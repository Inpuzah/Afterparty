package com.inpuzah.club.core.display;

import com.inpuzah.club.core.session.ProfileSessionManager;
import com.inpuzah.club.core.session.SessionState;
import com.inpuzah.club.core.settings.DefaultPlayerSettingsService;
import com.inpuzah.club.core.settings.PlayerSettingsService;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** All viewer/Bukkit mutations run on the server thread; workers only mark UUIDs dirty. */
public final class DisplayManager implements Listener, AutoCloseable {
    private final Server server;
    private final ProfileSessionManager sessions;
    private final DefaultPlayerSettingsService settings;
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final Set<UUID> suppressed = new HashSet<>();
    private DisplayConfig config;
    private BukkitTask task;
    private long tick;
    private volatile boolean closed;
    private long lastUpdateNanos;
    private int bossFrame;
    private int titleFrame;

    public DisplayManager(Server server, ProfileSessionManager sessions, DefaultPlayerSettingsService settings,
                          DisplayConfig config) {
        this.server = server;
        this.sessions = sessions;
        this.settings = settings;
        this.config = config;
    }

    public void start(Plugin plugin) {
        if (task != null || closed) {
            throw new IllegalStateException("Display manager already started or closed");
        }
        task = server.getScheduler().runTaskTimer(plugin, this::update, 1, 5);
        server.getOnlinePlayers().forEach(this::attach);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        attach(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        detach(event.getPlayer().getUniqueId());
    }

    public void markDirty(UUID uuid) {
        if (!closed) {
            dirty.add(uuid);
        }
    }

    public DisplayConfig config() {
        return config;
    }

    public long lastUpdateNanos() {
        return lastUpdateNanos;
    }

    /** Main-thread ownership handoff for future games. No game implementation is implied. */
    public void suppress(UUID uuid, boolean value) {
        if (value) {
            suppressed.add(uuid);
            Viewer viewer = viewers.get(uuid);
            if (viewer != null) {
                release(viewer);
            }
        } else {
            suppressed.remove(uuid);
            markDirty(uuid);
        }
    }

    public void replaceConfig(DisplayConfig replacement) {
        viewers.values().forEach(this::release);
        config = replacement;
        tick = 0;
        bossFrame = 0;
        titleFrame = 0;
        viewers.keySet().forEach(this::markDirty);
    }

    public DisplayPreferences current(UUID uuid) {
        return settings.cached(uuid).map(value ->
                new DisplayPreferences(value.bossbarVisible(), value.scoreboardVisible())).orElse(null);
    }

    public boolean busy(UUID uuid) {
        return settings.state(uuid) != PlayerSettingsService.State.READY
                || sessions.get(uuid).filter(session -> session.state() == SessionState.READY).isEmpty();
    }

    public CompletableFuture<DisplayPreferences> toggle(UUID uuid, boolean bossbar, boolean enabled) {
        return (bossbar ? settings.setBossbarVisible(uuid, enabled) : settings.setScoreboardVisible(uuid, enabled))
                .thenApply(value -> new DisplayPreferences(value.bossbarVisible(), value.scoreboardVisible()));
    }

    public void retryPreferences(UUID uuid) {
        settings.get(uuid);
    }

    private void attach(Player player) {
        if (closed) {
            return;
        }
        detach(player.getUniqueId());
        Viewer viewer = new Viewer(player);
        viewers.put(player.getUniqueId(), viewer);
        settings.begin(player.getUniqueId());
        markDirty(player.getUniqueId());
    }

    private void detach(UUID uuid) {
        Viewer removed = viewers.remove(uuid);
        if (removed != null) {
            release(removed);
        }
        dirty.remove(uuid);
        suppressed.remove(uuid);
        settings.end(uuid);
    }

    void update() {
        long started = System.nanoTime();
        Set<UUID> changed = new HashSet<>();
        dirty.removeIf(uuid -> { changed.add(uuid); return true; });
        int online = server.getOnlinePlayers().size();
        bossFrame = AnimationFrames.index(tick, config.rotationTicks(), config.messages().size());
        titleFrame = AnimationFrames.index(tick, config.titleTicks(), config.titles().size());
        for (Viewer viewer : viewers.values()) {
            UUID uuid = viewer.player.getUniqueId();
            boolean changedProfile = changed.contains(uuid);
            render(viewer, online, changedProfile);
        }
        tick += 5;
        lastUpdateNanos = System.nanoTime() - started;
    }

    private void render(Viewer viewer, int online, boolean changed) {
        UUID uuid = viewer.player.getUniqueId();
        var state = settings.state(uuid);
        var preference = settings.cached(uuid).orElse(null);
        if (!config.enabled() || preference == null
                || (state != PlayerSettingsService.State.READY && state != PlayerSettingsService.State.SAVING)
                || suppressed.contains(uuid)) {
            release(viewer);
            return;
        }
        var values = PlaceholderResolver.values(sessions.get(uuid).orElse(null), viewer.player.getName(), online, config.server());
        if (config.bossbarEnabled() && preference.bossbarVisible()) {
            if (viewer.bar == null || changed || tick - viewer.lastBoss >= config.bossbarTicks()) {
                Component title = config.messages().get(bossFrame).render(values);
                if (viewer.bar == null) {
                    viewer.bar = BossBar.bossBar(title, config.progress(), config.color(), config.overlay());
                    viewer.player.showBossBar(viewer.bar);
                } else if (!viewer.bar.name().equals(title)) {
                    viewer.bar.name(title);
                }
                viewer.lastBoss = tick;
            }
        } else if (viewer.bar != null) {
            viewer.player.hideBossBar(viewer.bar);
            viewer.bar = null;
        }

        if (config.scoreboardEnabled() && preference.scoreboardVisible()) {
            if (viewer.board == null) {
                viewer.previous = viewer.player.getScoreboard();
                viewer.board = server.getScoreboardManager().getNewScoreboard();
                viewer.objective = viewer.board.registerNewObjective(
                        "clubcore", server.getScoreboardCriteria("dummy"), Component.empty());
                viewer.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
                viewer.objective.numberFormat(NumberFormat.blank());
                viewer.player.setScoreboard(viewer.board);
                changed = true;
            } else if (viewer.player.getScoreboard() != viewer.board) {
                // Explicit lobby ownership: callers must suppress displays before a game takes over.
                viewer.player.setScoreboard(viewer.board);
            }
            Component title = config.titles().get(titleFrame).render(values);
            if (!title.equals(viewer.objective.displayName())) {
                viewer.objective.displayName(title);
            }
            if (changed || tick - viewer.lastStats >= config.statsTicks()) {
                List<Component> lines = config.lines().stream().map(line -> line.render(values)).toList();
                for (int i = 0; i < lines.size(); i++) {
                    if (i >= viewer.lines.size() || !lines.get(i).equals(viewer.lines.get(i))) {
                        var score = viewer.objective.getScore("clubcore-line-" + i);
                        score.setScore(lines.size() - i);
                        score.customName(lines.get(i));
                    }
                }
                viewer.lines = lines;
                viewer.lastStats = tick;
            }
        } else {
            releaseBoard(viewer);
        }
    }

    private void release(Viewer viewer) {
        if (viewer.bar != null) {
            viewer.player.hideBossBar(viewer.bar);
            viewer.bar = null;
        }
        releaseBoard(viewer);
    }

    private void releaseBoard(Viewer viewer) {
        if (viewer.board != null) {
            if (viewer.player.getScoreboard() == viewer.board) {
                viewer.player.setScoreboard(viewer.previous);
            }
            viewer.objective.unregister();
            viewer.board = null;
            viewer.objective = null;
            viewer.previous = null;
            viewer.lines = List.of();
        }
    }

    @Override
    public void close() {
        closed = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
        viewers.values().forEach(this::release);
        viewers.keySet().forEach(settings::end);
        viewers.clear();
        dirty.clear();
        suppressed.clear();
    }

    private static final class Viewer {
        final Player player;
        BossBar bar;
        Scoreboard previous;
        Scoreboard board;
        Objective objective;
        List<Component> lines = new ArrayList<>();
        long lastStats = -72000;
        long lastBoss = -72000;

        Viewer(Player player) {
            this.player = player;
        }
    }
}
