package com.inpuzah.club.core.progression;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Trusted server-side API, not a player authorization boundary. Each new action needs a new operation ID;
 * retries must reuse both its ID and exact payload. Futures complete off-thread: hop to the server thread
 * before using Bukkit APIs. The plugin-managed implementation invalidates and refreshes online profile
 * snapshots after committed success; it does not publish Bukkit reward events.
 */
public interface ProgressionService {

    CompletableFuture<ProgressionSnapshot> getProgress(UUID playerId);

    CompletableFuture<XpAwardResult> awardXp(
            UUID operationId, UUID playerId, long amount, String source, String reason, UUID actorId);

    /** Stops accepting work. Returns the number of requests still unresolved at the shutdown deadline. */
    int shutdown(Duration timeout) throws InterruptedException;
}
