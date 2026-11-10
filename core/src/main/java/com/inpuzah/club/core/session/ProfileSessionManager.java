package com.inpuzah.club.core.session;

import com.inpuzah.club.core.model.PlayerProfile;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Tracks profile state for online players, keyed by UUID. Transitions are atomic and token-checked, so a result
 * that arrives after the player quit (or rejoined) is ignored.
 */
public final class ProfileSessionManager {

    private final ConcurrentHashMap<UUID, ProfileSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong nextToken = new AtomicLong();
    private volatile Consumer<UUID> observer = uuid -> {};

    public void observe(Consumer<UUID> observer) {
        this.observer = Objects.requireNonNull(observer);
    }

    /** Starts a new LOADING session, replacing any previous session for the player. */
    public ProfileSession begin(UUID uuid) {
        ProfileSession session = new ProfileSession(uuid, nextToken.incrementAndGet(), SessionState.LOADING, null);
        sessions.put(uuid, session);
        observer.accept(uuid);
        return session;
    }

    /** @return {@code true} if the session was still current and LOADING */
    public boolean markReady(UUID uuid, long token, PlayerProfile profile) {
        return markReady(uuid, token, 0, profile);
    }

    public boolean markReady(UUID uuid, long token, long revision, PlayerProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (!profile.uuid().equals(uuid)) {
            throw new IllegalArgumentException("profile UUID does not match session UUID");
        }
        return transition(uuid, token, revision, SessionState.READY, profile);
    }

    /** @return {@code true} if the session was still current and LOADING */
    public boolean markUnavailable(UUID uuid, long token) {
        return markUnavailable(uuid, token, 0);
    }

    public boolean markUnavailable(UUID uuid, long token, long revision) {
        return transition(uuid, token, revision, SessionState.UNAVAILABLE, null);
    }

    /** Invalidates cached data and any older read without replacing the login identity. */
    public Optional<ProfileSession> beginRefresh(UUID uuid) {
        var refresh = Optional.ofNullable(sessions.computeIfPresent(uuid, (key, current) ->
                new ProfileSession(uuid, current.token(), Math.incrementExact(current.revision()),
                        SessionState.LOADING, null)));
        if (refresh.isPresent()) {
            observer.accept(uuid);
        }
        return refresh;
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

    private boolean transition(UUID uuid, long token, long revision, SessionState target, PlayerProfile profile) {
        boolean[] applied = {false};
        sessions.computeIfPresent(uuid, (key, current) -> {
            if (current.token() != token || current.revision() != revision || current.state() != SessionState.LOADING) {
                return current;
            }
            applied[0] = true;
            return current.withState(target, profile);
        });
        if (applied[0]) {
            observer.accept(uuid);
        }
        return applied[0];
    }
}
