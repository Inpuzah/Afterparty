package com.inpuzah.club.core.listener;

import com.inpuzah.club.core.config.ProfileConfig;
import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.repository.ProfileStorageException;
import com.inpuzah.club.core.service.ProfileService;
import com.inpuzah.club.core.service.ProfileServiceClosedException;
import com.inpuzah.club.core.session.ProfileSession;
import com.inpuzah.club.core.session.ProfileSessionManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Drives the profile session lifecycle. Event handlers run on the server thread; database work is delegated to
 * {@link ProfileService} and results are handed back through {@code mainThread}.
 */
public final class PlayerSessionListener implements Listener {

    static final Component UNAVAILABLE_MESSAGE =
            Component.text("Profiles are temporarily unavailable.", NamedTextColor.RED);
    static final Component READY_MESSAGE =
            Component.text("Your profile is ready.", NamedTextColor.GRAY);

    private final Server server;
    private final Logger logger;
    private final ProfileService profiles;
    private final ProfileSessionManager sessions;
    private final ProfileConfig config;
    private final Executor mainThread;

    public PlayerSessionListener(Server server, Logger logger, ProfileService profiles,
                                 ProfileSessionManager sessions, ProfileConfig config, Executor mainThread) {
        this.server = server;
        this.logger = logger;
        this.profiles = profiles;
        this.sessions = sessions;
        this.config = config;
        this.mainThread = mainThread;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        startSession(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        endSession(event.getPlayer().getUniqueId());
    }

    /** Must be called on the server thread. */
    public void startSession(Player player) {
        UUID uuid = player.getUniqueId();
        String username = player.getName();
        ProfileSession session = sessions.begin(uuid);

        profiles.getOrCreate(uuid, username)
                .orTimeout(config.loadTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .whenCompleteAsync((profile, error) -> {
                    if (error == null) {
                        onLoaded(session, profile);
                    } else {
                        onLoadFailed(session, username, unwrap(error));
                    }
                }, mainThread);
    }

    /** Must be called on the server thread. */
    public void endSession(UUID uuid) {
        sessions.close(uuid).ifPresent(closed -> profiles.touchLastSeen(uuid).exceptionally(error -> {
            logger.warn("Could not record last-seen time for {}: {}", uuid, describe(unwrap(error)));
            return null;
        }));
    }

    private void onLoaded(ProfileSession session, PlayerProfile profile) {
        if (!sessions.markReady(session.uuid(), session.token(), profile)) {
            logger.debug("Dropped stale profile result for {} (session {})", session.uuid(), session.token());
            return;
        }
        if (config.showReadyMessage()) {
            Player player = server.getPlayer(session.uuid());
            if (player != null) {
                player.sendMessage(READY_MESSAGE);
            }
        }
    }

    private void onLoadFailed(ProfileSession session, String username, Throwable error) {
        if (!sessions.markUnavailable(session.uuid(), session.token())) {
            return;
        }
        switch (error) {
            case TimeoutException _ -> logger.warn("Profile load for {} ({}) timed out after {} ms",
                    username, session.uuid(), config.loadTimeout().toMillis());
            case ProfileStorageException e -> logger.error("Profile load for {} ({}) failed: database unavailable",
                    username, session.uuid(), e);
            case ProfileServiceClosedException e -> logger.warn("Profile load for {} ({}) rejected: {}",
                    username, session.uuid(), e.getMessage());
            default -> logger.error("Unexpected error loading profile for {} ({})", username, session.uuid(), error);
        }
        Player player = server.getPlayer(session.uuid());
        if (player != null) {
            player.sendMessage(UNAVAILABLE_MESSAGE);
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private static String describe(Throwable error) {
        return error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
    }
}
