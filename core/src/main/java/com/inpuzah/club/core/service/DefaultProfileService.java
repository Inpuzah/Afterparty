package com.inpuzah.club.core.service;

import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.repository.ProfileRepository;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class DefaultProfileService implements ProfileService {

    private final ProfileRepository repository;
    private final ExecutorService executor;
    private final ConcurrentHashMap<UUID, CompletableFuture<PlayerProfile>> inFlightLoads = new ConcurrentHashMap<>();

    public DefaultProfileService(ProfileRepository repository, ExecutorService executor) {
        this.repository = Objects.requireNonNull(repository);
        this.executor = Objects.requireNonNull(executor);
    }

    /** Fixed number of worker threads with a bounded queue; excess work is rejected rather than queued forever. */
    public static DefaultProfileService create(ProfileRepository repository, int threads, int queueCapacity) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "ClubCore-Profile-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
        return new DefaultProfileService(repository, executor);
    }

    @Override
    public CompletableFuture<PlayerProfile> getOrCreate(UUID uuid, String username) {
        Objects.requireNonNull(uuid, "uuid");
        PlayerProfile.validateUsername(username);

        CompletableFuture<PlayerProfile> created = new CompletableFuture<>();
        CompletableFuture<PlayerProfile> existing = inFlightLoads.putIfAbsent(uuid, created);
        if (existing != null) {
            return existing.copy();
        }

        created.whenComplete((profile, error) -> inFlightLoads.remove(uuid, created));
        submit(() -> repository.upsertOnJoin(uuid, username)).whenComplete((profile, error) -> {
            if (error != null) {
                created.completeExceptionally(error);
            } else {
                created.complete(profile);
            }
        });
        return created.copy();
    }

    @Override
    public CompletableFuture<Optional<PlayerProfile>> findById(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return submit(() -> repository.findById(uuid));
    }

    @Override
    public CompletableFuture<Void> touchLastSeen(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return submit(() -> {
            repository.touchLastSeen(uuid);
            return null;
        });
    }

    @Override
    public int shutdown(Duration timeout) throws InterruptedException {
        executor.shutdown();
        if (executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            return 0;
        }
        return executor.shutdownNow().size();
    }

    private <T> CompletableFuture<T> submit(Supplier<T> work) {
        try {
            return CompletableFuture.supplyAsync(work, executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(
                    new ProfileServiceClosedException("Profile service is shut down or its queue is full", e));
        }
    }
}
