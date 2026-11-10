package com.inpuzah.club.core.progression;

import com.inpuzah.club.core.service.BoundedServiceExecutor;
import com.inpuzah.club.core.repository.ProfileStorageException;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

public final class DefaultProgressionService implements ProgressionService {

    private final ProgressionRepository repository;
    private final BoundedServiceExecutor executor;
    private final Consumer<UUID> afterCommit;

    /** The service owns this executor; do not share it with another service. */
    public DefaultProgressionService(ProgressionRepository repository, ExecutorService executor) {
        this(repository, executor, uuid -> {});
    }

    /** The refresh hook must not throw; it runs after success or an uncertain storage outcome. */
    public DefaultProgressionService(
            ProgressionRepository repository, ExecutorService executor, Consumer<UUID> afterCommit) {
        this.repository = Objects.requireNonNull(repository);
        this.afterCommit = Objects.requireNonNull(afterCommit);
        this.executor = new BoundedServiceExecutor(executor,
                message -> new ProgressionException(ProgressionException.Code.SERVICE_UNAVAILABLE, message));
    }

    public static DefaultProgressionService create(ProgressionRepository repository, int threads, int queueCapacity) {
        return create(repository, threads, queueCapacity, uuid -> {});
    }

    public static DefaultProgressionService create(
            ProgressionRepository repository, int threads, int queueCapacity, Consumer<UUID> afterCommit) {
        return new DefaultProgressionService(repository,
                BoundedServiceExecutor.workers("ClubCore-Progression", threads, queueCapacity), afterCommit);
    }

    @Override
    public CompletableFuture<ProgressionSnapshot> getProgress(UUID playerId) {
        return executor.submit(() -> repository.getProgress(Objects.requireNonNull(playerId, "playerId")));
    }

    @Override
    public CompletableFuture<XpAwardResult> awardXp(
            UUID operationId, UUID playerId, long amount, String source, String reason, UUID actorId) {
        return executor.submit(() -> {
            XpAwardRequest request = new XpAwardRequest(operationId, playerId, amount, source, reason, actorId);
            XpAwardResult result;
            try {
                result = repository.awardXp(request);
            } catch (ProfileStorageException e) {
                afterCommit.accept(playerId);
                throw e;
            }
            afterCommit.accept(playerId);
            return result;
        });
    }

    @Override
    public int shutdown(Duration timeout) throws InterruptedException {
        return executor.shutdown(timeout);
    }
}
