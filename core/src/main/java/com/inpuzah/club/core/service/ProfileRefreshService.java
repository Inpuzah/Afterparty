package com.inpuzah.club.core.service;

import com.inpuzah.club.core.session.ProfileSessionManager;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** No Bukkit calls: invalidation and immutable snapshot replacement are safe on worker threads. */
public final class ProfileRefreshService implements AutoCloseable {
    private final ProfileService profiles;
    private final ProfileSessionManager sessions;
    private final Duration timeout;
    private final Logger logger;
    private boolean closed;

    public ProfileRefreshService(ProfileService profiles, ProfileSessionManager sessions, Duration timeout, Logger logger) {
        this.profiles = profiles;
        this.sessions = sessions;
        this.timeout = timeout;
        this.logger = logger;
    }

    public synchronized CompletableFuture<Void> refresh(UUID uuid) {
        if (closed) {
            return CompletableFuture.completedFuture(null);
        }
        var refresh = sessions.beginRefresh(uuid);
        if (refresh.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        var session = refresh.get();
        return profiles.findById(uuid).copy().orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle((profile, error) -> {
                    if (error != null || profile.isEmpty()) {
                        if (sessions.markUnavailable(uuid, session.token(), session.revision())) {
                            logger.warn("Could not refresh profile {} (session {}, revision {}); cache unavailable",
                                    uuid, session.token(), session.revision(), error);
                        }
                    } else {
                        sessions.markReady(uuid, session.token(), session.revision(), profile.get());
                    }
                    return null;
                });
    }

    @Override
    public synchronized void close() {
        closed = true;
    }
}
