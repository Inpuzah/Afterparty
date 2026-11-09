package com.inpuzah.club.core.economy;

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

class DefaultEconomyServiceTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private final FakeRepository repository = new FakeRepository();
    private final DefaultEconomyService service = DefaultEconomyService.create(repository, 1, 1);

    @AfterEach
    void stop() throws InterruptedException {
        repository.release.countDown();
        service.shutdown(Duration.ofSeconds(5));
    }

    @Test
    void creditsOffThreadWithExactPayload() throws Exception {
        repository.release.countDown();
        UUID operation = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        var result = service.credit(operation, PLAYER, 100, "admin", "reward", actor).get(5, TimeUnit.SECONDS);

        assertNotEquals(Thread.currentThread(), repository.worker.get());
        assertEquals(new CurrencyOperation(operation, PLAYER, 100, CurrencyOperationType.CREDIT, "admin", "reward", actor),
                result.operation());
        assertEquals(CurrencyOperationResult.Status.SUCCESS, result.status());
        assertEquals(100, result.balance());
    }

    @Test
    void debitPropagatesInsufficientFundsAsAnOutcome() throws Exception {
        repository.release.countDown();

        var result = service.debit(UUID.randomUUID(), PLAYER, 100, "test", "debit", null).get(5, TimeUnit.SECONDS);

        assertEquals(CurrencyOperationType.DEBIT, result.operation().type());
        assertEquals(CurrencyOperationResult.Status.INSUFFICIENT_FUNDS, result.status());
        assertEquals(0, result.balance());
    }

    @Test
    void balanceReadsDoNotMutate() throws Exception {
        repository.release.countDown();
        assertEquals(new BalanceSnapshot(PLAYER, 0), service.balance(PLAYER).get(5, TimeUnit.SECONDS));
        assertEquals(0, repository.operations.get());
    }

    @Test
    void storageFailureIsNotSuccessOrInsufficientFunds() {
        repository.failure = new ProfileStorageException("simulated outage", null);
        repository.release.countDown();
        assertInstanceOf(ProfileStorageException.class, failure(credit()));
        assertInstanceOf(ProfileStorageException.class, failure(service.balance(PLAYER)));
    }

    @Test
    void preservesDomainFailures() {
        repository.failure = new EconomyException(EconomyException.Code.OPERATION_CONFLICT, "conflict");
        repository.release.countDown();
        assertEquals(EconomyException.Code.OPERATION_CONFLICT,
                assertInstanceOf(EconomyException.class, failure(credit())).code());
    }

    @Test
    void invalidInputFailsFutureWithoutCallingRepository() {
        repository.release.countDown();
        assertInstanceOf(IllegalArgumentException.class,
                failure(service.credit(UUID.randomUUID(), PLAYER, 0, "test", "invalid", null)));
        assertInstanceOf(IllegalArgumentException.class,
                failure(service.debit(UUID.randomUUID(), PLAYER, -1, "test", "invalid", null)));
        assertInstanceOf(NullPointerException.class, failure(service.balance(null)));
        assertEquals(0, repository.operations.get());
    }

    @Test
    void fullQueueRejectsExplicitlyAndAcceptedWorkFinishes() throws Exception {
        var running = credit();
        assertTrue(repository.entered.await(5, TimeUnit.SECONDS));
        var queued = credit();

        assertUnavailable(failure(credit()));
        assertFalse(running.isDone());
        assertFalse(queued.isDone());
        repository.release.countDown();
        running.get(5, TimeUnit.SECONDS);
        queued.get(5, TimeUnit.SECONDS);
    }

    @Test
    void callerTimeoutDoesNotCancelAcceptedOperation() throws Exception {
        var caller = credit();
        assertTrue(repository.entered.await(5, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, failure(caller.orTimeout(1, TimeUnit.MILLISECONDS)));

        repository.release.countDown();
        assertEquals(0, service.shutdown(Duration.ofSeconds(5)));
        assertEquals(1, repository.operations.get());
    }

    @Test
    void callerCancellationDoesNotCancelAcceptedOperation() throws Exception {
        var caller = credit();
        assertTrue(repository.entered.await(5, TimeUnit.SECONDS));
        assertTrue(caller.cancel(true));

        repository.release.countDown();
        assertEquals(0, service.shutdown(Duration.ofSeconds(5)));
        assertEquals(1, repository.operations.get());
    }

    @Test
    void normalShutdownDrainsAndRejectsFutureWork() throws Exception {
        repository.release.countDown();
        var accepted = credit();
        assertEquals(0, service.shutdown(Duration.ofSeconds(5)));
        assertEquals(CurrencyOperationResult.Status.SUCCESS, accepted.get(5, TimeUnit.SECONDS).status());
        assertUnavailable(failure(credit()));
        assertUnavailable(failure(service.balance(PLAYER)));
    }

    @Test
    void shutdownDeadlineCompletesQueuedAndRunningFutures() throws Exception {
        repository.ignoreInterrupts = true;
        var running = credit();
        assertTrue(repository.entered.await(5, TimeUnit.SECONDS));
        var queued = credit();

        assertEquals(2, service.shutdown(Duration.ZERO));

        assertUnavailable(failure(running));
        assertUnavailable(failure(queued));
        assertUnavailable(failure(credit()));
    }

    @Test
    void interruptedShutdownFailsPendingRequestsAndStopsAcceptingWork() throws Exception {
        repository.ignoreInterrupts = true;
        var running = credit();
        assertTrue(repository.entered.await(5, TimeUnit.SECONDS));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> service.shutdown(Duration.ofSeconds(5)));
        } finally {
            Thread.interrupted();
        }

        assertUnavailable(failure(running));
        assertUnavailable(failure(credit()));
    }

    private CompletableFuture<CurrencyOperationResult> credit() {
        return service.credit(UUID.randomUUID(), PLAYER, 100, "test", "reward", null);
    }

    private static Throwable failure(CompletableFuture<?> future) {
        return assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)).getCause();
    }

    private static void assertUnavailable(Throwable failure) {
        assertEquals(EconomyException.Code.SERVICE_UNAVAILABLE, assertInstanceOf(EconomyException.class, failure).code());
    }

    private static final class FakeRepository implements EconomyRepository {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger operations = new AtomicInteger();
        final AtomicReference<Thread> worker = new AtomicReference<>();
        volatile RuntimeException failure;
        volatile boolean ignoreInterrupts;

        @Override
        public BalanceSnapshot balance(UUID playerId) {
            await();
            return new BalanceSnapshot(playerId, 0);
        }

        @Override
        public CurrencyOperationResult apply(CurrencyOperation operation) {
            await();
            operations.incrementAndGet();
            return new CurrencyOperationResult(operation,
                    operation.type() == CurrencyOperationType.CREDIT
                            ? CurrencyOperationResult.Status.SUCCESS : CurrencyOperationResult.Status.INSUFFICIENT_FUNDS,
                    0, operation.type() == CurrencyOperationType.CREDIT ? operation.amount() : 0, false);
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
