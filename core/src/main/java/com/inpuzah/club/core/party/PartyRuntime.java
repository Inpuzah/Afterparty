package com.inpuzah.club.core.party;

import com.inpuzah.club.core.party.repository.JdbcPartyRepository;
import com.inpuzah.club.core.party.repository.PartyEpochLease;
import com.inpuzah.club.core.party.service.DefaultPartyService;
import com.inpuzah.club.core.party.service.PartyService;
import com.inpuzah.club.core.service.BoundedServiceExecutor;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Owns one epoch; all JDBC lifecycle work runs outside the Paper thread. */
public final class PartyRuntime {
    public enum State { STARTING, READY, FAILED, STOPPING, STOPPED }

    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);
    private final Object lifecycle = new Object();
    private final ScheduledExecutorService control;
    private final Logger logger;
    private final CompletableFuture<Void> started = new CompletableFuture<>();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private State state = State.STARTING;
    private DefaultPartyService service;
    private ExecutorService workers;
    private PartyEpochLease lease;
    private ScheduledFuture<?> heartbeat;
    private boolean cleanupQueued;

    public PartyRuntime(DataSource source, PGSimpleDataSource directSource, String ownerId,
                        PartyPolicy policy, int threads, int capacity, Logger logger) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(directSource);
        Objects.requireNonNull(ownerId);
        Objects.requireNonNull(policy);
        this.logger = Objects.requireNonNull(logger);
        if (threads < 1 || capacity < 1) {
            throw new IllegalArgumentException("Party worker count and queue capacity must be positive");
        }
        control = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "ClubCore-Party-Lifecycle");
            thread.setDaemon(true);
            return thread;
        });
        control.execute(() -> start(source, directSource, ownerId, policy, threads, capacity));
    }

    public State state() {
        synchronized (lifecycle) {
            return state;
        }
    }

    public CompletableFuture<Void> started() {
        return started.copy();
    }

    public PartyService service() {
        synchronized (lifecycle) {
            if (state != State.READY) {
                throw new IllegalStateException("Party runtime is " + state);
            }
            return service;
        }
    }

    private void start(DataSource source, PGSimpleDataSource directSource, String ownerId,
                       PartyPolicy policy, int threads, int capacity) {
        try {
            synchronized (lifecycle) {
                if (state != State.STARTING) {
                    return;
                }
            }
            lease = PartyEpochLease.acquireMatching(directSource, ownerId, source);
            workers = BoundedServiceExecutor.workers("ClubCore-Party", threads, capacity);
            var next = new DefaultPartyService(new JdbcPartyRepository(source, lease, policy),
                    workers, logger, this::verifyLease);
            synchronized (lifecycle) {
                service = next;
                if (state != State.STARTING) {
                    next.invalidate();
                    return;
                }
                state = State.READY;
                heartbeat = control.scheduleWithFixedDelay(this::checkHealth, 5, 5, TimeUnit.SECONDS);
            }
            logger.info("Party backend ready for owner {}; server-thread control activation may now proceed", ownerId);
            started.complete(null);
        } catch (RuntimeException error) {
            fail(error);
        }
    }

    public void observe(java.util.function.Consumer<com.inpuzah.club.core.party.model.PartyChange> observer) {
        synchronized (lifecycle) {
            if (state != State.READY) { throw new IllegalStateException("Party runtime is " + state); }
            service.observe(observer);
        }
    }

    /** Explicit asynchronous probe, also used by the shared heartbeat. */
    public CompletableFuture<Void> checkHealth() {
        synchronized (lifecycle) {
            if (state != State.READY) {
                return CompletableFuture.failedFuture(new IllegalStateException("Party runtime is " + state));
            }
            var result = new CompletableFuture<Void>();
            control.execute(() -> {
                try {
                    verifyLease();
                    result.complete(null);
                } catch (RuntimeException error) {
                    result.completeExceptionally(error);
                }
            });
            return result.copy();
        }
    }

    private void verifyLease() {
        synchronized (lifecycle) {
            if (state != State.READY) {
                throw new IllegalStateException("Party runtime is " + state);
            }
        }
        try {
            lease.verify();
        } catch (RuntimeException error) {
            fail(error);
            throw error;
        }
    }

    private void fail(RuntimeException error) {
        synchronized (lifecycle) {
            if (state != State.STOPPING && state != State.STOPPED) {
                state = State.FAILED;
            }
            if (service != null) {
                service.invalidate();
            }
            queueCleanup();
        }
        logger.error("Party runtime failed; party work stopped until restart. Other ClubCore services remain available.", error);
        started.completeExceptionally(error);
    }

    public CompletableFuture<Void> stop() {
        synchronized (lifecycle) {
            if (stopped.isDone()) {
                state = State.STOPPED;
                return stopped.copy();
            }
            if (state != State.STOPPED) {
                state = State.STOPPING;
                if (service != null) {
                    service.invalidate();
                }
                if (heartbeat != null) {
                    heartbeat.cancel(false);
                }
                started.completeExceptionally(new IllegalStateException("Party runtime stopped before startup completed"));
                queueCleanup();
            }
            return stopped.copy();
        }
    }

    private void queueCleanup() {
        if (!cleanupQueued) {
            cleanupQueued = true;
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            control.execute(this::cleanup);
        }
    }

    private void cleanup() {
        try {
            if (service != null) {
                int unresolved = service.shutdown(DRAIN_TIMEOUT);
                if (unresolved > 0) {
                    logger.warn("{} party requests unresolved; retaining epoch lease until workers actually terminate. In-flight commits may have succeeded.", unresolved);
                }
            }
            // Abandoning futures is not worker termination. Never release ownership under a running transaction.
            if (workers != null) {
                workers.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            }
            if (lease != null) {
                lease.close();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            logger.error("Party cleanup interrupted; epoch ownership was not safely released", error);
            finishCleanup(error);
            return;
        } catch (RuntimeException error) {
            logger.error("Party epoch cleanup failed; next startup must reconcile the previous epoch", error);
            finishCleanup(error);
            return;
        }
        finishCleanup(null);
    }

    private void finishCleanup(Throwable error) {
        synchronized (lifecycle) {
            if (state != State.FAILED) {
                state = State.STOPPED;
            }
            control.shutdown();
            if (error == null) {
                stopped.complete(null);
            } else {
                stopped.completeExceptionally(error);
            }
        }
    }
}
