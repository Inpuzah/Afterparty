package com.inpuzah.club.core.economy;

import com.inpuzah.club.core.service.BoundedServiceExecutor;
import com.inpuzah.club.core.repository.ProfileStorageException;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

public final class DefaultEconomyService implements EconomyService {

    private final EconomyRepository repository;
    private final BoundedServiceExecutor executor;
    private final Consumer<UUID> afterCommit;

    /** The service owns this executor; do not share it with another service. */
    public DefaultEconomyService(EconomyRepository repository, ExecutorService executor) {
        this(repository, executor, uuid -> {});
    }

    /** The refresh hook must not throw; it runs after success or an uncertain storage outcome. */
    public DefaultEconomyService(EconomyRepository repository, ExecutorService executor, Consumer<UUID> afterCommit) {
        this.repository = Objects.requireNonNull(repository);
        this.afterCommit = Objects.requireNonNull(afterCommit);
        this.executor = new BoundedServiceExecutor(executor,
                message -> new EconomyException(EconomyException.Code.SERVICE_UNAVAILABLE, message));
    }

    public static DefaultEconomyService create(EconomyRepository repository, int threads, int queueCapacity) {
        return create(repository, threads, queueCapacity, uuid -> {});
    }

    public static DefaultEconomyService create(
            EconomyRepository repository, int threads, int queueCapacity, Consumer<UUID> afterCommit) {
        return new DefaultEconomyService(repository,
                BoundedServiceExecutor.workers("ClubCore-Economy", threads, queueCapacity), afterCommit);
    }

    @Override
    public CompletableFuture<BalanceSnapshot> balance(UUID playerId) {
        return executor.submit(() -> repository.balance(Objects.requireNonNull(playerId, "playerId")));
    }

    @Override
    public CompletableFuture<CurrencyOperationResult> credit(
            UUID operationId, UUID playerId, long amount, String source, String reason, UUID actorId) {
        return apply(operationId, playerId, amount, CurrencyOperationType.CREDIT, source, reason, actorId);
    }

    @Override
    public CompletableFuture<CurrencyOperationResult> debit(
            UUID operationId, UUID playerId, long amount, String source, String reason, UUID actorId) {
        return apply(operationId, playerId, amount, CurrencyOperationType.DEBIT, source, reason, actorId);
    }

    private CompletableFuture<CurrencyOperationResult> apply(
            UUID operationId, UUID playerId, long amount, CurrencyOperationType type,
            String source, String reason, UUID actorId) {
        return executor.submit(() -> {
            CurrencyOperation operation = new CurrencyOperation(operationId, playerId, amount, type, source, reason, actorId);
            CurrencyOperationResult result;
            try {
                result = repository.apply(operation);
            } catch (ProfileStorageException e) {
                // A failed commit acknowledgement or connection close leaves the outcome uncertain.
                afterCommit.accept(playerId);
                throw e;
            }
            if (result.status() == CurrencyOperationResult.Status.SUCCESS) {
                afterCommit.accept(playerId);
            }
            return result;
        });
    }

    @Override
    public int shutdown(Duration timeout) throws InterruptedException {
        return executor.shutdown(timeout);
    }
}
