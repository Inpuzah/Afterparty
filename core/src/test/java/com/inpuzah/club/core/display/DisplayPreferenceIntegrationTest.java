package com.inpuzah.club.core.display;

import com.inpuzah.club.core.persistence.Migrations;
import com.inpuzah.club.core.repository.ProfileStorageException;
import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.session.ProfileSessionManager;
import com.inpuzah.club.core.settings.DefaultPlayerSettingsService;
import com.inpuzah.club.core.settings.PlayerSettings;
import org.slf4j.LoggerFactory;
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
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;

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
        assertEquals(2, result.migrationsExecuted);
        assertEquals("4", result.targetSchemaVersion);
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
            var service = settings(first.getPostgresDatabase());
            try {
                service.get(uuid).get(5, TimeUnit.SECONDS);
                service.setBossbarVisible(uuid, false).get(5, TimeUnit.SECONDS);
            } finally {
                service.shutdown(Duration.ofSeconds(5));
            }
        }
        try (var second = EmbeddedPostgres.builder().setDataDirectory(directory).setCleanDataDirectory(false).start()) {
            var service = settings(second.getPostgresDatabase());
            try {
                assertEquals(new PlayerSettings(uuid, false, true), service.get(uuid).get(5, TimeUnit.SECONDS));
            } finally {
                service.shutdown(Duration.ofSeconds(5));
            }
        }
    }

    @Test
    void unifiedApiReusesV3DefaultsAndExistingChoicesWithoutMigration() throws Exception {
        var defaults = settings(source);
        try {
            assertEquals(new PlayerSettings(uuid, true, true), defaults.get(uuid).get(5, TimeUnit.SECONDS));
        } finally {
            defaults.shutdown(Duration.ofSeconds(5));
        }
        repository.save(uuid, true, false);
        var upgraded = settings(source);
        try {
            assertEquals(new PlayerSettings(uuid, false, true), upgraded.get(uuid).get(5, TimeUnit.SECONDS));
            upgraded.setScoreboardVisible(uuid, false).get(5, TimeUnit.SECONDS);
            assertEquals(new DisplayPreferences(false, false), repository.load(uuid));
        } finally {
            upgraded.shutdown(Duration.ofSeconds(5));
        }
        var recreated = settings(source);
        try {
            assertEquals(new PlayerSettings(uuid, false, false), recreated.get(uuid).get(5, TimeUnit.SECONDS));
            assertEquals(0, Migrations.apply(source, getClass().getClassLoader()).migrationsExecuted);
            assertEquals("4", Flyway.configure().dataSource(source).locations(Migrations.LOCATION)
                    .load().info().current().getVersion().getVersion());
        } finally {
            recreated.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void rejectedSqlWriteRollsBackAndKeepsLastCommittedSettings() throws Exception {
        repository.save(uuid, false, false);
        String constraint = "reject_settings_" + uuid.toString().replace("-", "");
        execute(source, "ALTER TABLE display_preferences ADD CONSTRAINT " + constraint
                + " CHECK (player_uuid <> '" + uuid + "' OR show_bossbar)");
        var service = settings(source);
        try {
            assertEquals(new PlayerSettings(uuid, true, false), service.get(uuid).get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> service.setBossbarVisible(uuid, false).get(5, TimeUnit.SECONDS));
            assertEquals(new DisplayPreferences(true, false), repository.load(uuid));
            assertEquals(new PlayerSettings(uuid, true, false), service.get(uuid).get(5, TimeUnit.SECONDS));
        } finally {
            service.shutdown(Duration.ofSeconds(5));
            execute(source, "ALTER TABLE display_preferences DROP CONSTRAINT " + constraint);
        }
    }

    private DefaultPlayerSettingsService settings(DataSource dataSource) {
        var sessions = new ProfileSessionManager();
        sessions.markReady(uuid, sessions.begin(uuid).token(), PlayerProfile.newProfile(uuid, "Player", Instant.EPOCH));
        var service = new DefaultPlayerSettingsService(
                new DisplayPreferenceService(new JdbcDisplayPreferenceRepository(dataSource)), sessions,
                Duration.ofSeconds(5), LoggerFactory.getLogger(getClass()));
        service.begin(uuid);
        return service;
    }

    private static void execute(DataSource dataSource, String sql) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
