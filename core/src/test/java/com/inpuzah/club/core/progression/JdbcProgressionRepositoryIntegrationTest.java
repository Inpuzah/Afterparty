package com.inpuzah.club.core.progression;

import com.inpuzah.club.core.persistence.Migrations;
import com.inpuzah.club.core.repository.ProfileStorageException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcProgressionRepositoryIntegrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private JdbcProgressionRepository repository;
    private UUID playerId;

    @BeforeAll
    static void startDatabase() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        Migrations.apply(dataSource, JdbcProgressionRepositoryIntegrationTest.class.getClassLoader());
    }

    @AfterAll
    static void stopDatabase() throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void reset() throws SQLException {
        execute("TRUNCATE display_preferences, currency_transactions, experience_awards, player_profiles");
        repository = new JdbcProgressionRepository(dataSource);
        playerId = UUID.randomUUID();
        insertPlayer(playerId);
    }

    @Test
    void awardPersistsXpLevelAndAuditMetadataWithoutTouchingOtherFields() throws SQLException {
        execute("UPDATE player_profiles SET coins = 123");
        String before = scalar("""
                SELECT json_build_array(uuid, username, first_joined_at, last_seen_at, coins, created_at)::text
                FROM player_profiles
                """);
        XpAwardRequest request = request(UUID.randomUUID(), 300);

        XpAwardResult result = repository.awardXp(request);

        assertEquals(0, result.previousExperience());
        assertEquals(300, result.experience());
        assertEquals(1, result.previousLevel());
        assertEquals(3, result.level());
        assertFalse(result.duplicateReplay());
        assertEquals(new ProgressionSnapshot(playerId, 300), repository.getProgress(playerId));
        assertEquals(before, scalar("""
                SELECT json_build_array(uuid, username, first_joined_at, last_seen_at, coins, created_at)::text
                FROM player_profiles
                """));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM experience_awards WHERE operation_id = ?")) {
            statement.setObject(1, request.operationId());
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(playerId, rs.getObject("player_uuid", UUID.class));
                assertEquals(300, rs.getLong("amount"));
                assertEquals(300, rs.getLong("experience_after"));
                assertEquals(3, rs.getInt("level_after"));
                assertEquals(request.source(), rs.getString("source"));
                assertEquals(request.reason(), rs.getString("reason"));
                assertEquals(request.actorId(), rs.getObject("actor_uuid", UUID.class));
                assertNotNull(rs.getTimestamp("created_at"));
            }
        }
        assertEquals("3", scalar("SELECT level FROM player_profiles"));
    }

    @Test
    void replayReturnsOriginalResultAfterOtherAwardsAndRepositoryRecreation() throws SQLException {
        XpAwardRequest request = request(UUID.randomUUID(), 100);
        XpAwardResult first = repository.awardXp(request);
        repository.awardXp(request(UUID.randomUUID(), 200));
        String updated = scalar("SELECT updated_at::text FROM player_profiles");

        XpAwardResult replay = new JdbcProgressionRepository(dataSource).awardXp(request);

        assertTrue(replay.duplicateReplay());
        assertEquals(first.previousExperience(), replay.previousExperience());
        assertEquals(first.experience(), replay.experience());
        assertEquals(first.previousLevel(), replay.previousLevel());
        assertEquals(first.level(), replay.level());
        assertEquals(300, repository.getProgress(playerId).experience());
        assertEquals("2", scalar("SELECT COUNT(*) FROM experience_awards"));
        assertEquals(updated, scalar("SELECT updated_at::text FROM player_profiles"));
    }

    @Test
    void conflictingReplayRejectsEveryChangedInput() throws SQLException {
        XpAwardRequest original = request(UUID.randomUUID(), 100);
        repository.awardXp(original);
        UUID anotherPlayer = UUID.randomUUID();
        insertPlayer(anotherPlayer);
        List<XpAwardRequest> conflicts = List.of(
                new XpAwardRequest(original.operationId(), anotherPlayer, 100, original.source(), original.reason(), original.actorId()),
                new XpAwardRequest(original.operationId(), playerId, 101, original.source(), original.reason(), original.actorId()),
                new XpAwardRequest(original.operationId(), playerId, 100, "other-source", original.reason(), original.actorId()),
                new XpAwardRequest(original.operationId(), playerId, 100, original.source(), "other-reason", original.actorId()),
                new XpAwardRequest(original.operationId(), playerId, 100, original.source(), original.reason(), null));
        for (XpAwardRequest conflict : conflicts) {
            assertEquals(ProgressionException.Code.OPERATION_CONFLICT,
                    assertThrows(ProgressionException.class, () -> repository.awardXp(conflict)).code());
        }
        assertEquals(100, repository.getProgress(playerId).experience());
        assertEquals(0, repository.getProgress(anotherPlayer).experience());
        assertEquals("1", scalar("SELECT COUNT(*) FROM experience_awards"));
    }

    @Test
    void concurrentDistinctAwardsAllCountExactlyOnce() throws Exception {
        List<Callable<XpAwardResult>> actions = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            XpAwardRequest request = request(UUID.randomUUID(), 100);
            actions.add(() -> repository.awardXp(request));
        }
        List<XpAwardResult> results = concurrently(actions);

        assertEquals(24, results.stream().map(XpAwardResult::experience).distinct().count());
        assertEquals(2400, repository.getProgress(playerId).experience());
        assertEquals("24", scalar("SELECT COUNT(*) FROM experience_awards"));
        assertEquals("2400", scalar("SELECT SUM(amount) FROM experience_awards"));
        assertEquals("7", scalar("SELECT level FROM player_profiles"));
    }

    @Test
    void concurrentDuplicateAwardsCommitOnlyOnce() throws Exception {
        XpAwardRequest request = request(UUID.randomUUID(), 100);
        List<Callable<XpAwardResult>> actions = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            actions.add(() -> repository.awardXp(request));
        }

        List<XpAwardResult> results = concurrently(actions);

        assertEquals(1, results.stream().filter(result -> !result.duplicateReplay()).count());
        assertTrue(results.stream().allMatch(result -> result.experience() == 100 && result.previousExperience() == 0));
        assertEquals(100, repository.getProgress(playerId).experience());
        assertEquals("1", scalar("SELECT COUNT(*) FROM experience_awards"));
    }

    @Test
    void crossPlayerOperationIdRaceRollsBackLosingPlayer() throws Exception {
        UUID other = UUID.randomUUID();
        insertPlayer(other);
        UUID operationId = UUID.randomUUID();
        // Force both transactions past their initial replay check before either inserts the ledger row.
        execute("""
                CREATE FUNCTION slow_xp_update() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM pg_sleep(0.3); RETURN NEW; END $$
                """);
        execute("""
                CREATE TRIGGER slow_xp BEFORE UPDATE ON player_profiles
                FOR EACH ROW EXECUTE FUNCTION slow_xp_update()
                """);
        try {
            List<ProgressionException.Code> outcomes = concurrently(List.of(
                    () -> tryAward(request(operationId, 100)),
                    () -> tryAward(new XpAwardRequest(operationId, other, 100, "test", "award", null))));

            assertEquals(1, outcomes.stream().filter(code -> code == ProgressionException.Code.OPERATION_CONFLICT).count());
            assertEquals("100", scalar("SELECT SUM(experience) FROM player_profiles"));
            assertEquals("1", scalar("SELECT COUNT(*) FROM experience_awards"));
        } finally {
            execute("DROP TRIGGER slow_xp ON player_profiles");
            execute("DROP FUNCTION slow_xp_update()");
        }
    }

    @Test
    void independentPlayersDoNotWaitForAnotherPlayersRowLock() throws Exception {
        UUID other = UUID.randomUUID();
        insertPlayer(other);
        var pool = Executors.newSingleThreadExecutor();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement lock = connection.prepareStatement("SELECT uuid FROM player_profiles WHERE uuid = ? FOR UPDATE")) {
            connection.setAutoCommit(false);
            lock.setObject(1, playerId);
            try (ResultSet ignored = lock.executeQuery()) {
                assertTrue(ignored.next());
                Future<XpAwardResult> result = pool.submit(() -> repository.awardXp(
                        new XpAwardRequest(UUID.randomUUID(), other, 100, "test", "award", null)));
                assertEquals(100, result.get(5, TimeUnit.SECONDS).experience());
            } finally {
                connection.rollback();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void insertFailureRollsBackBothProfileAndLedger() throws SQLException {
        String before = scalar("SELECT row_to_json(p)::text FROM player_profiles p");
        execute("""
                CREATE FUNCTION fail_xp_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'simulated ledger insert failure'; END $$
                """);
        execute("""
                CREATE TRIGGER fail_xp BEFORE INSERT ON experience_awards
                FOR EACH ROW EXECUTE FUNCTION fail_xp_insert()
                """);
        XpAwardRequest request = request(UUID.randomUUID(), 100);
        try {
            ProfileStorageException error = assertThrows(ProfileStorageException.class, () -> repository.awardXp(request));
            assertInstanceOf(SQLException.class, error.getCause());
            assertEquals(before, scalar("SELECT row_to_json(p)::text FROM player_profiles p"));
            assertEquals("0", scalar("SELECT COUNT(*) FROM experience_awards"));
        } finally {
            execute("DROP TRIGGER fail_xp ON experience_awards");
            execute("DROP FUNCTION fail_xp_insert()");
        }
        assertFalse(repository.awardXp(request).duplicateReplay());
        assertEquals(100, repository.getProgress(playerId).experience());
    }

    @Test
    void continuesAccumulatingAtLevel100AndRejectsLongOverflow() throws SQLException {
        repository.awardXp(request(UUID.randomUUID(), Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, repository.getProgress(playerId).experience());
        assertEquals(100, repository.getProgress(playerId).level());
        assertEquals(0, repository.getProgress(playerId).remainingToNextLevel());
        assertEquals("100", scalar("SELECT level FROM player_profiles"));
        String before = scalar("SELECT row_to_json(p)::text FROM player_profiles p");

        assertEquals(ProgressionException.Code.XP_OVERFLOW,
                assertThrows(ProgressionException.class, () -> repository.awardXp(request(UUID.randomUUID(), 1))).code());
        assertEquals(before, scalar("SELECT row_to_json(p)::text FROM player_profiles p"));
        assertEquals("1", scalar("SELECT COUNT(*) FROM experience_awards"));
    }

    @Test
    void missingProfileIsDistinctFromStorageFailure() {
        UUID missing = UUID.randomUUID();
        assertEquals(ProgressionException.Code.PROFILE_NOT_FOUND,
                assertThrows(ProgressionException.class, () -> repository.getProgress(missing)).code());
        assertEquals(ProgressionException.Code.PROFILE_NOT_FOUND,
                assertThrows(ProgressionException.class, () -> repository.awardXp(
                        new XpAwardRequest(UUID.randomUUID(), missing, 1, "test", "award", null))).code());
    }

    @Test
    void unavailableDatabaseNeverReportsSuccess() throws IOException {
        PGSimpleDataSource unavailable = new PGSimpleDataSource();
        unavailable.setServerNames(new String[]{"127.0.0.1"});
        try (ServerSocket socket = new ServerSocket(0)) {
            unavailable.setPortNumbers(new int[]{socket.getLocalPort()});
        }
        unavailable.setDatabaseName("postgres");
        unavailable.setUser("postgres");
        unavailable.setConnectTimeout(1);
        JdbcProgressionRepository broken = new JdbcProgressionRepository(unavailable);

        assertThrows(ProfileStorageException.class, () -> broken.getProgress(playerId));
        assertThrows(ProfileStorageException.class, () -> broken.awardXp(request(UUID.randomUUID(), 1)));
    }

    @Test
    void asynchronousServiceRunsFullAwardAndReplayAgainstPostgres() throws Exception {
        DefaultProgressionService service = DefaultProgressionService.create(repository, 2, 10);
        UUID operation = UUID.randomUUID();
        try {
            XpAwardResult first = service.awardXp(operation, playerId, 100, "test", "award", null).get(5, TimeUnit.SECONDS);
            XpAwardResult replay = service.awardXp(operation, playerId, 100, "test", "award", null).get(5, TimeUnit.SECONDS);
            assertFalse(first.duplicateReplay());
            assertTrue(replay.duplicateReplay());
            assertEquals(100, service.getProgress(playerId).get(5, TimeUnit.SECONDS).experience());
        } finally {
            assertEquals(0, service.shutdown(java.time.Duration.ofSeconds(5)));
        }

    }

    @Test
    void retryAfterCallerTimeoutDoesNotDoubleGrantXp() throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        CompletableFuture<Void> releaseResponse = new CompletableFuture<>();
        ProgressionRepository delayedResponse = new ProgressionRepository() {
            @Override
            public ProgressionSnapshot getProgress(UUID id) {
                return repository.getProgress(id);
            }

            @Override
            public XpAwardResult awardXp(XpAwardRequest request) {
                XpAwardResult result = repository.awardXp(request);
                if (!result.duplicateReplay()) {
                    committed.countDown();
                    releaseResponse.join();
                }
                return result;
            }
        };
        DefaultProgressionService service = DefaultProgressionService.create(delayedResponse, 2, 10);
        UUID operation = UUID.randomUUID();
        try {
            var first = service.awardXp(operation, playerId, 100, "test", "award", null);
            assertTrue(committed.await(5, TimeUnit.SECONDS));
            first.orTimeout(1, TimeUnit.MILLISECONDS);
            assertInstanceOf(java.util.concurrent.TimeoutException.class,
                    assertThrows(java.util.concurrent.ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS)).getCause());
            XpAwardResult retry = service.awardXp(operation, playerId, 100, "test", "award", null)
                    .get(5, TimeUnit.SECONDS);
            assertTrue(retry.duplicateReplay());
            assertEquals(100, repository.getProgress(playerId).experience());
            assertEquals("1", scalar("SELECT COUNT(*) FROM experience_awards"));
        } finally {
            releaseResponse.complete(null);
            service.shutdown(java.time.Duration.ofSeconds(5));
        }
    }

    private ProgressionException.Code tryAward(XpAwardRequest request) {
        try {
            repository.awardXp(request);
            return null;
        } catch (ProgressionException e) {
            return e.code();
        }
    }

    private static <T> List<T> concurrently(List<Callable<T>> actions) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(actions.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> action : actions) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return action.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private XpAwardRequest request(UUID operationId, long amount) {
        return new XpAwardRequest(operationId, playerId, amount, "test", "award", playerId);
    }

    private void insertPlayer(UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("INSERT INTO player_profiles(uuid, username) VALUES (?, 'Player')")) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String scalar(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }
}
