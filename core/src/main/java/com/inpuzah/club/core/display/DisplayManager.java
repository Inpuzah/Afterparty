package com.inpuzah.club.core.display;

import com.inpuzah.club.core.session.ProfileSessionManager;
import com.inpuzah.club.core.session.SessionState;
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
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/** All viewer/Bukkit mutations run on the server thread; workers only enqueue completions and UUIDs. */
public final class DisplayManager implements Listener, AutoCloseable {
    private final Server server;
    private final ProfileSessionManager sessions;
    private final DisplayPreferenceService preferences;
    private final Logger logger;
    private final long timeoutMillis;
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Runnable> completions = new ConcurrentLinkedQueue<>();
    private final Set<UUID> suppressed = new HashSet<>();
    private DisplayConfig config;
    private BukkitTask task;
    private long tick;
    private long nextToken;
    private volatile boolean closed;
    private long lastUpdateNanos;
    private int bossFrame;
    private int titleFrame;

    public DisplayManager(Server server, ProfileSessionManager sessions, DisplayPreferenceService preferences,
                          DisplayConfig config, Logger logger, long timeoutMillis) {
        this.server = server;
        this.sessions = sessions;
        this.preferences = preferences;
        this.config = config;
        this.logger = logger;
        this.timeoutMillis = timeoutMillis;
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
        Viewer viewer = viewers.get(uuid);
        return viewer == null ? null : viewer.preferences;
    }

    public boolean busy(UUID uuid) {
        Viewer viewer = viewers.get(uuid);
        return viewer == null || viewer.saving || viewer.loading || viewer.preferences == null
                || sessions.get(uuid).filter(session -> session.state() == SessionState.READY).isEmpty();
    }

    public CompletableFuture<DisplayPreferences> toggle(UUID uuid, boolean bossbar, boolean enabled) {
        Viewer viewer = viewers.get(uuid);
        if (viewer == null || busy(uuid)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Display preferences are loading or unavailable."));
        }
        viewer.saving = true;
        long token = viewer.token;
        CompletableFuture<DisplayPreferences> saved = preferences.save(uuid, bossbar, enabled);
        // Observe the original request, not the command's timeout copy: a late successful save still updates UI.
        saved.whenComplete((value, error) -> enqueue(() -> {
            Viewer current = viewer(uuid, token);
            if (current == null) {
                return;
            }
            current.saving = false;
            if (error == null) {
                current.preferences = value;
                markDirty(uuid);
                render(current, server.getOnlinePlayers().size(), true);
            } else {
                current.preferences = null;
                release(current);
                logger.warn("Display preference save outcome uncertain for {}; reload preferences before another toggle", uuid, error);
                load(current);
            }
        }));
        return saved.copy();
    }

    public void retryPreferences(UUID uuid) {
        Viewer viewer = viewers.get(uuid);
        if (viewer != null && !viewer.loading && !viewer.saving && viewer.preferences == null) {
            load(viewer);
        }
    }

    private void attach(Player player) {
        if (closed) {
            return;
        }
        detach(player.getUniqueId());
        Viewer viewer = new Viewer(player, ++nextToken);
        viewers.put(player.getUniqueId(), viewer);
        markDirty(player.getUniqueId());
    }

    private void detach(UUID uuid) {
        Viewer removed = viewers.remove(uuid);
        if (removed != null) {
            release(removed);
        }
        dirty.remove(uuid);
        suppressed.remove(uuid);
    }

    private Viewer viewer(UUID uuid, long token) {
        Viewer current = viewers.get(uuid);
        return current != null && current.token == token ? current : null;
    }

    private void load(Viewer viewer) {
        viewer.loading = true;
        UUID uuid = viewer.player.getUniqueId();
        long token = viewer.token;
        preferences.load(uuid).orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).whenComplete((value, error) ->
                enqueue(() -> {
                    Viewer current = viewer(uuid, token);
                    if (current == null) {
                        return;
                    }
                    current.loading = false;
                    if (error != null) {
                        logger.warn("Display preferences unavailable for {}; use /display status to retry", uuid, error);
                    } else {
                        current.preferences = value;
                        markDirty(uuid);
                    }
                }));
    }

    private void enqueue(Runnable completion) {
        if (!closed) {
            completions.add(completion);
            if (closed) {
                completions.clear();
            }
        }
    }

    void update() {
        long started = System.nanoTime();
        for (Runnable completion; (completion = completions.poll()) != null;) {
            completion.run();
        }
        Set<UUID> changed = new HashSet<>();
        dirty.removeIf(uuid -> { changed.add(uuid); return true; });
        int online = server.getOnlinePlayers().size();
        bossFrame = AnimationFrames.index(tick, config.rotationTicks(), config.messages().size());
        titleFrame = AnimationFrames.index(tick, config.titleTicks(), config.titles().size());
        for (Viewer viewer : viewers.values()) {
            UUID uuid = viewer.player.getUniqueId();
            boolean changedProfile = changed.contains(uuid);
            if (viewer.preferences == null && !viewer.loading && !viewer.loadAttempted) {
                viewer.loadAttempted = true;
                load(viewer);
            }
            render(viewer, online, changedProfile);
        }
        tick += 5;
        lastUpdateNanos = System.nanoTime() - started;
    }

    private void render(Viewer viewer, int online, boolean changed) {
        UUID uuid = viewer.player.getUniqueId();
        if (!config.enabled() || viewer.preferences == null || suppressed.contains(uuid)) {
            release(viewer);
            return;
        }
        var values = PlaceholderResolver.values(sessions.get(uuid).orElse(null), viewer.player.getName(), online, config.server());
        if (config.bossbarEnabled() && viewer.preferences.bossbar()) {
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

        if (config.scoreboardEnabled() && viewer.preferences.scoreboard()) {
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
        viewers.clear();
        dirty.clear();
        suppressed.clear();
        completions.clear();
    }

    private static final class Viewer {
        final Player player;
        final long token;
        DisplayPreferences preferences;
        boolean loading;
        boolean loadAttempted;
        boolean saving;
        BossBar bar;
        Scoreboard previous;
        Scoreboard board;
        Objective objective;
        List<Component> lines = new ArrayList<>();
        long lastStats = -72000;
        long lastBoss = -72000;

        Viewer(Player player, long token) {
            this.player = player;
            this.token = token;
        }
    }
}
