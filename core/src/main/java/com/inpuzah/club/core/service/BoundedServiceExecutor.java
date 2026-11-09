package com.inpuzah.club.core.service;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/** Owns a service's workers and completes unresolved requests explicitly when shutdown cannot drain them. */
public final class BoundedServiceExecutor {

    private final ExecutorService executor;
    private final Function<String, ? extends RuntimeException> unavailable;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final Object lifecycle = new Object();
    private boolean accepting = true;

    public BoundedServiceExecutor(ExecutorService executor, Function<String, ? extends RuntimeException> unavailable) {
        this.executor = Objects.requireNonNull(executor);
        this.unavailable = Objects.requireNonNull(unavailable);
    }

    public static ExecutorService workers(String name, int threads, int queueCapacity) {
        AtomicInteger counter = new AtomicInteger();
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), task -> {
                    Thread thread = new Thread(task, name + "-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> CompletableFuture<T> submit(Supplier<T> operation) {
        synchronized (lifecycle) {
            if (!accepting) {
                return rejected();
            }
            try {
                CompletableFuture<T> future = CompletableFuture.supplyAsync(operation, executor);
                pending.add(future);
                future.whenComplete((result, error) -> pending.remove(future));
                return future.copy();
            } catch (RejectedExecutionException e) {
                return rejected();
            }
        }
    }

    public int shutdown(Duration timeout) throws InterruptedException {
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("shutdown timeout must be nonnegative");
        }
        synchronized (lifecycle) {
            accepting = false;
            executor.shutdown();
        }
        try {
            if (executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return 0;
            }
        } catch (InterruptedException e) {
            abandonPending();
            throw e;
        }
        return abandonPending();
    }

    private int abandonPending() {
        CompletableFuture<?>[] unresolved = pending.toArray(CompletableFuture<?>[]::new);
        executor.shutdownNow();
        for (CompletableFuture<?> future : unresolved) {
            future.completeExceptionally(unavailable.apply(
                    "Service shutdown deadline reached; an in-flight commit may still have succeeded. Retry the same operation ID."));
        }
        return unresolved.length;
    }

    private <T> CompletableFuture<T> rejected() {
        return CompletableFuture.failedFuture(unavailable.apply("Service is shut down or its queue is full"));
    }
}
