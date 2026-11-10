package com.inpuzah.club.core.party.service;

import com.inpuzah.club.core.party.model.Party;
import com.inpuzah.club.core.party.PartyDeparture;
import com.inpuzah.club.core.party.model.PartyInvite;
import com.inpuzah.club.core.party.model.PartyOutcome;
import com.inpuzah.club.core.party.model.PartyChange;
import com.inpuzah.club.core.party.repository.PartyException;
import com.inpuzah.club.core.party.repository.PartyRepository;
import com.inpuzah.club.core.repository.ProfileStorageException;
import com.inpuzah.club.core.service.BoundedServiceExecutor;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class DefaultPartyService implements PartyService {
    private final PartyRepository repository;
    private final BoundedServiceExecutor workers;
    private final Logger logger;
    private final Runnable beforeOperation;
    private final AtomicBoolean available = new AtomicBoolean(true);
    private volatile java.util.function.Consumer<PartyChange> observer;

    /** Runtime notifications require its single FIFO worker for coherent before/after snapshots. */
    public void observe(java.util.function.Consumer<PartyChange> observer) {
        this.observer = Objects.requireNonNull(observer);
    }

    public DefaultPartyService(PartyRepository repository, ExecutorService executor, Logger logger) {
        this(repository, executor, logger, () -> {});
    }

    public DefaultPartyService(PartyRepository repository, ExecutorService executor, Logger logger, Runnable beforeOperation) {
        this.repository = Objects.requireNonNull(repository);
        this.logger = Objects.requireNonNull(logger);
        this.beforeOperation = Objects.requireNonNull(beforeOperation);
        workers = new BoundedServiceExecutor(executor,
                message -> new PartyException(PartyException.Code.SERVICE_UNAVAILABLE, message));
    }

    public static DefaultPartyService create(PartyRepository repository, int threads, int capacity, Logger logger) {
        return new DefaultPartyService(repository, BoundedServiceExecutor.workers("ClubCore-Party", threads, capacity), logger);
    }

    @Override public CompletableFuture<PartyOutcome> create(UUID actor) {
        return mutate(actor, () -> repository.create(Objects.requireNonNull(actor)));
    }
    @Override public CompletableFuture<PartyOutcome> invite(UUID actor, UUID target) {
        return mutate(actor, () -> repository.invite(Objects.requireNonNull(actor), Objects.requireNonNull(target)));
    }
    @Override public CompletableFuture<PartyOutcome> accept(UUID actor, UUID inviteId) {
        return mutate(actor, () -> repository.accept(Objects.requireNonNull(actor), Objects.requireNonNull(inviteId)));
    }
    @Override public CompletableFuture<PartyOutcome> deny(UUID actor, UUID inviteId) {
        return submit(() -> repository.deny(Objects.requireNonNull(actor), Objects.requireNonNull(inviteId)));
    }
    @Override public CompletableFuture<PartyOutcome> leave(UUID actor, Set<UUID> onlineMembers) {
        // Freeze before queuing so the worker never iterates a caller-owned mutable collection.
        final Set<UUID> online;
        try {
            Objects.requireNonNull(actor);
            online = Set.copyOf(onlineMembers);
        } catch (NullPointerException error) {
            return CompletableFuture.failedFuture(error);
        }
        return mutate(actor, () -> repository.leave(actor, online));
    }
    @Override public CompletableFuture<PartyOutcome> kick(UUID actor, UUID target) {
        return mutate(actor, () -> repository.kick(Objects.requireNonNull(actor), Objects.requireNonNull(target)));
    }
    @Override public CompletableFuture<PartyOutcome> leaveIfCurrent(UUID actor, Set<UUID> onlineMembers, UUID partyId, long revision) {
        final Set<UUID> online;
        try {
            Objects.requireNonNull(actor);
            Objects.requireNonNull(partyId);
            if (revision < 0) { throw new IllegalArgumentException("Party revision must be nonnegative"); }
            online = Set.copyOf(onlineMembers);
        } catch (IllegalArgumentException | NullPointerException error) {
            return CompletableFuture.failedFuture(error);
        }
        return mutate(actor, () -> repository.leaveIfCurrent(actor, online, partyId, revision));
    }
    @Override public CompletableFuture<PartyOutcome> leaveDisconnected(UUID actor, Set<UUID> onlineMembers, PartyDeparture departure) {
        final Set<UUID> online;
        try {
            Objects.requireNonNull(actor);
            Objects.requireNonNull(departure);
            online = Set.copyOf(onlineMembers);
        } catch (NullPointerException error) {
            return CompletableFuture.failedFuture(error);
        }
        return mutate(actor, () -> repository.leaveDisconnected(actor, online, departure));
    }
    @Override public CompletableFuture<PartyOutcome> promote(UUID actor, UUID target) {
        return mutate(actor, () -> repository.promote(Objects.requireNonNull(actor), Objects.requireNonNull(target)));
    }
    @Override public CompletableFuture<PartyOutcome> disband(UUID actor) {
        return mutate(actor, () -> repository.disband(Objects.requireNonNull(actor)));
    }
    @Override public CompletableFuture<Optional<Party>> getParty(UUID player) {
        return submit(() -> repository.getParty(Objects.requireNonNull(player)));
    }
    @Override public CompletableFuture<PartyOutcome> disbandIfCurrent(UUID actor, UUID partyId, long revision) {
        return mutate(actor, () -> {
            Objects.requireNonNull(actor);
            Objects.requireNonNull(partyId);
            if (revision < 0) { throw new IllegalArgumentException("Party revision must be nonnegative"); }
            return repository.disbandIfCurrent(actor, partyId, revision);
        });
    }
    @Override public CompletableFuture<Void> withParty(UUID player, java.util.function.Consumer<Party> delivery) {
        return submit(() -> {
            repository.withParty(Objects.requireNonNull(player), Objects.requireNonNull(delivery));
            return null;
        });
    }

    private CompletableFuture<PartyOutcome> mutate(UUID actor, Supplier<PartyOutcome> operation) {
        return submit(() -> {
            var listener = observer;
            if (listener == null) { return operation.get(); }
            Objects.requireNonNull(actor);
            var before = notificationSnapshot(actor);
            var outcome = operation.get();
            if (switch (outcome.code()) {
                case CREATED, INVITED, ACCEPTED, LEFT, KICKED, PROMOTED, DISBANDED -> true;
                default -> false;
            }) {
                UUID remaining = outcome.code() == PartyOutcome.Code.LEFT && before.isPresent()
                        ? before.orElseThrow().members().stream().map(member -> member.playerId())
                        .filter(id -> !id.equals(actor)).findFirst().orElse(actor) : actor;
                var change = new PartyChange(actor, outcome, before, notificationSnapshot(remaining));
                try {
                    listener.accept(change);
                } catch (RuntimeException error) {
                    logger.error("Party mutation committed but member notification failed for {}", actor, error);
                }
            }
            return outcome;
        });
    }

    private Optional<Party> notificationSnapshot(UUID player) {
        try {
            return repository.getParty(player);
        } catch (PartyException error) {
            // A failed initial profile load can still produce a quit; FK constraints rule out membership.
            if (error.code() == PartyException.Code.PLAYER_NOT_FOUND) { return Optional.empty(); }
            throw error;
        }
    }
    @Override public CompletableFuture<List<PartyInvite>> getInvites(UUID player, int offset, int limit) {
        return submit(() -> {
            Objects.requireNonNull(player);
            if (offset < 0 || limit < 1 || limit > 100) {
                throw new IllegalArgumentException("Invite offset must be nonnegative and limit 1-100");
            }
            return List.copyOf(repository.getInvites(player, offset, limit));
        });
    }

    private <T> CompletableFuture<T> submit(Supplier<T> operation) {
        if (!available.get()) {
            return CompletableFuture.failedFuture(unavailable());
        }
        return workers.submit(() -> {
            if (!available.get()) {
                throw unavailable();
            }
            try {
                beforeOperation.run();
                return operation.get();
            } catch (ProfileStorageException error) {
                logger.warn("Party storage operation failed; check authoritative state before retrying mutations", error);
                throw error;
            } catch (IllegalStateException error) {
                logger.warn("Party operation could not safely complete", error);
                throw error;
            } catch (PartyException error) {
                if (error.code() == PartyException.Code.STALE_EPOCH) {
                    logger.warn("Party epoch or membership is stale; stop party work", error);
                }
                throw error;
            }
        });
    }

    public void invalidate() {
        available.set(false);
    }

    private static PartyException unavailable() {
        return new PartyException(PartyException.Code.SERVICE_UNAVAILABLE, "Party runtime is unavailable; restart is required");
    }

    @Override public int shutdown(Duration timeout) throws InterruptedException {
        return workers.shutdown(timeout);
    }
}
