package com.inpuzah.club.core.progression;

import com.inpuzah.club.core.repository.ProfileStorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultProgressionServiceTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private final FakeRepository repository = new FakeRepository();
    private final DefaultProgressionService service = DefaultProgressionService.create(repository, 1, 1);

    @AfterEach
    void stop() throws InterruptedException {
        repository.release.countDown();
        service.shutdown(Duration.ofSeconds(5));
    }

    @Test
    void runsRepositoryOffCallingThreadAndPreservesMetadata() throws Exception {
        repository.release.countDown();
        UUID operation = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        XpAwardResult result = service.awardXp(operation, PLAYER, 100, "admin", "activity reward", actor)
                .get(5, TimeUnit.SECONDS);

        assertNotEquals(Thread.currentThread(), repository.worker.get());
        assertEquals(new XpAwardRequest(operation, PLAYER, 100, "admin", "activity reward", actor), result.request());
        assertEquals(2, result.level());
    }

    @Test
    void readsProgressWithoutInventingAnAward() throws Exception {
        repository.release.countDown();

        assertEquals(new ProgressionSnapshot(PLAYER, 300), service.getProgress(PLAYER).get(5, TimeUnit.SECONDS));
        assertEquals(0, repository.awards.get());
    }

    @Test
    void storageFailureCompletesExceptionally() {
        repository.failure = new ProfileStorageException("simulated failure", null);
        repository.release.countDown();

        assertInstanceOf(ProfileStorageException.class, failure(award()));
    }

    @Test
    void domainFailureIsNotReportedAsStorageFailure() {
        repository.failure = new ProgressionException(ProgressionException.Code.PROFILE_NOT_FOUND, "missing");
        repository.release.countDown();

        ProgressionException error = assertInstanceOf(ProgressionException.class, failure(award()));
        assertEquals(ProgressionException.Code.PROFILE_NOT_FOUND, error.code());
    }

    @Test
    void invalidRequestFailsFutureWithoutCallingRepository() {
        repository.release.countDown();

        assertInstanceOf(IllegalArgumentException.class,
                failure(service.awardXp(UUID.randomUUID(), PLAYER, 0, "test", "reason", null)));
        assertEquals(0, repository.awards.get());
    }

    @Test
    void fullQueueFailsExplicitly() throws Exception {
        CompletableFuture<XpAwardResult> running = award();
        assertTrue(repository.entered.await(5, TimeUnit.SECONDS));
        CompletableFuture<XpAwardResult> queued = award();

        assertUnavailable(failure(award()));
        assertFalse(running.isDone());
        assertFalse(queued.isDone());
        repository.release.countDown();
        running.get(5, TimeUnit.SECONDS);
        queued.get(5, TimeUnit.SECONDS);
    }

    @Test
    void timeoutOnCallerFutureDoesNotCancelAcceptedDatabaseWork() throws Exception {
        CompletableFuture<XpAwardResult> caller = award();
        assertTrue(repository.entered.await(5, TimeUnit.SECONDS));
        caller.orTimeout(1, TimeUnit.MILLISECONDS);
        assertInstanceOf(TimeoutException.class, failure(caller));

        repository.release.countDown();
        assertEquals(0, service.shutdown(Duration.ofSeconds(5)));
        assertEquals(1, repository.awards.get());
    }

    @Test
    void normalShutdownDrainsWorkAndRejectsNewRequests() throws Exception {
        repository.release.countDown();
        CompletableFuture<XpAwardResult> accepted = award();

        assertEquals(0, service.shutdown(Duration.ofSeconds(5)));
        assertEquals(100, accepted.get(5, TimeUnit.SECONDS).experience());
        assertUnavailable(failure(award()));
        assertUnavailable(failure(service.getProgress(PLAYER)));
    }

    @Test
    void shutdownDeadlineCompletesBothQueuedAndRunningFutures() throws Exception {
        // Keep the worker blocked even after interruption, like a database request whose outcome is unknown.
        repository.ignoreInterrupts = true;
        CompletableFuture<XpAwardResult> running = award();
        assertTrue(repository.entered.await(5, TimeUnit.SECONDS));
        CompletableFuture<XpAwardResult> queued = award();

        assertEquals(2, service.shutdown(Duration.ZERO));

        assertUnavailable(failure(running));
        assertUnavailable(failure(queued));
        assertUnavailable(failure(award()));
    }

    @Test
    void interruptedShutdownStopsAcceptingWorkAndFailsPendingFutures() throws Exception {
        repository.ignoreInterrupts = true;
        CompletableFuture<XpAwardResult> running = award();
        assertTrue(repository.entered.await(5, TimeUnit.SECONDS));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> service.shutdown(Duration.ofSeconds(5)));
        } finally {
            Thread.interrupted();
        }

        assertUnavailable(failure(running));
        assertUnavailable(failure(award()));
    }

    private CompletableFuture<XpAwardResult> award() {
        return service.awardXp(UUID.randomUUID(), PLAYER, 100, "test", "award", null);
    }

    private static Throwable failure(CompletableFuture<?> future) {
        return assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)).getCause();
    }

    private static void assertUnavailable(Throwable failure) {
        assertEquals(ProgressionException.Code.SERVICE_UNAVAILABLE,
                assertInstanceOf(ProgressionException.class, failure).code());
    }

    private static final class FakeRepository implements ProgressionRepository {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger awards = new AtomicInteger();
        final AtomicReference<Thread> worker = new AtomicReference<>();
        volatile RuntimeException failure;
        volatile boolean ignoreInterrupts;

        @Override
        public ProgressionSnapshot getProgress(UUID playerId) {
            await();
            return new ProgressionSnapshot(playerId, 300);
        }

        @Override
        public XpAwardResult awardXp(XpAwardRequest request) {
            await();
            awards.incrementAndGet();
            return new XpAwardResult(request, 0, request.amount(), false);
        }

        private void await() {
            worker.set(Thread.currentThread());
            entered.countDown();
            boolean released = false;
            while (!released) {
                try {
                    release.await();
                    released = true;
                } catch (InterruptedException e) {
                    if (!ignoreInterrupts) {
                        Thread.currentThread().interrupt();
                        throw new ProfileStorageException("interrupted", e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
