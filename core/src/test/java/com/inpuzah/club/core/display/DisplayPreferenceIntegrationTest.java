package com.inpuzah.club.core.display;

import com.inpuzah.club.core.persistence.Migrations;
import com.inpuzah.club.core.repository.ProfileStorageException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayPreferenceIntegrationTest {
    private static EmbeddedPostgres postgres;
    private static DataSource source;
    private JdbcDisplayPreferenceRepository repository;
    private UUID uuid;

    @BeforeAll
    static void start() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        source = postgres.getPostgresDatabase();
        Migrations.apply(source, DisplayPreferenceIntegrationTest.class.getClassLoader());
    }

    @AfterAll
    static void stop() throws Exception {
        postgres.close();
    }

    @BeforeEach
    void player() throws Exception {
        uuid = UUID.randomUUID();
        repository = new JdbcDisplayPreferenceRepository(source);
        execute(source, "INSERT INTO player_profiles(uuid, username) VALUES ('" + uuid + "', 'Player')");
    }

    @Test
    void defaultsAndIndependentFlagsPersistAcrossServiceRecreation() throws Exception {
        var service = new DisplayPreferenceService(repository);
        try {
            assertEquals(DisplayPreferences.DEFAULT, service.load(uuid).get(5, TimeUnit.SECONDS));
            assertEquals(new DisplayPreferences(false, true), service.save(uuid, true, false).get(5, TimeUnit.SECONDS));
            assertEquals(new DisplayPreferences(false, false), service.save(uuid, false, false).get(5, TimeUnit.SECONDS));
            assertEquals(new DisplayPreferences(false, false),
                    new JdbcDisplayPreferenceRepository(source).load(uuid));
            assertEquals(new DisplayPreferences(true, false), service.save(uuid, true, true).get(5, TimeUnit.SECONDS));
        } finally {
            assertEquals(0, service.shutdown(Duration.ofSeconds(5)));
        }
    }

    @Test
    void queuedToggleThenReconnectLoadPreservesOrder() throws Exception {
        var service = new DisplayPreferenceService(repository);
        try {
            var first = service.save(uuid, true, false);
            var second = service.save(uuid, false, false);
            var reconnect = service.load(uuid);
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(new DisplayPreferences(false, false), reconnect.get(5, TimeUnit.SECONDS));
        } finally {
            service.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void unknownPlayerSaveAndNullFlagsAreRejectedByDatabase() {
        assertThrows(ProfileStorageException.class, () -> repository.save(UUID.randomUUID(), true, false));
        assertThrows(java.sql.SQLException.class, () -> execute(source,
                "INSERT INTO display_preferences(player_uuid, show_bossbar) VALUES ('" + uuid + "', NULL)"));
    }

    @Test
    void freshInstallAndPopulatedV2UpgradePreserveProfileAndLedger() throws Exception {
        String schema = "display_" + UUID.randomUUID().toString().replace("-", "");
        execute(source, "CREATE SCHEMA " + schema);
        var isolated = new PGSimpleDataSource();
        isolated.setServerNames(new String[]{"127.0.0.1"});
        isolated.setPortNumbers(new int[]{postgres.getPort()});
        isolated.setDatabaseName("postgres");
        isolated.setUser("postgres");
        isolated.setCurrentSchema(schema);
        Flyway.configure(getClass().getClassLoader()).dataSource(isolated).locations(Migrations.LOCATION)
                .target("2").load().migrate();
        execute(isolated, "INSERT INTO player_profiles(uuid, username, coins) VALUES ('" + uuid + "', 'Player', 100)");
        execute(isolated, """
                INSERT INTO currency_transactions(operation_id, player_uuid, amount_delta, balance_after, operation_type, source, reason)
                VALUES ('%s', '%s', 100, 100, 'CREDIT', 'test', 'grant')
                """.formatted(UUID.randomUUID(), uuid));
        var result = Migrations.apply(isolated, getClass().getClassLoader());
        assertEquals(1, result.migrationsExecuted);
        assertEquals("3", result.targetSchemaVersion);
        var prefs = new JdbcDisplayPreferenceRepository(isolated);
        assertEquals(DisplayPreferences.DEFAULT, prefs.load(uuid));
        prefs.save(uuid, true, false);
        assertEquals(0, Migrations.apply(isolated, getClass().getClassLoader()).migrationsExecuted);
        try (Connection connection = isolated.getConnection(); Statement query = connection.createStatement();
             var rows = query.executeQuery("SELECT coins, (SELECT count(*) FROM currency_transactions) FROM player_profiles")) {
            assertTrue(rows.next());
            assertEquals(100, rows.getLong(1));
            assertEquals(1, rows.getInt(2));
        }
    }

    @Test
    void preferencesSurviveActualPostgresRestart(@TempDir Path directory) throws Exception {
        try (var first = EmbeddedPostgres.builder().setDataDirectory(directory).setCleanDataDirectory(false).start()) {
            Migrations.apply(first.getPostgresDatabase(), getClass().getClassLoader());
            execute(first.getPostgresDatabase(), "INSERT INTO player_profiles(uuid, username) VALUES ('" + uuid + "', 'Player')");
            new JdbcDisplayPreferenceRepository(first.getPostgresDatabase()).save(uuid, true, false);
        }
        try (var second = EmbeddedPostgres.builder().setDataDirectory(directory).setCleanDataDirectory(false).start()) {
            assertEquals(new DisplayPreferences(false, true),
                    new JdbcDisplayPreferenceRepository(second.getPostgresDatabase()).load(uuid));
        }
    }

    private static void execute(DataSource dataSource, String sql) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
