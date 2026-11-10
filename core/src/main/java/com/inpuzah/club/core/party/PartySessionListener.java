package com.inpuzah.club.core.party;

import com.inpuzah.club.core.session.ProfileSessionManager;
import com.inpuzah.club.core.session.SessionState;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/** One server-thread sweep, not a scheduled task per departure. */
public final class PartySessionListener implements Listener, AutoCloseable {
    private record Login(Player player, long token) {}
    private final Map<UUID, Login> logins = new HashMap<>();
    private final Server server;
    private final ProfileSessionManager profiles;
    private final PartyDisconnects disconnects;
    private final BooleanSupplier enabled;
    private BukkitTask task;
    private long nextToken;
    private boolean closed;
    private Runnable unavailable = () -> {};

    public PartySessionListener(Server server, ProfileSessionManager profiles, PartyDisconnects disconnects,
                                BooleanSupplier enabled) {
        this.server = server;
        this.profiles = profiles;
        this.disconnects = disconnects;
        this.enabled = enabled;
    }

    public void start(JavaPlugin plugin) {
        if (closed || task != null) { throw new IllegalStateException("Party session listener already started or closed"); }
        server.getOnlinePlayers().forEach(this::begin);
        task = server.getScheduler().runTaskTimer(plugin, this::sweep, 20, 20);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        begin(event.getPlayer());
    }

    public void begin(Player player) {
        if (closed) { return; }
        var old = logins.get(player.getUniqueId());
        if (old != null && old.player == player) { return; }
        var login = new Login(player, ++nextToken);
        logins.put(player.getUniqueId(), login);
        disconnects.joined(player.getUniqueId(), login.token);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        var player = event.getPlayer();
        var login = logins.get(player.getUniqueId());
        if (!closed && login != null && login.player == player) {
            logins.remove(player.getUniqueId(), login);
            disconnects.quit(player.getUniqueId(), login.token);
        }
    }

    public boolean admitted(UUID player) {
        return !closed && enabled.getAsBoolean() && disconnects.admitted(player);
    }

    public void onUnavailable(Runnable unavailable) {
        this.unavailable = java.util.Objects.requireNonNull(unavailable);
    }

    public void sweep() {
        if (closed) { return; }
        if (!enabled.getAsBoolean()) {
            close();
            unavailable.run();
            return;
        }
        disconnects.sweep(server.getOnlinePlayers().stream().map(Player::getUniqueId)
                .filter(this::admitted)
                .filter(id -> profiles.get(id).filter(value -> value.state() == SessionState.READY).isPresent())
                .collect(Collectors.toUnmodifiableSet()));
    }

    @Override public void close() {
        closed = true;
        if (task != null) { task.cancel(); task = null; }
        disconnects.close();
        logins.clear();
    }
}
