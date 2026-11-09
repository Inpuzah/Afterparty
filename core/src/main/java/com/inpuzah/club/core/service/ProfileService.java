package com.inpuzah.club.core.service;

import com.inpuzah.club.core.model.PlayerProfile;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The supported entry point for profile data. All work runs off the server thread; futures complete on a
 * worker thread, so callers must hop back to the server thread before touching Bukkit APIs.
 *
 * <p>Futures fail with {@link com.inpuzah.club.core.repository.ProfileStorageException} when the database is
 * unavailable, and with {@link ProfileServiceClosedException} after shutdown or when the work queue is full.
 */
public interface ProfileService {

    /** Concurrent calls for the same UUID share a single database request. */
    CompletableFuture<PlayerProfile> getOrCreate(UUID uuid, String username);

    CompletableFuture<Optional<PlayerProfile>> findById(UUID uuid);

    CompletableFuture<Void> touchLastSeen(UUID uuid);

    /**
     * Stops accepting work and waits up to {@code timeout} for queued operations.
     *
     * @return number of operations abandoned because they did not finish in time
     */
    int shutdown(Duration timeout) throws InterruptedException;
}
