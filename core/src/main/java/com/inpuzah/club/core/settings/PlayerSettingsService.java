package com.inpuzah.club.core.settings;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One session cache and persistence path for GUI, commands and displays.
 * Futures/observers may complete off-thread: schedule Bukkit work on the server thread.
 * Caller cancellation/timeout does not cancel accepted persistence.
 */
public interface PlayerSettingsService {
    enum State { LOADING, READY, SAVING, UNAVAILABLE, OFFLINE }

    CompletableFuture<PlayerSettings> get(UUID playerId);
    Optional<PlayerSettings> cached(UUID playerId);
    State state(UUID playerId);
    CompletableFuture<PlayerSettings> setBossbarVisible(UUID playerId, boolean visible);
    CompletableFuture<PlayerSettings> setScoreboardVisible(UUID playerId, boolean visible);
    int shutdown(Duration timeout) throws InterruptedException;
}
