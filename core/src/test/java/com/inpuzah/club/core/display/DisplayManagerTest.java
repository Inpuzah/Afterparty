package com.inpuzah.club.core.display;

import com.inpuzah.club.core.command.CommandFeedback;
import com.inpuzah.club.core.command.DisplayCommand;
import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.session.ProfileSessionManager;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayManagerTest {
    private final Map<UUID, DisplayPreferences> stored = new HashMap<>();
    private final List<Person> people = new ArrayList<>();
    private final List<Board> boards = new ArrayList<>();
    private final ProfileSessionManager sessions = new ProfileSessionManager();
    private final List<String> messages = new ArrayList<>();
    private final Set<String> permissions = new HashSet<>(Set.of("club.display", "club.admin.displays"));
    private int scheduled;
    private int cancelled;
    private int loads;
    private int saves;
    private boolean failSave;
    private boolean failLoad;
    private CountDownLatch saveGate;
    private final DisplayPreferenceService preferences = new DisplayPreferenceService(new DisplayPreferenceRepository() {
        @Override public DisplayPreferences load(UUID uuid) {
            loads++;
            if (failLoad) {
                throw new IllegalStateException("simulated outage");
            }
            return stored.getOrDefault(uuid, DisplayPreferences.DEFAULT);
        }
        @Override public DisplayPreferences save(UUID uuid, boolean bossbar, boolean enabled) {
            if (saveGate != null) {
                try {
                    if (!saveGate.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test gate timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            saves++;
            if (failSave) {
                throw new IllegalStateException("simulated outage");
            }
            var old = stored.getOrDefault(uuid, DisplayPreferences.DEFAULT);
            var next = bossbar ? new DisplayPreferences(enabled, old.scoreboard())
                    : new DisplayPreferences(old.bossbar(), enabled);
            stored.put(uuid, next);
            return next;
        }
    });
    private final BukkitTask task = proxy(BukkitTask.class, (method, args) -> {
        if (method.equals("cancel")) { cancelled++; return null; }
        throw new AssertionError(method);
    });
    private final BukkitScheduler scheduler = proxy(BukkitScheduler.class, (method, args) -> {
        if (method.equals("runTaskTimer")) {
            scheduled++;
            assertEquals(5L, args[3]);
            return task;
        }
        throw new AssertionError(method);
    });
    private final ScoreboardManager scoreboards = proxy(ScoreboardManager.class, (method, args) -> {
        if (method.equals("getNewScoreboard")) {
            Board board = new Board();
            boards.add(board);
            return board.api;
        }
        throw new AssertionError(method);
    });
    private final Server server = proxy(Server.class, (method, args) -> switch (method) {
        case "getOnlinePlayers" -> people.stream().map(person -> person.api).toList();
        case "getScoreboardManager" -> scoreboards;
        case "getScheduler" -> scheduler;
        case "getScoreboardCriteria" -> null;
        case "getPlayer" -> people.stream().filter(person -> person.id.equals(args[0])).map(person -> person.api).findFirst().orElse(null);
        default -> throw new AssertionError(method);
    });
    private DisplayManager manager;
    private DisplayReloadService reloads;

    @AfterEach
    void stop() throws Exception {
        if (saveGate != null) { saveGate.countDown(); }
        if (manager != null) { manager.close(); }
        if (reloads != null) { reloads.shutdown(Duration.ofSeconds(5)); }
        preferences.shutdown(Duration.ofSeconds(5));
    }

    @Test
    void sharedTaskRendersViewerSpecificStatsAndDiffsWithoutRecreatingResources() throws Exception {
        Person first = add("First", 100);
        Person second = add("Second", 250);
        start();
        ready();
        assertEquals(1, scheduled);
        assertEquals(2, boards.size());
        assertEquals(1, first.bars.size());
        assertNotSame(first.current, second.current);
        Board firstBoard = boards.stream().filter(board -> board.api == first.current).findFirst().orElseThrow();
        Board secondBoard = boards.stream().filter(board -> board.api == second.current).findFirst().orElseThrow();
        assertTrue(firstBoard.texts().contains("Coins: 100"));
        assertTrue(secondBoard.texts().contains("Coins: 250"));
        int writes = firstBoard.writes;
        for (int i = 0; i < 10; i++) { manager.update(); }
        assertEquals(writes, firstBoard.writes);
        assertEquals(2, boards.size());
        assertEquals(2, loads);
        assertTrue(manager.lastUpdateNanos() > 0);

        long token = sessions.get(first.id).orElseThrow().token();
        var refresh = sessions.beginRefresh(first.id).orElseThrow();
        manager.update();
        assertTrue(firstBoard.texts().contains("Coins: Loading..."));
        sessions.markReady(first.id, token, refresh.revision(), profile(first.id, first.name, 900));
        manager.update();
        assertTrue(firstBoard.texts().contains("Coins: 900"));
        assertTrue(secondBoard.texts().contains("Coins: 250"));
    }

    @Test
    void duplicateAndBlankRowsHaveStableUniqueScoreIdentities() throws Exception {
        Person player = add("Player", 0);
        var yaml = DisplayConfigTest.defaults();
        yaml.set("displays.scoreboard.lines", List.of("", " ", "same", "same"));
        start(DisplayConfig.load(yaml));
        ready();
        assertEquals(List.of("", " ", "same", "same"), boards.getFirst().texts());
        assertEquals(4, boards.getFirst().scores.size());
        assertEquals(List.of(4, 3, 2, 1), boards.getFirst().scores.values().stream()
                .sorted(java.util.Comparator.comparingInt((Row row) -> row.score).reversed()).map(row -> row.score).toList());
        assertSame(boards.getFirst().api, player.current);
    }

    @Test
    void togglesPersistAndRemoveImmediatelyAfterSaveWithoutTouchingOtherDisplay() throws Exception {
        Person player = add("Player", 0);
        start();
        ready();
        manager.toggle(player.id, true, false).get(5, TimeUnit.SECONDS);
        manager.update();
        assertTrue(player.bars.isEmpty());
        assertSame(boards.getFirst().api, player.current);
        manager.toggle(player.id, false, false).get(5, TimeUnit.SECONDS);
        manager.update();
        assertSame(player.original.api, player.current);
        assertEquals(new DisplayPreferences(false, false), stored.get(player.id));

        quit(player);
        join(player);
        ready();
        assertTrue(player.bars.isEmpty());
        assertSame(player.original.api, player.current);
        assertEquals(new DisplayPreferences(false, false), manager.current(player.id));
    }

    @Test
    void lobbyOwnsSidebarUntilExplicitSuppressionAndRestoresPriorBoardSafely() throws Exception {
        Person player = add("Player", 0);
        start();
        ready();
        Board lobby = boards.getFirst();
        Board foreign = new Board();
        player.current = foreign.api;
        manager.update();
        assertSame(lobby.api, player.current);
        manager.suppress(player.id, true);
        assertSame(player.original.api, player.current);
        assertTrue(player.bars.isEmpty());
        player.current = foreign.api;
        manager.update();
        assertSame(foreign.api, player.current);
        manager.suppress(player.id, false);
        manager.update();
        assertNotSame(foreign.api, player.current);
        manager.close();
        assertSame(foreign.api, player.current);
        assertTrue(player.bars.isEmpty());
    }

    @Test
    void twentyJoinQuitCyclesReleaseBarsBoardsAndSingleTask() throws Exception {
        Person player = add("Player", 0);
        start();
        ready();
        for (int i = 0; i < 20; i++) {
            quit(player);
            assertTrue(player.bars.isEmpty());
            assertSame(player.original.api, player.current);
            assertNull(manager.current(player.id));
            join(player);
            ready();
            assertEquals(1, player.bars.size());
        }
        manager.close();
        assertEquals(1, cancelled);
        assertTrue(player.bars.isEmpty());
        assertTrue(boards.stream().allMatch(board -> board.unregistered));
        assertSame(player.original.api, player.current);
    }

    @Test
    void loadingAndUnavailableProfilesRenderSafeFallbackAndCannotSaveBeforeReady() throws Exception {
        Person player = add("Player", 0);
        long token = sessions.begin(player.id).token();
        start();
        ready();
        assertTrue(boards.getFirst().texts().contains("Coins: Loading..."));
        assertTrue(manager.busy(player.id));
        sessions.markUnavailable(player.id, token);
        manager.update();
        assertTrue(boards.getFirst().texts().contains("Coins: Unavailable"));
        assertEquals(0, saves);
    }

    @Test
    void hundredViewersUseOneTaskAndNoDatabaseReadsDuringAnimation() throws Exception {
        for (int i = 0; i < 100; i++) { add("Player" + i, i); }
        start();
        ready();
        assertEquals(100, boards.size());
        int initialLoads = loads;
        for (int i = 0; i < 40; i++) { manager.update(); }
        assertEquals(initialLoads, loads);
        assertEquals(1, scheduled);
        assertEquals(100, boards.size());
        assertTrue(people.stream().allMatch(person -> person.bars.size() == 1));
    }

    @Test
    void preferencesStayHiddenUntilLoadedAndOutageDoesNotResetSavedFlags() throws Exception {
        Person player = add("Player", 0);
        failLoad = true;
        start();
        manager.update();
        // Barrier after the queued failing load.
        try { preferences.load(player.id).get(5, TimeUnit.SECONDS); } catch (java.util.concurrent.ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
        manager.update();
        assertNull(manager.current(player.id));
        assertTrue(player.bars.isEmpty());
        assertSame(player.original.api, player.current);
        failLoad = false;
        stored.put(player.id, new DisplayPreferences(false, true));
        manager.retryPreferences(player.id);
        ready();
        assertEquals(new DisplayPreferences(false, true), manager.current(player.id));
        assertTrue(player.bars.isEmpty());
    }

    @Test
    void oldPreferenceCompletionCannotAttachToRejoinedViewer() throws Exception {
        Person player = add("Player", 0);
        start();
        ready();
        saveGate = new CountDownLatch(1);
        var save = manager.toggle(player.id, true, false);
        quit(player);
        join(player);
        manager.update();
        saveGate.countDown();
        save.get(5, TimeUnit.SECONDS);
        ready();
        assertEquals(new DisplayPreferences(false, true), manager.current(player.id));
        assertTrue(player.bars.isEmpty());
        assertFalse(manager.busy(player.id));
    }

    @Test
    void failedSaveDoesNotClaimSuccessAndReloadsAuthoritativePreference() throws Exception {
        Person player = add("Player", 0);
        start();
        ready();
        failSave = true;
        try { manager.toggle(player.id, true, false).get(5, TimeUnit.SECONDS); } catch (java.util.concurrent.ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
        manager.update();
        ready();
        assertEquals(DisplayPreferences.DEFAULT, manager.current(player.id));
        assertEquals(1, player.bars.size());
        assertEquals(1, saves);
    }

    @Test
    void atomicReloadRejectsInvalidConfigAndAppliesValidDisabledSettings(@TempDir Path directory) throws Exception {
        Person player = add("Player", 0);
        start();
        ready();
        Path file = directory.resolve("config.yml");
        var yaml = DisplayConfigTest.defaults();
        yaml.set("displays.bossbar.progress", 2);
        Files.writeString(file, yaml.saveToString());
        reloads = new DisplayReloadService(file.toFile(), () -> getClass().getResourceAsStream("/config.yml"));
        var old = manager.config();
        try { reloads.load().get(5, TimeUnit.SECONDS); } catch (java.util.concurrent.ExecutionException expected) {
            assertTrue(expected.getCause() instanceof com.inpuzah.club.core.config.ConfigException);
        }
        assertSame(old, manager.config());
        assertEquals(1, player.bars.size());
        yaml.set("displays.bossbar.progress", 1.0);
        yaml.set("displays.enabled", false);
        Files.writeString(file, yaml.saveToString());
        manager.replaceConfig(reloads.load().get(5, TimeUnit.SECONDS));
        manager.update();
        assertTrue(player.bars.isEmpty());
        assertSame(player.original.api, player.current);
        manager.replaceConfig(old);
        manager.update();
        assertEquals(1, player.bars.size());
    }

    @Test
    void displayCommandsEnforcePermissionsConsoleSyntaxAndSaveConfirmation(@TempDir Path directory) throws Exception {
        Person player = add("Player", 0);
        start();
        ready();
        Path file = directory.resolve("config.yml");
        Files.writeString(file, DisplayConfigTest.defaults().saveToString());
        reloads = new DisplayReloadService(file.toFile(), () -> getClass().getResourceAsStream("/config.yml"));
        var feedback = new CommandFeedback(server, sessions, Runnable::run);
        var command = new DisplayCommand(manager, reloads, feedback, LoggerFactory.getLogger(getClass()), 1000);
        CommandSender console = proxy(CommandSender.class, this::sender);
        command.onCommand(console, null, "", new String[]{"status"});
        assertTrue(messages.getLast().contains("player"));
        permissions.clear();
        command.onCommand(player.api, null, "", new String[]{"bossbar", "off"});
        command.admin(console, new String[]{"displays", "reload"});
        assertTrue(messages.getLast().contains("permission"));
        assertEquals(0, saves);
        permissions.add("club.display");
        command.onCommand(player.api, null, "", new String[]{"scoreboard", "wrong"});
        assertTrue(messages.getLast().contains("Usage"));
        command.onCommand(player.api, null, "", new String[]{"status"});
        assertTrue(messages.getLast().contains("bossbar on"));
        command.onCommand(player.api, null, "", new String[]{"bossbar", "off"});
        ready();
        assertEquals(new DisplayPreferences(false, true), stored.get(player.id));
        assertEquals(List.of("off"), command.onTabComplete(player.api, null, "", new String[]{"bossbar", "of"}));
    }

    @Test
    void reloadStillAppliesWhenAdminDisconnectsAndDoesNotRemainBusy(@TempDir Path directory) throws Exception {
        Person player = add("Admin", 0);
        start();
        ready();
        Path file = directory.resolve("config.yml");
        var yaml = DisplayConfigTest.defaults();
        yaml.set("displays.enabled", false);
        Files.writeString(file, yaml.saveToString());
        reloads = new DisplayReloadService(file.toFile(), () -> getClass().getResourceAsStream("/config.yml"));
        var actions = new ConcurrentLinkedQueue<Runnable>();
        var feedback = new CommandFeedback(server, sessions, actions::add);
        var command = new DisplayCommand(manager, reloads, feedback, LoggerFactory.getLogger(getClass()), 1000);
        command.admin(player.api, new String[]{"displays", "reload"});
        quit(player);
        reloads.load().get(5, TimeUnit.SECONDS);
        while (!actions.isEmpty()) { actions.remove().run(); }
        assertFalse(manager.config().enabled());
        assertTrue(messages.isEmpty());

        yaml.set("displays.bossbar.progress", 2);
        Files.writeString(file, yaml.saveToString());
        var before = manager.config();
        CommandSender console = proxy(CommandSender.class, this::sender);
        command.admin(console, new String[]{"displays", "reload"});
        try { reloads.load().get(5, TimeUnit.SECONDS); } catch (java.util.concurrent.ExecutionException expected) {
            assertTrue(expected.getCause() instanceof com.inpuzah.club.core.config.ConfigException);
        }
        while (!actions.isEmpty()) { actions.remove().run(); }
        assertSame(before, manager.config());
        assertTrue(messages.getLast().contains("previous settings retained"));
    }

    @Test
    void timedOutToggleStillUpdatesUiWhenOriginalSaveEventuallyCommits() throws Exception {
        Person player = add("Player", 0);
        start();
        ready();
        saveGate = new CountDownLatch(1);
        var caller = manager.toggle(player.id, true, false).orTimeout(1, TimeUnit.MILLISECONDS);
        try { caller.get(5, TimeUnit.SECONDS); } catch (java.util.concurrent.ExecutionException expected) {
            assertTrue(expected.getCause() instanceof java.util.concurrent.TimeoutException);
        }
        assertTrue(manager.busy(player.id));
        saveGate.countDown();
        ready();
        assertEquals(new DisplayPreferences(false, true), manager.current(player.id));
        assertTrue(player.bars.isEmpty());
    }

    private void start() throws Exception { start(DisplayConfig.load(DisplayConfigTest.defaults())); }

    private void start(DisplayConfig config) {
        manager = new DisplayManager(server, sessions, preferences, config, LoggerFactory.getLogger(getClass()), 1000);
        sessions.observe(manager::markDirty);
        manager.start(proxy(Plugin.class, (method, args) -> { throw new AssertionError(method); }));
    }

    private void ready() throws Exception {
        manager.update();
        preferences.load(UUID.randomUUID()).get(5, TimeUnit.SECONDS);
        // The extra barrier call isn't a viewer load.
        loads--;
        manager.update();
    }

    private Person add(String name, long coins) {
        Person person = new Person(name);
        people.add(person);
        sessions.markReady(person.id, sessions.begin(person.id).token(), profile(person.id, name, coins));
        return person;
    }

    private void quit(Person person) {
        sessions.close(person.id);
        people.remove(person);
        manager.onQuit(new PlayerQuitEvent(person.api, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
    }

    private void join(Person person) {
        people.add(person);
        sessions.markReady(person.id, sessions.begin(person.id).token(), profile(person.id, person.name, 0));
        manager.onJoin(new PlayerJoinEvent(person.api, Component.empty()));
    }

    private static PlayerProfile profile(UUID id, String name, long coins) {
        return new PlayerProfile(id, name, Instant.EPOCH, Instant.EPOCH, 1, 0, coins);
    }

    private Object sender(String method, Object[] args) {
        return switch (method) {
            case "hasPermission" -> permissions.contains(args[0]);
            case "sendMessage" -> { messages.add(plain((Component) args[0])); yield null; }
            default -> throw new AssertionError(method);
        };
    }

    private final class Person {
        final UUID id = UUID.randomUUID();
        final String name;
        final Board original = new Board();
        Scoreboard current = original.api;
        final Set<BossBar> bars = new HashSet<>();
        final Player api;
        Person(String name) {
            this.name = name;
            api = proxy(Player.class, (method, args) -> switch (method) {
                case "getUniqueId" -> id;
                case "getName" -> name;
                case "getScoreboard" -> current;
                case "setScoreboard" -> { current = (Scoreboard) args[0]; yield null; }
                case "showBossBar" -> { bars.add((BossBar) args[0]); yield null; }
                case "hideBossBar" -> { bars.remove((BossBar) args[0]); yield null; }
                default -> sender(method, args);
            });
        }
    }

    private static final class Row {
        int score;
        Component text;
        final Score api;
        Row(Board board) {
            api = proxy(Score.class, (method, args) -> switch (method) {
                case "setScore" -> { score = (Integer) args[0]; board.writes++; yield null; }
                case "customName" -> { text = (Component) args[0]; yield null; }
                default -> throw new AssertionError(method);
            });
        }
    }

    private static final class Board {
        final Map<String, Row> scores = new HashMap<>();
        Component title = Component.empty();
        boolean unregistered;
        int writes;
        final Objective objective = proxy(Objective.class, (method, args) -> switch (method) {
            case "setDisplaySlot", "numberFormat" -> null;
            case "unregister" -> { unregistered = true; yield null; }
            case "displayName" -> {
                if (args == null || args.length == 0) { yield title; }
                title = (Component) args[0]; yield null;
            }
            case "getScore" -> scores.computeIfAbsent((String) args[0], key -> new Row(this)).api;
            default -> throw new AssertionError(method);
        });
        final Scoreboard api = proxy(Scoreboard.class, (method, args) -> {
            if (method.equals("registerNewObjective")) { return objective; }
            throw new AssertionError(method);
        });
        List<String> texts() {
            return scores.values().stream().sorted(java.util.Comparator.comparingInt((Row row) -> row.score).reversed())
                    .map(row -> plain(row.text)).toList();
        }
    }

    private static String plain(Component text) { return PlainTextComponentSerializer.plainText().serialize(text); }

    @FunctionalInterface
    private interface Call { Object invoke(String method, Object[] args); }

    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> type.getSimpleName() + " double";
                    default -> throw new AssertionError(method);
                };
            }
            return call.invoke(method.getName(), args);
        }));
    }
}
