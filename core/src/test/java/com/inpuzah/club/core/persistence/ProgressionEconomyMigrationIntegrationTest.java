package com.inpuzah.club.core.persistence;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProgressionEconomyMigrationIntegrationTest {

    private static final String PLAYER = "00000000-0000-0000-0000-000000000001";
    private static final String OPERATION = "00000000-0000-0000-0000-000000000002";
    private static final String COIN_INSERT = """
            INSERT INTO currency_transactions
                (operation_id, player_uuid, amount_delta, balance_after, operation_type, source, reason)
            VALUES ('%s', '%s', %d, %d, 'CREDIT', 'migration-test', 'test grant')
            """;
    private static final String XP_INSERT = """
            INSERT INTO experience_awards
                (operation_id, player_uuid, amount, experience_after, level_after, source, reason)
            VALUES ('%s', '%s', %d, %d, %d, 'migration-test', 'test award')
            """;

    private static EmbeddedPostgres postgres;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeAll
    static void startDatabase() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopDatabase() throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void isolateSchema() throws SQLException {
        schema = "test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = postgres.getPostgresDatabase().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        dataSource = new PGSimpleDataSource();
        dataSource.setServerNames(new String[]{"127.0.0.1"});
        dataSource.setPortNumbers(new int[]{postgres.getPort()});
        dataSource.setDatabaseName("postgres");
        dataSource.setUser("postgres");
        dataSource.setCurrentSchema(schema);
    }

    @Test
    void freshInstallCreatesLedgersAndDescendingPlayerHistoryIndexes() throws SQLException {
        MigrateResult result = migrate();

        assertEquals(2, result.migrationsExecuted);
        assertEquals("2", result.targetSchemaVersion);
        assertEquals("0", scalar("SELECT COUNT(*) FROM currency_transactions"));
        assertEquals("0", scalar("SELECT COUNT(*) FROM experience_awards"));
        for (String table : new String[]{"currency_transactions", "experience_awards"}) {
            String index = scalar("""
                    SELECT indexdef FROM pg_indexes
                    WHERE schemaname = current_schema() AND indexname = 'idx_%s_player_time'
                    """.formatted(table));
            assertTrue(index.contains("(player_uuid, created_at DESC)"), index);
            assertEquals("timestamp with time zone", scalar("""
                    SELECT data_type FROM information_schema.columns
                    WHERE table_schema = current_schema() AND table_name = '%s' AND column_name = 'created_at'
                    """.formatted(table)));
        }
    }

    @Test
    void upgradePreservesEveryExistingProfileColumn() throws SQLException {
        Flyway.configure(getClass().getClassLoader())
                .dataSource(dataSource)
                .locations(Migrations.LOCATION)
                .target("1")
                .load()
                .migrate();
        execute("""
                INSERT INTO player_profiles
                    (uuid, username, first_joined_at, last_seen_at, level, experience, coins, created_at, updated_at)
                VALUES
                    ('%s', 'ExistingPlayer', '2026-01-02T03:04:05Z', '2026-02-03T04:05:06Z',
                     7, 2100, 900, '2026-01-02T03:04:05Z', '2026-02-03T04:05:06Z'),
                    ('00000000-0000-0000-0000-000000000003', 'NewPlayer', NOW(), NOW(), 1, 0, 0, NOW(), NOW())
                """.formatted(PLAYER));
        String before = scalar("SELECT json_agg(p ORDER BY uuid)::text FROM player_profiles p");

        MigrateResult result = migrate();

        assertEquals(1, result.migrationsExecuted);
        assertEquals("2", result.targetSchemaVersion);
        assertEquals(before, scalar("SELECT json_agg(p ORDER BY uuid)::text FROM player_profiles p"));
    }

    @Test
    void repeatStartupDoesNotReapplyMigrationsOrChangeLedgerRows() throws SQLException {
        migrate();
        insertPlayer();
        execute(COIN_INSERT.formatted(OPERATION, PLAYER, 25, 25));
        execute(XP_INSERT.formatted(UUID.randomUUID(), PLAYER, 100, 100, 2));
        String coins = scalar("SELECT row_to_json(t)::text FROM currency_transactions t");
        String xp = scalar("SELECT row_to_json(t)::text FROM experience_awards t");

        assertEquals(0, migrate().migrationsExecuted);
        assertEquals(coins, scalar("SELECT row_to_json(t)::text FROM currency_transactions t"));
        assertEquals(xp, scalar("SELECT row_to_json(t)::text FROM experience_awards t"));
    }

    @Test
    void acceptsCreditDebitAndXpRowsWithOptionalActor() throws SQLException {
        migrate();
        insertPlayer();
        execute(COIN_INSERT.formatted(OPERATION, PLAYER, 100, 100));
        execute(COIN_INSERT.formatted(UUID.randomUUID(), PLAYER, -25, 75).replace("'CREDIT'", "'DEBIT'"));
        execute(XP_INSERT.formatted(OPERATION, PLAYER, 100, 100, 2));
        execute("UPDATE experience_awards SET actor_uuid = '" + UUID.randomUUID() + "'");

        assertEquals("2", scalar("SELECT COUNT(*) FROM currency_transactions"));
        assertEquals("1", scalar("SELECT COUNT(*) FROM experience_awards WHERE actor_uuid IS NOT NULL"));
        assertEquals("2", scalar("SELECT COUNT(*) FROM currency_transactions WHERE created_at IS NOT NULL"));
    }

    @ParameterizedTest
    @MethodSource("invalidLedgerRows")
    void rejectsInvalidLedgerRows(String sql, String sqlState) throws SQLException {
        migrate();
        insertPlayer();

        SQLException error = assertThrows(SQLException.class, () -> execute(sql));

        assertEquals(sqlState, error.getSQLState());
    }

    static Stream<Arguments> invalidLedgerRows() {
        String missingPlayer = "00000000-0000-0000-0000-000000000099";
        return Stream.of(
                Arguments.of(COIN_INSERT.formatted(OPERATION, PLAYER, 0, 0), "23514"),
                Arguments.of(COIN_INSERT.formatted(OPERATION, PLAYER, 1, -1), "23514"),
                Arguments.of(XP_INSERT.formatted(OPERATION, PLAYER, 0, 0, 1), "23514"),
                Arguments.of(XP_INSERT.formatted(OPERATION, PLAYER, -1, 0, 1), "23514"),
                Arguments.of(XP_INSERT.formatted(OPERATION, PLAYER, 1, -1, 1), "23514"),
                Arguments.of(XP_INSERT.formatted(OPERATION, PLAYER, 1, 1, 0), "23514"),
                Arguments.of(COIN_INSERT.formatted(OPERATION, missingPlayer, 1, 1), "23503"),
                Arguments.of(XP_INSERT.formatted(OPERATION, missingPlayer, 1, 1, 1), "23503"),
                Arguments.of(COIN_INSERT.formatted(OPERATION, PLAYER, 1, 1).replace("'test grant'", "NULL"), "23502"),
                Arguments.of(XP_INSERT.formatted(OPERATION, PLAYER, 1, 1, 1).replace("'migration-test'", "NULL"), "23502"));
    }

    @Test
    void duplicateOperationIdsAreRejectedWithinEachLedger() throws SQLException {
        migrate();
        insertPlayer();
        String coins = COIN_INSERT.formatted(OPERATION, PLAYER, 25, 25);
        String xp = XP_INSERT.formatted(OPERATION, PLAYER, 100, 100, 2);
        execute(coins);
        execute(xp);

        assertEquals("23505", assertThrows(SQLException.class, () -> execute(coins)).getSQLState());
        assertEquals("23505", assertThrows(SQLException.class, () -> execute(xp)).getSQLState());
        assertEquals("1", scalar("SELECT COUNT(*) FROM currency_transactions"));
        assertEquals("1", scalar("SELECT COUNT(*) FROM experience_awards"));
    }

    @Test
    void profileDeletionCannotOrphanAuditRows() throws SQLException {
        migrate();
        insertPlayer();
        execute(COIN_INSERT.formatted(OPERATION, PLAYER, 25, 25));
        assertEquals("23503", assertThrows(SQLException.class,
                () -> execute("DELETE FROM player_profiles")).getSQLState());
        execute("DELETE FROM currency_transactions");
        execute(XP_INSERT.formatted(OPERATION, PLAYER, 100, 100, 2));
        assertEquals("23503", assertThrows(SQLException.class,
                () -> execute("DELETE FROM player_profiles")).getSQLState());
    }

    @Test
    void schemaOwningNonSuperuserCanRunMigrations() throws SQLException {
        String role = "app_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE ROLE " + role + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE");
        execute("ALTER SCHEMA " + schema + " OWNER TO " + role);
        dataSource.setUser(role);

        assertEquals("false", scalar("SELECT rolsuper::text FROM pg_roles WHERE rolname = current_user"));
        assertEquals(2, migrate().migrationsExecuted);
        insertPlayer();
        execute(COIN_INSERT.formatted(OPERATION, PLAYER, 25, 25));
        execute(XP_INSERT.formatted(OPERATION, PLAYER, 100, 100, 2));
    }

    private MigrateResult migrate() {
        return Flyway.configure(getClass().getClassLoader()).dataSource(dataSource)
                .locations(Migrations.LOCATION).target("2").load().migrate();
    }

    private void insertPlayer() throws SQLException {
        execute("INSERT INTO player_profiles (uuid, username) VALUES ('" + PLAYER + "', 'TestPlayer')");
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String scalar(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), sql);
            return result.getString(1);
        }
    }
}
