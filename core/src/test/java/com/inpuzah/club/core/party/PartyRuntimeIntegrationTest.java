package com.inpuzah.club.core.party;

import com.inpuzah.club.core.party.model.PartyOutcome;
import com.inpuzah.club.core.party.repository.PartyEpochLease;
import com.inpuzah.club.core.party.repository.PartyException;
import com.inpuzah.club.core.persistence.Migrations;
import com.inpuzah.club.core.repository.JdbcProfileRepository;
import com.inpuzah.club.core.repository.ProfileStorageException;
import com.inpuzah.club.core.social.SocialConfig;
import com.inpuzah.club.core.social.repository.JdbcSocialRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PartyRuntimeIntegrationTest {
    private EmbeddedPostgres postgres;
    private PGSimpleDataSource source;
    private PartyRuntime runtime;
    private final UUID leader = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @BeforeEach
    void setup() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        source = new PGSimpleDataSource();
        source.setServerNames(new String[]{"127.0.0.1"});
        source.setPortNumbers(new int[]{postgres.getPort()});
        source.setDatabaseName("postgres");
        source.setUser("postgres");
        source.setConnectTimeout(2);
        source.setSocketTimeout(3);
        Migrations.apply(source, getClass().getClassLoader());
        var profiles = new JdbcProfileRepository(source);
        profiles.upsertOnJoin(leader, "Leader");
        profiles.upsertOnJoin(target, "Target");
    }

    @AfterEach
    void cleanup() throws Exception {
        try {
            if (runtime != null) {
                try {
                    runtime.stop().get(15, TimeUnit.SECONDS);
                } catch (ExecutionException error) {
                    assertInstanceOf(ProfileStorageException.class, error.getCause());
                }
            }
        } finally {
            if (postgres != null) {
                postgres.close();
            }
        }
    }

    @Test
    void fifoPartyWorkerFinishesOlderAcceptedWritesBeforeLaterDisconnectCleanup() throws Exception {
        runtime = runtime(source, source);
        runtime.started().get(10, TimeUnit.SECONDS);
        var service = runtime.service();
        var create = service.create(leader);
        var cleanup = service.leaveDisconnected(leader, java.util.Set.of(), new PartyDeparture());
        assertEquals(PartyOutcome.Code.CREATED, create.get(5, TimeUnit.SECONDS).code());
        assertEquals(PartyOutcome.Code.LEFT, cleanup.get(5, TimeUnit.SECONDS).code());
        assertTrue(service.getParty(leader).get(5, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void startsOffThreadAndGracefulStopClosesMembersInvitesAndEpochBeforeReleasingLease() throws Exception {
        var acquisitionThread = new AtomicReference<Thread>();
        runtime = runtime(wrap(() -> acquisitionThread.set(Thread.currentThread())), source);
        runtime.started().get(10, TimeUnit.SECONDS);
        assertNotEquals(Thread.currentThread(), acquisitionThread.get());
        assertEquals(PartyRuntime.State.READY, runtime.state());
        var retained = runtime.service();
        assertEquals(PartyOutcome.Code.INVITED, retained.invite(leader, target).get(5, TimeUnit.SECONDS).code());
        runtime.checkHealth().get(5, TimeUnit.SECONDS);
        var stoppedCopy = runtime.stop();
        assertEquals(PartyException.Code.SERVICE_UNAVAILABLE,
                assertInstanceOf(PartyException.class, failure(retained.create(target))).code());
        stoppedCopy.get(10, TimeUnit.SECONDS);
        assertEquals(PartyRuntime.State.STOPPED, runtime.state());
        runtime.stop().get(5, TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class, runtime::service);
        assertEquals(0, count("SELECT count(*) FROM party_instances WHERE ended_at IS NULL"));
        assertEquals(0, count("SELECT count(*) FROM parties WHERE closed_at IS NULL"));
        assertEquals(0, count("SELECT count(*) FROM party_members WHERE left_at IS NULL"));
        assertEquals(0, count("SELECT count(*) FROM party_invites WHERE resolved_at IS NULL"));
        try (var next = PartyEpochLease.acquire(source, "lobby")) {
            next.verify();
        }
    }

    @Test
    void competingOwnerFailsOnlyPartySubsystemAndDoesNotStealLease() throws Exception {
        try (var existing = PartyEpochLease.acquire(source, "lobby")) {
            runtime = runtime(source, source);
            assertInstanceOf(IllegalStateException.class, failure(runtime.started()));
            assertEquals(PartyRuntime.State.FAILED, runtime.state());
            assertThrows(IllegalStateException.class, runtime::service);
            existing.verify();
            assertTrue(new JdbcSocialRepository(source, SocialConfig.DEFAULT).preferences(target).partyInvitesEnabled());
            assertEquals(1, count("SELECT count(*) FROM party_instances"));
        }
    }

    @Test
    void leaseLossIsDetectedByWorkerAndRetainedServiceCannotResumeUntilRestart() throws Exception {
        runtime = runtime(source, source);
        runtime.started().get(10, TimeUnit.SECONDS);
        var retained = runtime.service();
        retained.create(leader).get(5, TimeUnit.SECONDS);
        terminateLease();
        assertInstanceOf(ProfileStorageException.class, failure(retained.getParty(leader)));
        assertEquals(PartyRuntime.State.FAILED, runtime.state());
        assertEquals(PartyException.Code.SERVICE_UNAVAILABLE,
                assertInstanceOf(PartyException.class, failure(retained.invite(leader, target))).code());
        assertThrows(IllegalStateException.class, runtime::service);
        assertTrue(new JdbcSocialRepository(source, SocialConfig.DEFAULT).preferences(target).friendRequestsEnabled());
        assertInstanceOf(ProfileStorageException.class, failure(runtime.stop()));
        try (var next = PartyEpochLease.acquire(source, "lobby")) {
            assertEquals(0, count("SELECT count(*) FROM parties WHERE closed_at IS NULL"));
            next.verify();
        }
    }

    @Test
    void healthProbeDetectsIdleLeaseLossWithoutStartingANewEpoch() throws Exception {
        runtime = runtime(source, source);
        runtime.started().get(10, TimeUnit.SECONDS);
        terminateLease();
        assertInstanceOf(ProfileStorageException.class, failure(runtime.checkHealth()));
        assertEquals(PartyRuntime.State.FAILED, runtime.state());
        assertEquals(1, count("SELECT count(*) FROM party_instances"));
    }

    @Test
    void stopDuringBlockedStartupNeverPublishesServiceAndCleansAcquiredEpoch() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        runtime = runtime(wrap(() -> {
            if (first.getAndSet(false)) {
                entered.countDown();
                await(release);
            }
        }), source);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, runtime::service);
            var stopped = runtime.stop();
            assertFalse(stopped.isDone());
            assertEquals(PartyRuntime.State.STOPPING, runtime.state());
            assertInstanceOf(IllegalStateException.class, failure(runtime.started()));
            release.countDown();
            stopped.get(10, TimeUnit.SECONDS);
            assertEquals(PartyRuntime.State.STOPPED, runtime.state());
            assertEquals(0, count("SELECT count(*) FROM party_instances WHERE ended_at IS NULL"));
            try (var next = PartyEpochLease.acquire(source, "lobby")) {
                next.verify();
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    void stopRetainsLeaseWhileAcceptedDatabaseWorkIsStillRunning() throws Exception {
        var blocking = new AtomicBoolean(false);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        runtime = runtime(wrap(() -> {
            if (blocking.get()) {
                entered.countDown();
                await(release);
            }
        }), source);
        runtime.started().get(10, TimeUnit.SECONDS);
        var service = runtime.service();
        blocking.set(true);
        var running = service.create(leader);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var stopping = runtime.stop();
            assertFalse(stopping.isDone());
            assertThrows(IllegalStateException.class, () -> PartyEpochLease.acquire(source, "lobby"));
            blocking.set(false);
            release.countDown();
            assertEquals(PartyOutcome.Code.CREATED, running.get(5, TimeUnit.SECONDS).code());
            stopping.get(10, TimeUnit.SECONDS);
            assertEquals(0, count("SELECT count(*) FROM parties WHERE closed_at IS NULL"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void shutdownDeadlineDoesNotReleaseLeaseUnderAnUncooperativeWorker() throws Exception {
        var blocking = new AtomicBoolean(false);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        runtime = runtime(wrap(() -> {
            if (blocking.get()) {
                entered.countDown();
                boolean waiting = true;
                while (waiting) {
                    try {
                        assertTrue(release.await(20, TimeUnit.SECONDS));
                        waiting = false;
                    } catch (InterruptedException ignored) {
                        // Deliberately model JDBC work that does not honor executor interruption.
                    }
                }
            }
        }), source);
        runtime.started().get(10, TimeUnit.SECONDS);
        var service = runtime.service();
        blocking.set(true);
        var running = service.create(leader);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var stopping = runtime.stop();
            var error = assertThrows(ExecutionException.class, () -> running.get(15, TimeUnit.SECONDS)).getCause();
            assertEquals(PartyException.Code.SERVICE_UNAVAILABLE, assertInstanceOf(PartyException.class, error).code());
            assertTrue(error.getMessage().contains("may still have succeeded"));
            assertFalse(stopping.isDone(), "Completing abandoned futures must not release ownership");
            assertThrows(IllegalStateException.class, () -> PartyEpochLease.acquire(source, "lobby"));
            blocking.set(false);
            release.countDown();
            stopping.get(10, TimeUnit.SECONDS);
            assertEquals(0, count("SELECT count(*) FROM party_instances WHERE ended_at IS NULL"));
            try (var next = PartyEpochLease.acquire(source, "lobby")) {
                next.verify();
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    void mismatchedSchemaFailsBeforeReconcilingOrCreatingDirectEpoch() throws Exception {
        try (var connection = source.getConnection(); var query = connection.createStatement()) {
            query.execute("CREATE SCHEMA other");
        }
        var direct = new PGSimpleDataSource();
        direct.setServerNames(source.getServerNames());
        direct.setPortNumbers(source.getPortNumbers());
        direct.setDatabaseName("postgres");
        direct.setUser("postgres");
        direct.setCurrentSchema("other");
        runtime = runtime(source, direct);
        assertInstanceOf(IllegalStateException.class, failure(runtime.started()));
        assertEquals(PartyRuntime.State.FAILED, runtime.state());
        assertEquals(0, count("SELECT count(*) FROM party_instances"));
    }

    @Test
    void automaticHeartbeatStopsIdleRuntimeAfterLeaseLoss() throws Exception {
        var reported = new CountDownLatch(1);
        var delegate = LoggerFactory.getLogger(getClass());
        var logger = (org.slf4j.Logger) Proxy.newProxyInstance(org.slf4j.Logger.class.getClassLoader(),
                new Class<?>[]{org.slf4j.Logger.class}, (proxy, method, args) -> {
                    if (method.getName().equals("error") && args[0] instanceof String message
                            && message.startsWith("Party runtime failed")) {
                        reported.countDown();
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
        runtime = new PartyRuntime(source, source, "lobby", PartyPolicy.DEFAULT, 1, 10, logger);
        runtime.started().get(10, TimeUnit.SECONDS);
        terminateLease();
        assertTrue(reported.await(12, TimeUnit.SECONDS), "Shared heartbeat must detect idle lease loss");
        assertEquals(PartyRuntime.State.FAILED, runtime.state());
        assertThrows(IllegalStateException.class, runtime::service);
        assertEquals(1, count("SELECT count(*) FROM party_instances"));
    }

    @Test
    void wrongServerWithSameDatabaseAndSchemaCannotReconcileAnotherPredecessor() throws Exception {
        try (var other = EmbeddedPostgres.builder().start()) {
            var direct = new PGSimpleDataSource();
            direct.setServerNames(new String[]{"127.0.0.1"});
            direct.setPortNumbers(new int[]{other.getPort()});
            direct.setDatabaseName("postgres");
            direct.setUser("postgres");
            Migrations.apply(direct, getClass().getClassLoader());
            try (var previous = PartyEpochLease.acquire(direct, "lobby")) {
                previous.verify();
            }
            runtime = runtime(source, direct);
            var failure = assertInstanceOf(IllegalStateException.class, failure(runtime.started()));
            assertTrue(failure.getMessage().contains("predecessor"));
            assertEquals(0, count("SELECT count(*) FROM party_instances"));
            try (var connection = direct.getConnection(); var query = connection.createStatement();
                 var rows = query.executeQuery("SELECT count(*) FROM party_instances")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    @Test
    void callerCancellationDoesNotCancelStartupOrCleanup() throws Exception {
        runtime = runtime(source, source);
        var copy = runtime.started();
        copy.cancel(false);
        runtime.started().get(10, TimeUnit.SECONDS);
        assertEquals(PartyRuntime.State.READY, runtime.state());
        runtime.stop().cancel(false);
        runtime.stop().get(10, TimeUnit.SECONDS);
        assertEquals(PartyRuntime.State.STOPPED, runtime.state());
    }

    private PartyRuntime runtime(DataSource application, PGSimpleDataSource direct) {
        return new PartyRuntime(application, direct, "lobby", PartyPolicy.DEFAULT, 1, 10, LoggerFactory.getLogger(getClass()));
    }

    private DataSource wrap(Runnable beforeConnection) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        beforeConnection.run();
                    }
                    try {
                        return method.invoke(source, args);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private int count(String sql) throws SQLException {
        try (var connection = source.getConnection(); var query = connection.createStatement(); var rows = query.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private void terminateLease() throws SQLException {
        try (var connection = source.getConnection(); var query = connection.createStatement();
             var rows = query.executeQuery("""
                     WITH lease_key AS (
                         SELECT hashtextextended('ClubCore:parties:' || current_database() || ':' || current_schema() || ':lobby',0) AS key
                     )
                     SELECT pg_terminate_backend(pid) FROM pg_locks,lease_key
                     WHERE locktype='advisory' AND granted AND objsubid=1
                         AND classid=((key >> 32) & 4294967295)::oid AND objid=(key & 4294967295)::oid
                     """)) {
            assertTrue(rows.next());
            assertTrue(rows.getBoolean(1));
            assertFalse(rows.next());
        }
    }

    private static Throwable failure(CompletableFuture<?> future) {
        return assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS)).getCause();
    }
}
