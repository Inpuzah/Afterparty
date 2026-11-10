package com.inpuzah.club.core.social;

import com.inpuzah.club.core.repository.ProfileStorageException;
import com.inpuzah.club.core.social.model.FriendRequest;
import com.inpuzah.club.core.social.model.FriendSummary;
import com.inpuzah.club.core.social.model.Relationship;
import com.inpuzah.club.core.social.model.SocialAction;
import com.inpuzah.club.core.social.model.SocialPreferences;
import com.inpuzah.club.core.social.model.SocialResult;
import com.inpuzah.club.core.social.repository.SocialException;
import com.inpuzah.club.core.social.repository.SocialRepository;
import com.inpuzah.club.core.social.service.DefaultSocialService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DefaultSocialServiceTest {
    private final UUID actor = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final FakeRepository repository = new FakeRepository();
    private final DefaultSocialService service = DefaultSocialService.create(repository, 1, 1,
            LoggerFactory.getLogger(getClass()));

    @AfterEach
    void stop() throws Exception {
        repository.release.countDown();
        service.shutdown(Duration.ofSeconds(5));
    }

    @Test
    void mutationIsOffThreadAndReturnsDomainOutcomeWithoutInventingSuccess() throws Exception {
        repository.release.countDown();
        repository.outcome = SocialResult.CANNOT_REQUEST;
        assertEquals(SocialResult.CANNOT_REQUEST, mutate().get(5, TimeUnit.SECONDS));
        assertNotEquals(Thread.currentThread(), repository.worker);
        assertEquals(SocialAction.REQUEST, repository.action);
        assertEquals(actor, repository.actor);
        assertEquals(other, repository.other);
    }

    @Test
    void delegatesBoundedReadAndPreferenceOperations() throws Exception {
        repository.release.countDown();
        assertEquals(List.of(), service.listFriends(actor, 10, 10).get(5, TimeUnit.SECONDS));
        assertEquals(List.of(), service.listIncomingRequests(actor, 0, 10).get(5, TimeUnit.SECONDS));
        assertEquals(List.of(), service.listBlocked(actor, 0, 10).get(5, TimeUnit.SECONDS));
        assertEquals(new SocialPreferences(actor, true), service.preferences(actor).get(5, TimeUnit.SECONDS));
        assertEquals(new SocialPreferences(actor, false), service.setPreferences(actor, false).get(5, TimeUnit.SECONDS));
        assertEquals(Relationship.NONE, service.relationship(actor, other).get(5, TimeUnit.SECONDS));
        assertEquals(6, repository.calls.get());
    }

    @Test
    void invalidInputFailsFutureBeforeRepository() {
        repository.release.countDown();
        assertInstanceOf(NullPointerException.class, failure(service.mutate(null, actor, other)));
        assertInstanceOf(NullPointerException.class, failure(service.mutate(SocialAction.REQUEST, null, other)));
        assertInstanceOf(NullPointerException.class, failure(service.preferences(null)));
        assertInstanceOf(NullPointerException.class, failure(service.relationship(null, other)));
        assertInstanceOf(NullPointerException.class, failure(service.relationship(actor, null)));
        assertInstanceOf(IllegalArgumentException.class, failure(service.relationship(actor, actor)));
        assertInstanceOf(IllegalArgumentException.class, failure(service.listFriends(actor, -1, 10)));
        assertInstanceOf(IllegalArgumentException.class, failure(service.listIncomingRequests(actor, 0, 101)));
        assertInstanceOf(IllegalArgumentException.class, failure(service.listBlocked(actor, 0, 0)));
        assertEquals(0, repository.calls.get());
    }

    @Test
    void storageAndTypedFailuresAreNotConvertedToEmptyListsOrSuccess() {
        repository.release.countDown();
        repository.failure = new ProfileStorageException("simulated outage", null);
        assertInstanceOf(ProfileStorageException.class, failure(mutate()));
        assertInstanceOf(ProfileStorageException.class, failure(service.listFriends(actor, 0, 10)));
        assertInstanceOf(ProfileStorageException.class, failure(service.relationship(actor, other)));
        repository.failure = new SocialException(SocialException.Code.PLAYER_NOT_FOUND, "missing");
        assertEquals(SocialException.Code.PLAYER_NOT_FOUND,
                assertInstanceOf(SocialException.class, failure(service.preferences(actor))).code());
    }

    @Test
    void queueIsBoundedAndDrainRejectsNewWork() throws Exception {
        var running = mutate();
        assertTrue(repository.entered.await(5, TimeUnit.SECONDS));
        var queued = mutate();
        unavailable(failure(mutate()));
        assertFalse(running.isDone());
        repository.release.countDown();
        assertEquals(0, service.shutdown(Duration.ofSeconds(5)));
        assertEquals(SocialResult.REQUEST_SENT, running.get(5, TimeUnit.SECONDS));
        assertEquals(SocialResult.REQUEST_SENT, queued.get(5, TimeUnit.SECONDS));
        unavailable(failure(mutate()));
    }

    @Test
    void callerTimeoutDoesNotCancelAcceptedPersistence() throws Exception {
        var running = mutate();
        assertTrue(repository.entered.await(5, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, failure(running.orTimeout(1, TimeUnit.MILLISECONDS)));
        repository.release.countDown();
        assertEquals(0, service.shutdown(Duration.ofSeconds(5)));
        assertEquals(1, repository.calls.get());
    }

    @Test
    void callerCancellationDoesNotCancelAcceptedPersistence() throws Exception {
        var running = mutate();
        assertTrue(repository.entered.await(5, TimeUnit.SECONDS));
        assertTrue(running.cancel(true));
        repository.release.countDown();
        assertEquals(0, service.shutdown(Duration.ofSeconds(5)));
        assertEquals(1, repository.calls.get());
    }

    @Test
    void shutdownDeadlineCompletesRunningAndQueuedRequestsExplicitly() throws Exception {
        repository.ignoreInterrupts = true;
        var running = mutate();
        assertTrue(repository.entered.await(5, TimeUnit.SECONDS));
        var queued = mutate();
        assertEquals(2, service.shutdown(Duration.ZERO));
        unavailable(failure(running));
        unavailable(failure(queued));
        unavailable(failure(mutate()));
    }

    @Test
    void interruptedShutdownCompletesRequestsAndStopsAccepting() throws Exception {
        repository.ignoreInterrupts = true;
        var running = mutate();
        assertTrue(repository.entered.await(5, TimeUnit.SECONDS));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> service.shutdown(Duration.ofSeconds(5)));
        } finally {
            Thread.interrupted();
        }
        unavailable(failure(running));
        unavailable(failure(mutate()));
    }

    private CompletableFuture<SocialResult> mutate() { return service.mutate(SocialAction.REQUEST, actor, other); }
    private static Throwable failure(CompletableFuture<?> future) {
        return assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)).getCause();
    }
    private static void unavailable(Throwable error) {
        assertEquals(SocialException.Code.SERVICE_UNAVAILABLE, assertInstanceOf(SocialException.class, error).code());
    }

    private static final class FakeRepository implements SocialRepository {
        @Override public Relationship relationship(UUID player, UUID other) {
            await();
            return Relationship.NONE;
        }
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean ignoreInterrupts;
        volatile RuntimeException failure;
        volatile Thread worker;
        SocialResult outcome = SocialResult.REQUEST_SENT;
        SocialAction action;
        UUID actor;
        UUID other;
        private void await() {
            worker = Thread.currentThread();
            entered.countDown();
            while (true) {
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Test gate timed out"); }
                    break;
                } catch (InterruptedException e) {
                    if (!ignoreInterrupts) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
            }
            if (failure != null) { throw failure; }
            calls.incrementAndGet();
        }
        @Override public SocialResult mutate(SocialAction action, UUID actor, UUID other) {
            await();
            this.action = action;
            this.actor = actor;
            this.other = other;
            return outcome;
        }
        @Override public List<FriendSummary> listFriends(UUID player, int offset, int limit) { await(); return List.of(); }
        @Override public List<FriendRequest> listIncomingRequests(UUID player, int offset, int limit) { await(); return List.of(); }
        @Override public List<FriendSummary> listBlocked(UUID player, int offset, int limit) { await(); return List.of(); }
        @Override public SocialPreferences preferences(UUID player) { await(); return new SocialPreferences(player, true); }
        @Override public SocialPreferences setPreferences(UUID player, boolean enabled) { await(); return new SocialPreferences(player, enabled); }
    }
}
