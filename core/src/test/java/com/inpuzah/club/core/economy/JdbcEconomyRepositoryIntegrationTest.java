package com.inpuzah.club.core.economy;

import com.inpuzah.club.core.persistence.Migrations;
import com.inpuzah.club.core.progression.JdbcProgressionRepository;
import com.inpuzah.club.core.progression.XpAwardRequest;
import com.inpuzah.club.core.progression.DefaultProgressionService;
import com.inpuzah.club.core.repository.JdbcProfileRepository;
import com.inpuzah.club.core.service.DefaultProfileService;
import com.inpuzah.club.core.service.ProfileRefreshService;
import com.inpuzah.club.core.session.ProfileSessionManager;
import com.inpuzah.club.core.session.SessionState;
import com.inpuzah.club.core.repository.ProfileStorageException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.inpuzah.club.core.economy.CurrencyOperationResult.Status.INSUFFICIENT_FUNDS;
import static com.inpuzah.club.core.economy.CurrencyOperationResult.Status.SUCCESS;
import static com.inpuzah.club.core.economy.CurrencyOperationType.CREDIT;
import static com.inpuzah.club.core.economy.CurrencyOperationType.DEBIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcEconomyRepositoryIntegrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private JdbcEconomyRepository repository;
    private UUID playerId;

    @BeforeAll
    static void startDatabase() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        Migrations.apply(dataSource, JdbcEconomyRepositoryIntegrationTest.class.getClassLoader());
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
        repository = new JdbcEconomyRepository(dataSource);
        playerId = UUID.randomUUID();
        insertPlayer(playerId);
    }

    @Test
    void creditDebitAndReadPreserveOtherProfileColumnsAndRecordMetadata() throws SQLException {
        execute("UPDATE player_profiles SET experience = 300, level = 3");
        String before = scalar("""
                SELECT json_build_array(uuid, username, first_joined_at, last_seen_at, experience, level, created_at)::text
                FROM player_profiles
                """);
        CurrencyOperation credit = operation(UUID.randomUUID(), 100, CREDIT);

        var credited = repository.apply(credit);
        var debited = repository.apply(operation(UUID.randomUUID(), 75, DEBIT));

        assertEquals(SUCCESS, credited.status());
        assertEquals(0, credited.previousBalance());
        assertEquals(100, credited.balance());
        assertEquals(100, debited.previousBalance());
        assertEquals(25, debited.balance());
        assertEquals(new BalanceSnapshot(playerId, 25), repository.balance(playerId));
        assertEquals(before, scalar("""
                SELECT json_build_array(uuid, username, first_joined_at, last_seen_at, experience, level, created_at)::text
                FROM player_profiles
                """));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM currency_transactions WHERE operation_id = ?")) {
            statement.setObject(1, credit.operationId());
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(playerId, rs.getObject("player_uuid", UUID.class));
                assertEquals(100, rs.getLong("amount_delta"));
                assertEquals(100, rs.getLong("balance_after"));
                assertEquals("CREDIT", rs.getString("operation_type"));
                assertEquals(credit.source(), rs.getString("source"));
                assertEquals(credit.reason(), rs.getString("reason"));
                assertEquals(credit.actorId(), rs.getObject("actor_uuid", UUID.class));
                assertNotNull(rs.getTimestamp("created_at"));
            }
        }
        assertEquals("-75", scalar("SELECT amount_delta FROM currency_transactions WHERE operation_type = 'DEBIT'"));
        assertEquals("25", scalar("SELECT SUM(amount_delta) FROM currency_transactions"));
    }

    @Test
    void overdraftReturnsOutcomeWithoutWritesAndCanBeRetriedAfterFunding() throws SQLException {
        CurrencyOperation purchase = operation(UUID.randomUUID(), 75, DEBIT);
        String before = scalar("SELECT row_to_json(p)::text FROM player_profiles p");

        var denied = repository.apply(purchase);

        assertEquals(INSUFFICIENT_FUNDS, denied.status());
        assertEquals(0, denied.balance());
        assertFalse(denied.duplicateReplay());
        assertEquals(before, scalar("SELECT row_to_json(p)::text FROM player_profiles p"));
        assertEquals("0", scalar("SELECT COUNT(*) FROM currency_transactions"));
        repository.apply(operation(UUID.randomUUID(), 100, CREDIT));
        assertEquals(SUCCESS, repository.apply(purchase).status());
        assertEquals(25, repository.balance(playerId).coins());
        assertEquals("2", scalar("SELECT COUNT(*) FROM currency_transactions"));
    }

    @ParameterizedTest
    @EnumSource(CurrencyOperationType.class)
    void replayReturnsOriginalBalancesAfterLaterOperations(CurrencyOperationType type) throws SQLException {
        repository.apply(operation(UUID.randomUUID(), 200, CREDIT));
        CurrencyOperation request = operation(UUID.randomUUID(), 100, type);
        var first = repository.apply(request);
        repository.apply(operation(UUID.randomUUID(), 50, CREDIT));
        String updated = scalar("SELECT updated_at::text FROM player_profiles");

        var replay = new JdbcEconomyRepository(dataSource).apply(request);

        assertTrue(replay.duplicateReplay());
        assertEquals(first.previousBalance(), replay.previousBalance());
        assertEquals(first.balance(), replay.balance());
        assertEquals(SUCCESS, replay.status());
        assertEquals(first.balance() + 50, repository.balance(playerId).coins());
        assertEquals(updated, scalar("SELECT updated_at::text FROM player_profiles"));
        assertEquals("3", scalar("SELECT COUNT(*) FROM currency_transactions"));
    }

    @Test
    void rejectsReplayWithAnyChangedPayloadField() throws SQLException {
        CurrencyOperation original = operation(UUID.randomUUID(), 100, CREDIT);
        repository.apply(original);
        UUID other = UUID.randomUUID();
        insertPlayer(other);
        List<CurrencyOperation> conflicts = List.of(
                new CurrencyOperation(original.operationId(), other, 100, CREDIT, "test", "reward", playerId),
                new CurrencyOperation(original.operationId(), playerId, 101, CREDIT, "test", "reward", playerId),
                new CurrencyOperation(original.operationId(), playerId, 100, DEBIT, "test", "reward", playerId),
                new CurrencyOperation(original.operationId(), playerId, 100, CREDIT, "other-source", "reward", playerId),
                new CurrencyOperation(original.operationId(), playerId, 100, CREDIT, "test", "other-reason", playerId),
                new CurrencyOperation(original.operationId(), playerId, 100, CREDIT, "test", "reward", null));

        for (CurrencyOperation conflict : conflicts) {
            assertEquals(EconomyException.Code.OPERATION_CONFLICT,
                    assertThrows(EconomyException.class, () -> repository.apply(conflict)).code());
        }
        assertEquals(100, repository.balance(playerId).coins());
        assertEquals(0, repository.balance(other).coins());
        assertEquals("1", scalar("SELECT COUNT(*) FROM currency_transactions"));
    }

    @Test
    void exactCapIsAcceptedAndCreditsAboveCapOrLongOverflowAreRejected() throws SQLException {
        repository.apply(operation(UUID.randomUUID(), BalanceSnapshot.MAX_BALANCE, CREDIT));
        String before = scalar("SELECT row_to_json(p)::text FROM player_profiles p");

        for (long amount : new long[]{1, Long.MAX_VALUE}) {
            assertEquals(EconomyException.Code.BALANCE_LIMIT_EXCEEDED,
                    assertThrows(EconomyException.class,
                            () -> repository.apply(operation(UUID.randomUUID(), amount, CREDIT))).code());
        }
        assertEquals(before, scalar("SELECT row_to_json(p)::text FROM player_profiles p"));
        assertEquals("1", scalar("SELECT COUNT(*) FROM currency_transactions"));
        assertEquals(0, repository.apply(operation(UUID.randomUUID(), BalanceSnapshot.MAX_BALANCE, DEBIT)).balance());
    }

    @Test
    void legacyBalanceAboveCapCanBeReadAndDebitedButNotIncreased() throws SQLException {
        execute("UPDATE player_profiles SET coins = " + Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, repository.balance(playerId).coins());
        assertEquals(EconomyException.Code.BALANCE_LIMIT_EXCEEDED,
                assertThrows(EconomyException.class, () -> repository.apply(operation(UUID.randomUUID(), 1, CREDIT))).code());
        CurrencyOperation debit = operation(UUID.randomUUID(), Long.MAX_VALUE, DEBIT);
        assertEquals(0, repository.apply(debit).balance());
        var replay = repository.apply(debit);
        assertTrue(replay.duplicateReplay());
        assertEquals(Long.MAX_VALUE, replay.previousBalance());
        assertEquals(0, replay.balance());
    }

    @ParameterizedTest
    @EnumSource(CurrencyOperationType.class)
    void concurrentDuplicateRequestsCommitOnce(CurrencyOperationType type) throws Exception {
        if (type == DEBIT) {
            repository.apply(operation(UUID.randomUUID(), 100, CREDIT));
        }
        CurrencyOperation request = operation(UUID.randomUUID(), 75, type);
        List<Callable<CurrencyOperationResult>> actions = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            actions.add(() -> repository.apply(request));
        }

        var results = concurrently(actions);

        assertEquals(1, results.stream().filter(result -> !result.duplicateReplay()).count());
        assertTrue(results.stream().allMatch(result -> result.status() == SUCCESS));
        assertEquals(1, results.stream().map(CurrencyOperationResult::balance).distinct().count());
        assertEquals(type == CREDIT ? 75 : 25, repository.balance(playerId).coins());
        assertEquals("1", scalar("SELECT COUNT(*) FROM currency_transactions WHERE operation_id = '" + request.operationId() + "'"));
    }

    @Test
    void twoSeventyFiveCoinPurchasesCannotOverspendOneHundredCoins() throws Exception {
        repository.apply(operation(UUID.randomUUID(), 100, CREDIT));
        List<CurrencyOperationResult> results = concurrently(List.of(
                () -> repository.apply(operation(UUID.randomUUID(), 75, DEBIT)),
                () -> repository.apply(operation(UUID.randomUUID(), 75, DEBIT))));

        assertEquals(1, results.stream().filter(result -> result.status() == SUCCESS).count());
        assertEquals(1, results.stream().filter(result -> result.status() == INSUFFICIENT_FUNDS).count());
        assertEquals(25, repository.balance(playerId).coins());
        assertEquals("2", scalar("SELECT COUNT(*) FROM currency_transactions"));
        assertEquals("25", scalar("SELECT SUM(amount_delta) FROM currency_transactions"));
    }

    @Test
    void concurrentDistinctCreditsAndDebitsAgreeWithLedger() throws Exception {
        repository.apply(operation(UUID.randomUUID(), 1000, CREDIT));
        List<Callable<CurrencyOperationResult>> actions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            actions.add(() -> repository.apply(operation(UUID.randomUUID(), 20, CREDIT)));
            actions.add(() -> repository.apply(operation(UUID.randomUUID(), 10, DEBIT)));
        }

        assertTrue(concurrently(actions).stream().allMatch(result -> result.status() == SUCCESS));

        assertEquals(1100, repository.balance(playerId).coins());
        assertEquals("1100", scalar("SELECT SUM(amount_delta) FROM currency_transactions"));
        assertEquals("21", scalar("SELECT COUNT(*) FROM currency_transactions"));
    }

    @Test
    void concurrentCreditsAtCapAllowOnlyOne() throws Exception {
        repository.apply(operation(UUID.randomUUID(), BalanceSnapshot.MAX_BALANCE - 1, CREDIT));
        List<EconomyException.Code> outcomes = concurrently(List.of(
                () -> tryCredit(operation(UUID.randomUUID(), 1, CREDIT)),
                () -> tryCredit(operation(UUID.randomUUID(), 1, CREDIT))));

        assertEquals(1, outcomes.stream().filter(code -> code == EconomyException.Code.BALANCE_LIMIT_EXCEEDED).count());
        assertEquals(BalanceSnapshot.MAX_BALANCE, repository.balance(playerId).coins());
        assertEquals("2", scalar("SELECT COUNT(*) FROM currency_transactions"));
    }

    @Test
    void differentPlayersUsingSameOperationIdDoNotBothGetCredited() throws Exception {
        UUID other = UUID.randomUUID();
        insertPlayer(other);
        UUID operation = UUID.randomUUID();
        execute("""
                CREATE FUNCTION slow_coin_update() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM pg_sleep(0.3); RETURN NEW; END $$
                """);
        execute("""
                CREATE TRIGGER slow_coin BEFORE UPDATE ON player_profiles
                FOR EACH ROW EXECUTE FUNCTION slow_coin_update()
                """);
        try {
            List<EconomyException.Code> outcomes = concurrently(List.of(
                    () -> tryCredit(operation(operation, 100, CREDIT)),
                    () -> tryCredit(new CurrencyOperation(operation, other, 100, CREDIT, "test", "reward", null))));

            assertEquals(1, outcomes.stream().filter(code -> code == EconomyException.Code.OPERATION_CONFLICT).count());
            assertEquals("100", scalar("SELECT SUM(coins) FROM player_profiles"));
            assertEquals("1", scalar("SELECT COUNT(*) FROM currency_transactions"));
        } finally {
            execute("DROP TRIGGER slow_coin ON player_profiles");
            execute("DROP FUNCTION slow_coin_update()");
        }
    }

    @Test
    void anotherPlayersRowLockDoesNotBlockIndependentCredit() throws Exception {
        UUID other = UUID.randomUUID();
        insertPlayer(other);
        var pool = Executors.newSingleThreadExecutor();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT uuid FROM player_profiles WHERE uuid = ? FOR UPDATE")) {
            connection.setAutoCommit(false);
            statement.setObject(1, playerId);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next());
                var result = pool.submit(() -> repository.apply(
                        new CurrencyOperation(UUID.randomUUID(), other, 100, CREDIT, "test", "reward", null)));
                assertEquals(100, result.get(5, TimeUnit.SECONDS).balance());
            } finally {
                connection.rollback();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @EnumSource(CurrencyOperationType.class)
    void ledgerInsertFailureRollsBackProfileAndAllowsSafeRetry(CurrencyOperationType type) throws SQLException {
        repository.apply(operation(UUID.randomUUID(), 100, CREDIT));
        String before = scalar("SELECT row_to_json(p)::text FROM player_profiles p");
        execute("""
                CREATE FUNCTION fail_coin_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'simulated coin ledger failure'; END $$
                """);
        execute("""
                CREATE TRIGGER fail_coin BEFORE INSERT ON currency_transactions
                FOR EACH ROW EXECUTE FUNCTION fail_coin_insert()
                """);
        CurrencyOperation request = operation(UUID.randomUUID(), 25, type);
        try {
            var error = assertThrows(ProfileStorageException.class, () -> repository.apply(request));
            assertInstanceOf(SQLException.class, error.getCause());
            assertEquals(before, scalar("SELECT row_to_json(p)::text FROM player_profiles p"));
            assertEquals("1", scalar("SELECT COUNT(*) FROM currency_transactions"));
        } finally {
            execute("DROP TRIGGER fail_coin ON currency_transactions");
            execute("DROP FUNCTION fail_coin_insert()");
        }
        assertFalse(repository.apply(request).duplicateReplay());
        assertEquals(type == CREDIT ? 125 : 75, repository.balance(playerId).coins());
    }

    @Test
    void coinAndXpAwardsDoNotOverwriteEachOther() throws Exception {
        var xp = new JdbcProgressionRepository(dataSource);
        concurrently(List.of(
                () -> {
                    repository.apply(operation(UUID.randomUUID(), 100, CREDIT));
                    return true;
                },
                () -> {
                    xp.awardXp(new XpAwardRequest(UUID.randomUUID(), playerId, 300, "test", "xp reward", null));
                    return true;
                }));

        assertEquals(100, repository.balance(playerId).coins());
        assertEquals(300, xp.getProgress(playerId).experience());
        assertEquals("3", scalar("SELECT level FROM player_profiles"));
    }

    @Test
    void missingProfileIsAnExplicitDomainFailure() {
        UUID missing = UUID.randomUUID();
        assertEquals(EconomyException.Code.PROFILE_NOT_FOUND,
                assertThrows(EconomyException.class, () -> repository.balance(missing)).code());
        assertEquals(EconomyException.Code.PROFILE_NOT_FOUND,
                assertThrows(EconomyException.class, () -> repository.apply(
                        new CurrencyOperation(UUID.randomUUID(), missing, 1, CREDIT, "test", "reward", null))).code());
    }

    @Test
    void unavailableDatabaseDoesNotReturnFictitiousSuccess() throws IOException {
        PGSimpleDataSource unreachable = new PGSimpleDataSource();
        unreachable.setServerNames(new String[]{"127.0.0.1"});
        try (ServerSocket socket = new ServerSocket(0)) {
            unreachable.setPortNumbers(new int[]{socket.getLocalPort()});
        }
        unreachable.setDatabaseName("postgres");
        unreachable.setUser("postgres");
        unreachable.setConnectTimeout(1);
        var broken = new JdbcEconomyRepository(unreachable);

        assertThrows(ProfileStorageException.class, () -> broken.balance(playerId));
        assertThrows(ProfileStorageException.class, () -> broken.apply(operation(UUID.randomUUID(), 1, CREDIT)));
        assertThrows(ProfileStorageException.class, () -> broken.apply(operation(UUID.randomUUID(), 1, DEBIT)));
    }

    @Test
    void serviceTimeoutThenRetryDoesNotChargeTwice() throws Exception {
        repository.apply(operation(UUID.randomUUID(), 100, CREDIT));
        CountDownLatch committed = new CountDownLatch(1);
        CompletableFuture<Void> releaseResponse = new CompletableFuture<>();
        EconomyRepository delayedResponse = new EconomyRepository() {
            @Override
            public BalanceSnapshot balance(UUID id) {
                return repository.balance(id);
            }

            @Override
            public CurrencyOperationResult apply(CurrencyOperation request) {
                CurrencyOperationResult result = repository.apply(request);
                if (!result.duplicateReplay()) {
                    committed.countDown();
                    releaseResponse.join();
                }
                return result;
            }
        };
        var service = DefaultEconomyService.create(delayedResponse, 2, 10);
        UUID operation = UUID.randomUUID();
        try {
            var caller = service.debit(operation, playerId, 75, "test", "purchase", null);
            assertTrue(committed.await(5, TimeUnit.SECONDS));
            caller.orTimeout(1, TimeUnit.MILLISECONDS);
            assertInstanceOf(TimeoutException.class,
                    assertThrows(ExecutionException.class, () -> caller.get(5, TimeUnit.SECONDS)).getCause());
            var replay = service.debit(operation, playerId, 75, "test", "purchase", null).get(5, TimeUnit.SECONDS);
            assertTrue(replay.duplicateReplay());
            assertEquals(25, service.balance(playerId).get(5, TimeUnit.SECONDS).coins());
            assertEquals("2", scalar("SELECT COUNT(*) FROM currency_transactions"));
        } finally {
            releaseResponse.complete(null);
            service.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void managedServicesRefreshOnlineProfilesAfterCommittedCoinsAndXp() throws Exception {
        var profiles = DefaultProfileService.create(new JdbcProfileRepository(dataSource), 2, 10);
        var sessions = new ProfileSessionManager();
        long token = sessions.begin(playerId).token();
        sessions.markReady(playerId, token, profiles.findById(playerId).get(5, TimeUnit.SECONDS).orElseThrow());
        var refreshes = new ProfileRefreshService(profiles, sessions, Duration.ofSeconds(5), LoggerFactory.getLogger(getClass()));
        AtomicReference<CompletableFuture<Void>> refresh = new AtomicReference<>();
        AtomicInteger refreshCount = new AtomicInteger();
        java.util.function.Consumer<UUID> observer = uuid -> {
            refreshCount.incrementAndGet();
            refresh.set(refreshes.refresh(uuid));
        };
        var coins = DefaultEconomyService.create(repository, 2, 10, observer);
        var xp = DefaultProgressionService.create(new JdbcProgressionRepository(dataSource), 2, 10, observer);
        UUID coinOperation = UUID.randomUUID();
        try {
            coins.credit(coinOperation, playerId, 100, "test", "grant", null).get(5, TimeUnit.SECONDS);
            refresh.get().get(5, TimeUnit.SECONDS);
            assertEquals(100, sessions.get(playerId).orElseThrow().profile().coins());

            xp.awardXp(UUID.randomUUID(), playerId, 100, "test", "grant", null).get(5, TimeUnit.SECONDS);
            refresh.get().get(5, TimeUnit.SECONDS);
            var profile = sessions.get(playerId).orElseThrow().profile();
            assertEquals(100, profile.coins());
            assertEquals(100, profile.experience());
            assertEquals(2, profile.level());
            assertEquals(token, sessions.get(playerId).orElseThrow().token());

            coins.debit(UUID.randomUUID(), playerId, 75, "test", "purchase", null).get(5, TimeUnit.SECONDS);
            refresh.get().get(5, TimeUnit.SECONDS);
            assertEquals(25, sessions.get(playerId).orElseThrow().profile().coins());
            assertTrue(coins.credit(coinOperation, playerId, 100, "test", "grant", null)
                    .get(5, TimeUnit.SECONDS).duplicateReplay());
            refresh.get().get(5, TimeUnit.SECONDS);
            assertEquals(25, sessions.get(playerId).orElseThrow().profile().coins());

            assertEquals(INSUFFICIENT_FUNDS, coins.debit(UUID.randomUUID(), playerId, 100, "test", "purchase", null)
                    .get(5, TimeUnit.SECONDS).status());
            assertEquals(4, refreshCount.get());
            assertEquals(SessionState.READY, sessions.get(playerId).orElseThrow().state());
        } finally {
            refreshes.close();
            sessions.closeAll();
            coins.shutdown(Duration.ofSeconds(5));
            xp.shutdown(Duration.ofSeconds(5));
            profiles.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void balanceAndReplaySurviveDatabaseRestart(@TempDir Path directory) throws Exception {
        CurrencyOperation credit = operation(UUID.randomUUID(), 100, CREDIT);
        CurrencyOperation debit = operation(UUID.randomUUID(), 75, DEBIT);
        try (EmbeddedPostgres first = EmbeddedPostgres.builder()
                .setDataDirectory(directory).setCleanDataDirectory(false).start()) {
            Migrations.apply(first.getPostgresDatabase(), getClass().getClassLoader());
            try (Connection connection = first.getPostgresDatabase().getConnection();
                 PreparedStatement statement = connection.prepareStatement("INSERT INTO player_profiles(uuid, username) VALUES (?, 'Player')")) {
                statement.setObject(1, playerId);
                statement.executeUpdate();
            }
            var initial = new JdbcEconomyRepository(first.getPostgresDatabase());
            initial.apply(credit);
            initial.apply(debit);
        }
        try (EmbeddedPostgres second = EmbeddedPostgres.builder()
                .setDataDirectory(directory).setCleanDataDirectory(false).start()) {
            var restored = new JdbcEconomyRepository(second.getPostgresDatabase());
            assertEquals(25, restored.balance(playerId).coins());
            assertEquals(100, restored.apply(credit).balance());
            assertTrue(restored.apply(debit).duplicateReplay());
            assertEquals(25, restored.balance(playerId).coins());
        }
    }

    private EconomyException.Code tryCredit(CurrencyOperation operation) {
        try {
            repository.apply(operation);
            return null;
        } catch (EconomyException e) {
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

    private CurrencyOperation operation(UUID id, long amount, CurrencyOperationType type) {
        return new CurrencyOperation(id, playerId, amount, type, "test", "reward", playerId);
    }

    private static void insertPlayer(UUID id) throws SQLException {
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
