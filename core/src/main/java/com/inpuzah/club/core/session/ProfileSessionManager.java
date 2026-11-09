package com.inpuzah.club.core.session;

import com.inpuzah.club.core.model.PlayerProfile;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks profile state for online players, keyed by UUID. Transitions are atomic and token-checked, so a result
 * that arrives after the player quit (or rejoined) is ignored.
 */
public final class ProfileSessionManager {

    private final ConcurrentHashMap<UUID, ProfileSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong nextToken = new AtomicLong();

    /** Starts a new LOADING session, replacing any previous session for the player. */
    public ProfileSession begin(UUID uuid) {
        ProfileSession session = new ProfileSession(uuid, nextToken.incrementAndGet(), SessionState.LOADING, null);
        sessions.put(uuid, session);
        return session;
    }

    /** @return {@code true} if the session was still current and LOADING */
    public boolean markReady(UUID uuid, long token, PlayerProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (!profile.uuid().equals(uuid)) {
            throw new IllegalArgumentException("profile UUID does not match session UUID");
        }
        return transition(uuid, token, SessionState.READY, profile);
    }

    /** @return {@code true} if the session was still current and LOADING */
    public boolean markUnavailable(UUID uuid, long token) {
        return transition(uuid, token, SessionState.UNAVAILABLE, null);
    }

    public Optional<ProfileSession> get(UUID uuid) {
        return Optional.ofNullable(sessions.get(uuid));
    }

    /** Removes the session; returns its final snapshot in the CLOSED state. */
    public Optional<ProfileSession> close(UUID uuid) {
        return Optional.ofNullable(sessions.remove(uuid))
                .map(session -> session.withState(SessionState.CLOSED, null));
    }

    public List<ProfileSession> closeAll() {
        List<ProfileSession> closed = sessions.keySet().stream()
                .map(this::close)
                .flatMap(Optional::stream)
                .toList();
        sessions.clear();
        return closed;
    }

    private boolean transition(UUID uuid, long token, SessionState target, PlayerProfile profile) {
        boolean[] applied = {false};
        sessions.computeIfPresent(uuid, (key, current) -> {
            if (current.token() != token || current.state() != SessionState.LOADING) {
                return current;
            }
            applied[0] = true;
            return current.withState(target, profile);
        });
        return applied[0];
    }
}
