package com.inpuzah.club.core.session;

import com.inpuzah.club.core.model.PlayerProfile;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable snapshot of one online session. {@code token} is unique per session so late async results from an
 * earlier session for the same player can be recognised and dropped.
 */
public record ProfileSession(UUID uuid, long token, long revision, SessionState state, PlayerProfile profile) {

    public ProfileSession(UUID uuid, long token, SessionState state, PlayerProfile profile) {
        this(uuid, token, 0, state, profile);
    }

    public ProfileSession {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(state, "state");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be nonnegative");
        }
        if ((state == SessionState.READY) != (profile != null)) {
            throw new IllegalArgumentException("profile must be present exactly when state is READY");
        }
    }

    public Optional<PlayerProfile> readyProfile() {
        return Optional.ofNullable(profile);
    }

    ProfileSession withState(SessionState newState, PlayerProfile newProfile) {
        return new ProfileSession(uuid, token, revision, newState, newProfile);
    }
}
