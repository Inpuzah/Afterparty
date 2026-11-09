package com.inpuzah.club.core.repository;

import com.inpuzah.club.core.model.PlayerProfile;

import java.util.Optional;
import java.util.UUID;

/**
 * Blocking persistence for player profiles. Implementations must not depend on Paper/Bukkit types, and callers
 * must never invoke them from the server thread.
 *
 * <p>All methods throw {@link ProfileStorageException} when the store is unavailable.
 */
public interface ProfileRepository {

    /**
     * Creates the profile if it does not exist; otherwise updates the username and last-seen time. Never resets
     * first join, level, experience or coins.
     */
    PlayerProfile upsertOnJoin(UUID uuid, String username);

    Optional<PlayerProfile> findById(UUID uuid);

    /** @return {@code false} if no profile exists for the UUID */
    boolean touchLastSeen(UUID uuid);
}
