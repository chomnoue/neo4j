/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.backup.impl;

import org.apache.commons.lang3.SystemUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.neo4j.graphdb.DatabaseShutdownException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.configuration.Settings;
import org.neo4j.kernel.impl.enterprise.configuration.OnlineBackupSettings;
import org.neo4j.kernel.impl.store.format.highlimit.HighLimit;
import org.neo4j.kernel.impl.store.format.standard.Standard;
import org.neo4j.kernel.impl.transaction.log.files.LogFiles;
import org.neo4j.kernel.impl.transaction.log.files.LogFilesBuilder;
import org.neo4j.ports.allocation.PortAuthority;
import org.neo4j.test.DbRepresentation;
import org.neo4j.test.rule.EmbeddedDatabaseRule;
import org.neo4j.test.rule.SuppressOutput;
import org.neo4j.test.rule.TestDirectory;

import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.neo4j.util.TestHelpers.runBackupToolFromOtherJvmToGetExitCode;

@RunWith( Parameterized.class )
public class OnlineBackupCommandHaIT
{
    @ClassRule
    public static final TestDirectory testDirectory = TestDirectory.testDirectory();

    private final EmbeddedDatabaseRule db = new EmbeddedDatabaseRule().startLazily();

    @Rule
    public final RuleChain ruleChain = RuleChain.outerRule( SuppressOutput.suppressAll() ).around( db );

    private final File backupDir = testDirectory.directory( "backups" );

    @Parameter
    public String recordFormat;

    @Parameters( name = "{0}" )
    public static List<String> recordFormats()
    {
        return Arrays.asList( Standard.LATEST_NAME, HighLimit.NAME );
    }

    private List<Runnable> oneOffShutdownTasks;
    private static final Label label = Label.label( "any_label" );
    private static final String PROP_NAME = "name";
    private static final String PROP_RANDOM = "random";

    private static void createSomeData( GraphDatabaseService db )
    {
        try ( Transaction tx = db.beginTx() )
        {
            Node node = db.createNode();
            node.setProperty( "name", "Neo" );
            db.createNode().createRelationshipTo( node, RelationshipType.withName( "KNOWS" ) );
            tx.success();
        }
    }

    @Before
    public void resetTasks()
    {
        oneOffShutdownTasks = new ArrayList<>();
    }

    @After
    public void shutdownTasks()
    {
        oneOffShutdownTasks.forEach( Runnable::run );
    }

    @Test
    public void makeSureBackupCanBePerformedWithCustomPort() throws Exception
    {
        assumeFalse( SystemUtils.IS_OS_WINDOWS );
        String backupName = "customport" + recordFormat; // due to ClassRule not cleaning between tests

        int backupPort = PortAuthority.allocatePort();
        startDb( backupPort );
        assertEquals( "should not be able to do backup when noone is listening",
                1,
                runBackupTool( "--from", "127.0.0.1:" + PortAuthority.allocatePort(),
                        "--cc-report-dir=" + backupDir,
                        "--backup-dir=" + backupDir,
                        "--name=" + backupName ) );
        assertEquals(
                0,
                runBackupTool( "--from", "127.0.0.1:" + backupPort,
                        "--cc-report-dir=" + backupDir,
                        "--backup-dir=" + backupDir,
                        "--name=" + backupName ) );
        assertEquals( getDbRepresentation(), getBackupDbRepresentation( backupName ) );
        createSomeData( db );
        assertEquals(
                0,
                runBackupTool( "--from", "127.0.0.1:" + backupPort,
                        "--cc-report-dir=" + backupDir,
                        "--backup-dir=" + backupDir,
                        "--name=" + backupName ) );
        assertEquals( getDbRepresentation(), getBackupDbRepresentation( backupName ) );
    }

    @Test
    public void dataIsInAUsableStateAfterBackup() throws Exception
    {
        // given database exists
        int backupPort = PortAuthority.allocatePort();
        startDb( backupPort );

        // and the database has indexes
        createIndexes( db );

        // and the database is being populated
        AtomicBoolean continueFlagReference = new AtomicBoolean( true );
        new Thread( () -> repeatedlyPopulateDatabase( db, continueFlagReference ) ).start(); // populate db with number properties etc.
        oneOffShutdownTasks.add( () -> continueFlagReference.set( false ) ); // kill thread

        // then backup is successful
        String ip = ":" + backupPort;
        assertEquals( 0, runBackupTool( "--from", ip, "--cc-report-dir=" + backupDir, "--backup-dir=" + backupDir, "--name=defaultport" + recordFormat ) );
        db.shutdown();
    }

    @Test
    public void backupDatabaseTransactionLogsStoredWithDatabase() throws Exception
    {
        int backupPort = PortAuthority.allocatePort();
        startDb( backupPort );
        String ip = ":" + backupPort;
        String name = "backupWithTxLogs" + recordFormat;
        assertEquals( 0, runBackupTool( "--from", ip, "--cc-report-dir=" + backupDir, "--backup-dir=" + backupDir,
                "--name=" + name ) );
        db.shutdown();

        try ( DefaultFileSystemAbstraction fileSystem = new DefaultFileSystemAbstraction() )
        {
            LogFiles logFiles = LogFilesBuilder.logFilesBasedOnlyBuilder( new File( backupDir, name ), fileSystem ).build();
            assertTrue( logFiles.versionExists( 0 ) );
            assertThat( logFiles.getLogFileForVersion( 0 ).length(), greaterThan( 50L ) );
        }
    }

    @Test
    public void backupFailsWithCatchupProtoOverride() throws Exception
    {
        String backupName = "customport" + recordFormat; // due to ClassRule not cleaning between tests

        int backupPort = PortAuthority.allocatePort();
        startDb( backupPort );

        assertEquals(
                1,
                runBackupTool( "--from", "127.0.0.1:" + backupPort,
                        "--cc-report-dir=" + backupDir,
                        "--backup-dir=" + backupDir,
                        "--proto=catchup",
                        "--name=" + backupName ) );
    }

    private void repeatedlyPopulateDatabase( GraphDatabaseService db, AtomicBoolean continueFlagReference )
    {
        while ( continueFlagReference.get() )
        {
            try
            {
                createSomeData( db );
            }
            catch ( DatabaseShutdownException ex )
            {
                break;
            }
        }
    }

    private void createIndexes( GraphDatabaseService db )
    {
        try ( Transaction tx = db.beginTx() )
        {
            db.schema().indexFor( label ).on( PROP_NAME ).on( PROP_RANDOM ).create();
            tx.success();
        }
    }

    private void startDb( Integer backupPort )
    {
        db.setConfig( GraphDatabaseSettings.record_format, recordFormat );
        db.setConfig( OnlineBackupSettings.online_backup_enabled, Settings.TRUE );
        db.setConfig( OnlineBackupSettings.online_backup_server, "127.0.0.1" + ":" + backupPort );
        db.ensureStarted();
        createSomeData( db );
    }

    private static int runBackupTool( String... args )
            throws Exception
    {
        return runBackupToolFromOtherJvmToGetExitCode( testDirectory.absolutePath(), args );
    }

    private DbRepresentation getDbRepresentation()
    {
        return DbRepresentation.of( db );
    }

    private DbRepresentation getBackupDbRepresentation( String name )
    {
        Config config = Config.defaults( OnlineBackupSettings.online_backup_enabled, Settings.FALSE );

        return DbRepresentation.of( new File( backupDir, name ), config );
    }
}
