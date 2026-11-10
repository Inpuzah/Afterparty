package com.inpuzah.club.core.party;

import com.inpuzah.club.core.party.model.PartyOutcome;
import com.inpuzah.club.core.party.repository.PartyException;
import com.inpuzah.club.core.party.repository.PartyRepository;
import com.inpuzah.club.core.party.service.DefaultPartyService;
import com.inpuzah.club.core.repository.ProfileStorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PartyServiceTest {
    private final UUID actor = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> operations = new ArrayList<>();
    private volatile Thread worker;
    private volatile RuntimeException failure;
    private volatile boolean ignoreInterrupts;
    private volatile Set<?> capturedOnline;
    private final PartyRepository repository = (PartyRepository) Proxy.newProxyInstance(
            PartyRepository.class.getClassLoader(), new Class<?>[]{PartyRepository.class}, (proxy, method, args) -> {
                calls.incrementAndGet();
                worker = Thread.currentThread();
                entered.countDown();
                boolean interrupted = false;
                while (true) {
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        break;
                    } catch (InterruptedException error) {
                        if (!ignoreInterrupts) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Test worker interrupted", error);
                        }
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                operations.add(method.getName());
                if (failure != null) {
                    throw failure;
                }
                if (method.getName().startsWith("leave")) {
                    capturedOnline = (Set<?>) args[1];
                }
                return switch (method.getName()) {
                    case "getParty" -> Optional.empty();
                    case "getInvites" -> List.of();
                    default -> PartyOutcome.of(PartyOutcome.Code.BLOCKED_OR_RESTRICTED);
                };
            });
    private final DefaultPartyService service = DefaultPartyService.create(repository, 1, 1, LoggerFactory.getLogger(getClass()));

    @AfterEach
    void stop() throws Exception {
        release.countDown();
        service.shutdown(Duration.ofSeconds(5));
    }

    @Test
    void delegatesAllOperationsOffThreadWithoutInventingSuccess() throws Exception {
        release.countDown();
        assertEquals(PartyOutcome.Code.BLOCKED_OR_RESTRICTED, service.create(actor).get(5, TimeUnit.SECONDS).code());
        service.invite(actor, target).get(5, TimeUnit.SECONDS);
        service.accept(actor, target).get(5, TimeUnit.SECONDS);
        service.deny(actor, target).get(5, TimeUnit.SECONDS);
        service.leave(actor, Set.of(target)).get(5, TimeUnit.SECONDS);
        service.kick(actor, target).get(5, TimeUnit.SECONDS);
        service.promote(actor, target).get(5, TimeUnit.SECONDS);
        service.disband(actor).get(5, TimeUnit.SECONDS);
        service.leaveIfCurrent(actor, Set.of(target), target, 4).get(5, TimeUnit.SECONDS);
        service.disbandIfCurrent(actor, target, 4).get(5, TimeUnit.SECONDS);
        service.leaveDisconnected(actor, Set.of(target), new PartyDeparture()).get(5, TimeUnit.SECONDS);
        assertTrue(service.getParty(actor).get(5, TimeUnit.SECONDS).isEmpty());
        assertTrue(service.getInvites(actor, 10, 10).get(5, TimeUnit.SECONDS).isEmpty());
        service.withParty(actor, party -> fail("Fake repository must not invoke a delivery")).get(5, TimeUnit.SECONDS);
        assertEquals(List.of("create", "invite", "accept", "deny", "leave", "kick", "promote", "disband",
                "leaveIfCurrent", "disbandIfCurrent", "leaveDisconnected", "getParty", "getInvites", "withParty"), operations);
        assertNotEquals(Thread.currentThread(), worker);
        assertEquals(Set.of(target), capturedOnline);
    }

    @Test
    void invalidInputReturnsExceptionalFutureBeforeRepository() {
        release.countDown();
        assertInstanceOf(NullPointerException.class, failure(service.create(null)));
        assertInstanceOf(NullPointerException.class, failure(service.invite(actor, null)));
        assertInstanceOf(NullPointerException.class, failure(service.invite(null, target)));
        assertInstanceOf(NullPointerException.class, failure(service.accept(null, target)));
        assertInstanceOf(NullPointerException.class, failure(service.accept(actor, null)));
        assertInstanceOf(NullPointerException.class, failure(service.deny(actor, null)));
        assertInstanceOf(NullPointerException.class, failure(service.kick(null, target)));
        assertInstanceOf(NullPointerException.class, failure(service.promote(actor, null)));
        assertInstanceOf(NullPointerException.class, failure(service.disband(null)));
        assertInstanceOf(NullPointerException.class, failure(service.getParty(null)));
        assertInstanceOf(NullPointerException.class, failure(service.getInvites(null, 0, 10)));
        assertInstanceOf(NullPointerException.class, failure(service.withParty(null, party -> {})));
        assertInstanceOf(NullPointerException.class, failure(service.withParty(actor, null)));
        assertInstanceOf(NullPointerException.class, failure(service.leave(null, Set.of())));
        assertInstanceOf(NullPointerException.class, failure(service.leave(actor, null)));
        assertInstanceOf(NullPointerException.class, failure(service.leaveIfCurrent(actor, Set.of(), null, 0)));
        assertInstanceOf(NullPointerException.class, failure(service.leaveIfCurrent(null, Set.of(), target, 0)));
        assertInstanceOf(NullPointerException.class, failure(service.leaveIfCurrent(actor, null, target, 0)));
        assertInstanceOf(NullPointerException.class, failure(service.disbandIfCurrent(actor, null, 0)));
        assertInstanceOf(NullPointerException.class, failure(service.disbandIfCurrent(null, target, 0)));
        assertInstanceOf(IllegalArgumentException.class, failure(service.leaveIfCurrent(actor, Set.of(), target, -1)));
        assertInstanceOf(IllegalArgumentException.class, failure(service.disbandIfCurrent(actor, target, -1)));
        assertInstanceOf(NullPointerException.class, failure(service.leaveDisconnected(null, Set.of(), new PartyDeparture())));
        assertInstanceOf(NullPointerException.class, failure(service.leaveDisconnected(actor, null, new PartyDeparture())));
        assertInstanceOf(NullPointerException.class, failure(service.leaveDisconnected(actor, Set.of(), null)));
        assertInstanceOf(IllegalArgumentException.class, failure(service.getInvites(actor, -1, 10)));
        assertInstanceOf(IllegalArgumentException.class, failure(service.getInvites(actor, 0, 101)));
        assertInstanceOf(IllegalArgumentException.class, failure(service.getInvites(actor, 0, 0)));
        assertEquals(0, calls.get());
    }

    @Test
    void snapshotsOnlineMembersBeforeWorkIsQueued() throws Exception {
        var blocking = service.create(actor);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var mutable = new HashSet<>(Set.of(target));
        var leaving = service.leave(actor, mutable);
        mutable.clear();
        release.countDown();
        blocking.get(5, TimeUnit.SECONDS);
        leaving.get(5, TimeUnit.SECONDS);
        assertEquals(Set.of(target), capturedOnline);
        assertThrows(UnsupportedOperationException.class, () -> capturedOnline.clear());
    }

    @Test
    void storageDomainAndLostLeaseFailuresStayExceptional() {
        release.countDown();
        failure = new ProfileStorageException("simulated failure", null);
        assertSame(failure, failure(service.invite(actor, target)));
        assertSame(failure, failure(service.getParty(actor)));
        failure = new PartyException(PartyException.Code.STALE_EPOCH, "stale");
        assertSame(failure, failure(service.create(actor)));
        failure = new PartyException(PartyException.Code.PLAYER_NOT_FOUND, "missing");
        assertSame(failure, failure(service.getInvites(actor, 0, 10)));
        failure = new IllegalStateException("lease closed");
        assertSame(failure, failure(service.create(actor)));
    }

    @Test
    void boundedQueueAndDrainRejectNewWork() throws Exception {
        var running = service.create(actor);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var queued = service.create(target);
        unavailable(failure(service.create(actor)));
        release.countDown();
        assertEquals(0, service.shutdown(Duration.ofSeconds(5)));
        running.get(5, TimeUnit.SECONDS);
        queued.get(5, TimeUnit.SECONDS);
        assertEquals(2, calls.get());
        unavailable(failure(service.create(actor)));
    }

    @Test
    void callerTimeoutDoesNotCancelAcceptedOperation() throws Exception {
        var running = service.invite(actor, target);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, failure(running.orTimeout(1, TimeUnit.MILLISECONDS)));
        release.countDown();
        assertEquals(0, service.shutdown(Duration.ofSeconds(5)));
        assertEquals(List.of("invite"), operations);
    }

    @Test
    void callerCancellationDoesNotCancelAcceptedOperation() throws Exception {
        var running = service.invite(actor, target);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(running.cancel(true));
        release.countDown();
        assertEquals(0, service.shutdown(Duration.ofSeconds(5)));
        assertEquals(List.of("invite"), operations);
    }

    @Test
    void deadlineCompletesUnresolvedRunningAndQueuedFuturesWithoutClaimingRollback() throws Exception {
        ignoreInterrupts = true;
        var running = service.create(actor);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var queued = service.create(actor);
        assertEquals(2, service.shutdown(Duration.ZERO));
        var error = failure(running);
        unavailable(error);
        assertTrue(error.getMessage().contains("may still have succeeded"));
        unavailable(failure(queued));
        unavailable(failure(service.getParty(actor)));
    }

    @Test
    void interruptedShutdownCompletesPendingAndRejectsFutureWork() throws Exception {
        ignoreInterrupts = true;
        var running = service.create(actor);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> service.shutdown(Duration.ofSeconds(5)));
        } finally {
            Thread.interrupted();
        }
        unavailable(failure(running));
        unavailable(failure(service.create(actor)));
    }

    @Test
    void invalidationRejectsNewAndAlreadyQueuedWorkWithoutCallingRepository() throws Exception {
        var running = service.create(actor);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var queued = service.create(target);
        service.invalidate();
        unavailable(failure(service.invite(actor, target)));
        release.countDown();
        running.get(5, TimeUnit.SECONDS);
        unavailable(failure(queued));
        assertEquals(1, calls.get());
    }

    @Test
    void failedRuntimePreflightNeverReachesRepository() throws Exception {
        var cause = new IllegalStateException("lost lease");
        var guarded = new DefaultPartyService(repository,
                com.inpuzah.club.core.service.BoundedServiceExecutor.workers("Party-Test-Guard", 1, 1),
                LoggerFactory.getLogger(getClass()), () -> { throw cause; });
        try {
            assertSame(cause, failure(guarded.create(actor)));
            assertEquals(0, calls.get());
        } finally {
            guarded.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void policyDefaultsAndBoundsAreValidated() {
        assertEquals(8, PartyPolicy.DEFAULT.maxMembers());
        assertEquals(Duration.ofSeconds(120), PartyPolicy.DEFAULT.inviteTtl());
        assertEquals(5, PartyPolicy.DEFAULT.invitesPerMinute());
        assertEquals(10, PartyPolicy.DEFAULT.maxPendingInvites());
        assertEquals(Duration.ofSeconds(30), PartyPolicy.DEFAULT.sameTargetCooldown());
        assertThrows(IllegalArgumentException.class, () -> new PartyPolicy(1, Duration.ofSeconds(120), 5, 10, Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class, () -> new PartyPolicy(33, Duration.ofSeconds(120), 5, 10, Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class, () -> new PartyPolicy(8, Duration.ZERO, 5, 10, Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class, () -> new PartyPolicy(8, Duration.ofHours(2), 5, 10, Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class, () -> new PartyPolicy(8, Duration.ofSeconds(120), 0, 10, Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class, () -> new PartyPolicy(8, Duration.ofSeconds(120), 101, 10, Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class, () -> new PartyPolicy(8, Duration.ofSeconds(120), 5, 0, Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class, () -> new PartyPolicy(8, Duration.ofSeconds(120), 5, 101, Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class, () -> new PartyPolicy(8, Duration.ofSeconds(120), 5, 10, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new PartyPolicy(8, Duration.ofSeconds(120), 5, 10, Duration.ofDays(2)));
        assertThrows(NullPointerException.class, () -> new PartyPolicy(8, null, 5, 10, Duration.ofSeconds(30)));
    }

    private static Throwable failure(CompletableFuture<?> future) {
        return assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)).getCause();
    }
    private static void unavailable(Throwable error) {
        assertEquals(PartyException.Code.SERVICE_UNAVAILABLE, assertInstanceOf(PartyException.class, error).code());
    }
}
