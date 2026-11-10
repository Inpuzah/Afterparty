package com.inpuzah.club.core.repository;

import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.persistence.Migrations;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against a real PostgreSQL server (embedded binaries; no Docker required).
 */
class JdbcProfileRepositoryIntegrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private static MigrateResult initialMigration;

    private JdbcProfileRepository repository;

    @BeforeAll
    static void startDatabase() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        initialMigration = Migrations.apply(dataSource, JdbcProfileRepositoryIntegrationTest.class.getClassLoader());
    }

    @AfterAll
    static void stopDatabase() throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void cleanTable() throws SQLException {
        execute("TRUNCATE friendships, friend_requests, player_blocks, social_preferences, display_preferences, currency_transactions, experience_awards, player_profiles");
        repository = new JdbcProfileRepository(dataSource);
    }

    @Test
    void migrationsApplyToFreshDatabaseAndAreIdempotent() {
        assertEquals(4, initialMigration.migrationsExecuted);
        assertEquals("4", initialMigration.targetSchemaVersion);

        MigrateResult again = Migrations.apply(dataSource, getClass().getClassLoader());
        assertEquals(0, again.migrationsExecuted);
    }

    @Test
    void firstUpsertCreatesExactlyOneDefaultRow() throws SQLException {
        UUID id = UUID.randomUUID();

        PlayerProfile profile = repository.upsertOnJoin(id, "Newcomer");

        assertEquals(1, countRows(id));
        assertEquals(id, profile.uuid());
        assertEquals("Newcomer", profile.username());
        assertEquals(1, profile.level());
        assertEquals(0, profile.experience());
        assertEquals(0, profile.coins());
    }

    @Test
    void secondUpsertUpdatesNameWithoutResettingProgress() throws SQLException {
        UUID id = UUID.randomUUID();
        PlayerProfile first = repository.upsertOnJoin(id, "OldName");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE player_profiles SET level = 7, experience = 450, coins = 99 WHERE uuid = ?")) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }

        PlayerProfile second = repository.upsertOnJoin(id, "NewName");

        assertEquals(1, countRows(id));
        assertEquals("NewName", second.username());
        assertEquals(first.firstJoinedAt(), second.firstJoinedAt());
        assertFalse(second.lastSeenAt().isBefore(first.lastSeenAt()));
        assertEquals(7, second.level());
        assertEquals(450, second.experience());
        assertEquals(99, second.coins());
    }

    @Test
    void concurrentUpsertsForSameUuidDoNotDuplicate() throws Exception {
        UUID id = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<PlayerProfile>> calls = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                calls.add(() -> repository.upsertOnJoin(id, "Racer"));
            }
            for (Future<PlayerProfile> result : pool.invokeAll(calls)) {
                assertEquals(id, result.get().uuid());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, countRows(id));
    }

    @Test
    void findByIdDistinguishesMissingProfiles() {
        UUID id = UUID.randomUUID();
        assertTrue(repository.findById(id).isEmpty());

        repository.upsertOnJoin(id, "Someone");
        assertEquals("Someone", repository.findById(id).orElseThrow().username());
    }

    @Test
    void touchLastSeenReportsWhetherProfileExists() {
        UUID id = UUID.randomUUID();
        assertFalse(repository.touchLastSeen(id));

        PlayerProfile created = repository.upsertOnJoin(id, "Someone");
        assertTrue(repository.touchLastSeen(id));
        assertFalse(repository.findById(id).orElseThrow().lastSeenAt().isBefore(created.lastSeenAt()));
    }

    @Test
    void storesNonAsciiAndMaximumLengthNames() {
        UUID unicode = UUID.randomUUID();
        UUID longest = UUID.randomUUID();

        assertEquals("Ünïcödé_名前", repository.upsertOnJoin(unicode, "Ünïcödé_名前").username());
        assertEquals("Sixteen_Chars_12", repository.upsertOnJoin(longest, "Sixteen_Chars_12").username());
        assertThrows(IllegalArgumentException.class, () -> repository.upsertOnJoin(UUID.randomUUID(), "Seventeen_Chars_1"));
    }

    @Test
    void timestampsAreTimezoneAware() throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT column_name, data_type FROM information_schema.columns
                     WHERE table_name = 'player_profiles' AND column_name LIKE '%_at'
                     """)) {
            int columns = 0;
            while (rs.next()) {
                columns++;
                assertEquals("timestamp with time zone", rs.getString("data_type"), rs.getString("column_name"));
            }
            assertEquals(4, columns);
        }
    }

    @Test
    void databaseConstraintsRejectInvalidProgress() {
        UUID id = UUID.randomUUID();
        repository.upsertOnJoin(id, "Someone");

        assertThrows(SQLException.class, () -> execute("UPDATE player_profiles SET coins = -1"));
        assertThrows(SQLException.class, () -> execute("UPDATE player_profiles SET level = 0"));
    }

    @Test
    void unreachableDatabaseFailsWithStorageException() throws IOException {
        PGSimpleDataSource unreachable = new PGSimpleDataSource();
        unreachable.setServerNames(new String[]{"127.0.0.1"});
        unreachable.setPortNumbers(new int[]{freePort()});
        unreachable.setDatabaseName("club");
        unreachable.setUser("nobody");
        unreachable.setConnectTimeout(2);
        JdbcProfileRepository broken = new JdbcProfileRepository(unreachable);

        ProfileStorageException error = assertThrows(ProfileStorageException.class,
                () -> broken.upsertOnJoin(UUID.randomUUID(), "Someone"));
        assertNotNull(error.getCause());
    }

    @Test
    void profilesSurviveDatabaseRestart(@TempDir Path dataDirectory) throws Exception {
        UUID id = UUID.randomUUID();
        PlayerProfile before;
        try (EmbeddedPostgres first = EmbeddedPostgres.builder()
                .setDataDirectory(dataDirectory).setCleanDataDirectory(false).start()) {
            Migrations.apply(first.getPostgresDatabase(), getClass().getClassLoader());
            before = new JdbcProfileRepository(first.getPostgresDatabase()).upsertOnJoin(id, "Persistent");
        }

        try (EmbeddedPostgres second = EmbeddedPostgres.builder()
                .setDataDirectory(dataDirectory).setCleanDataDirectory(false).start()) {
            PlayerProfile after = new JdbcProfileRepository(second.getPostgresDatabase()).findById(id).orElseThrow();
            assertEquals(before.username(), after.username());
            assertEquals(before.firstJoinedAt(), after.firstJoinedAt());
        }
    }

    private static int countRows(UUID id) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM player_profiles WHERE uuid = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
