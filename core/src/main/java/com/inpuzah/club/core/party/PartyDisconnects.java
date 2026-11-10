package com.inpuzah.club.core.party;

import com.inpuzah.club.core.party.model.PartyOutcome;
import com.inpuzah.club.core.party.service.PartyService;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** No Bukkit calls or blocking waits; one shared server task supplies presence and invokes sweep. */
public final class PartyDisconnects implements AutoCloseable {
    private static final int MAX_IN_FLIGHT = 16;
    private final Map<UUID, Presence> players = new HashMap<>();
    private final PartyService service;
    private final Duration grace;
    private final Clock clock;
    private final Logger logger;
    private final java.util.function.Consumer<Throwable> failure;
    private boolean closed;
    private int inFlight;

    private static final class Presence {
        final long token;
        final CompletableFuture<Void> barrier;
        final PartyDeparture departure = new PartyDeparture();
        boolean online = true;
        boolean submitted;
        Instant deadline;

        Presence(long token, CompletableFuture<Void> barrier) {
            this.token = token;
            this.barrier = barrier;
        }
    }

    public PartyDisconnects(PartyService service, Duration grace, Clock clock, Logger logger,
                            java.util.function.Consumer<Throwable> failure) {
        this.service = java.util.Objects.requireNonNull(service);
        this.grace = java.util.Objects.requireNonNull(grace);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.logger = java.util.Objects.requireNonNull(logger);
        this.failure = java.util.Objects.requireNonNull(failure);
        if (grace.isNegative() || grace.isZero()) {
            throw new IllegalArgumentException("Disconnect grace must be positive");
        }
    }

    public synchronized void joined(UUID player, long token) {
        if (closed) { return; }
        var old = players.get(player);
        if (old != null && old.online && old.token == token) { return; }
        CompletableFuture<Void> barrier = CompletableFuture.completedFuture(null);
        if (old != null) {
            old.departure.cancel();
            barrier = CompletableFuture.allOf(old.barrier, old.departure.finished());
        }
        players.put(player, new Presence(token, barrier));
    }

    public synchronized void quit(UUID player, long token) {
        var presence = players.get(player);
        if (closed || presence == null || presence.token != token || !presence.online) { return; }
        presence.online = false;
        presence.deadline = clock.instant().plus(grace);
    }

    /** A claimed expiry predates this login; wait asynchronously before admitting new party actions. */
    public synchronized boolean admitted(UUID player) {
        var presence = players.get(player);
        return !closed && presence != null && presence.online && presence.barrier.isDone();
    }

    public synchronized void sweep(Set<UUID> onlineMembers) {
        if (closed) { return; }
        var online = Set.copyOf(onlineMembers);
        Instant now = clock.instant();
        int submissions = 0;
        for (var entry : Map.copyOf(players).entrySet()) {
            if (closed || inFlight >= MAX_IN_FLIGHT || submissions >= MAX_IN_FLIGHT) { break; }
            var presence = entry.getValue();
            if (presence.online || presence.submitted || !presence.barrier.isDone()
                    || now.isBefore(presence.deadline)) { continue; }
            presence.submitted = true;
            ++inFlight;
            ++submissions;
            UUID uuid = entry.getKey();
            service.leaveDisconnected(uuid, online, presence.departure).whenComplete((result, error) ->
                    completed(uuid, presence, result, error));
        }
    }

    private synchronized void completed(UUID uuid, Presence presence, PartyOutcome result, Throwable error) {
        --inFlight;
        presence.departure.complete();
        if (error != null) {
            logger.warn("Party disconnect cleanup failed for {}; saved membership must be checked before retrying", uuid, error);
            close();
            failure.accept(error);
        } else if (result.code() != PartyOutcome.Code.LEFT && result.code() != PartyOutcome.Code.NOT_IN_PARTY
                && result.code() != PartyOutcome.Code.STALE_PARTY) {
            logger.warn("Unexpected party disconnect outcome for {}: {}", uuid, result.code());
            close();
            failure.accept(new IllegalStateException("Unexpected disconnect cleanup outcome: " + result.code()));
        }
        // Do not retry an uncertain write against a potentially different membership.
        players.remove(uuid, presence);
    }

    @Override public synchronized void close() {
        closed = true;
        players.values().forEach(value -> value.departure.cancel());
        players.clear();
    }
}
