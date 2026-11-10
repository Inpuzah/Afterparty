package com.inpuzah.club.core.economy;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Trusted backend API for Club Coins, not an authorization boundary. Retry an unknown outcome with the
 * original operation ID and payload. Futures complete off-thread; hop to the server thread for Bukkit calls.
 * The plugin-managed implementation invalidates and refreshes online profile snapshots after committed success.
 */
public interface EconomyService {

    CompletableFuture<BalanceSnapshot> balance(UUID playerId);

    CompletableFuture<CurrencyOperationResult> credit(
            UUID operationId, UUID playerId, long amount, String source, String reason, UUID actorId);

    CompletableFuture<CurrencyOperationResult> debit(
            UUID operationId, UUID playerId, long amount, String source, String reason, UUID actorId);

    /** Stops accepting work and returns requests still unresolved at the shutdown deadline. */
    int shutdown(Duration timeout) throws InterruptedException;
}
