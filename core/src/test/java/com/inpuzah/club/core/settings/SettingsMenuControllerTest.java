package com.inpuzah.club.core.settings;

import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.display.DisplayPreferenceRepository;
import com.inpuzah.club.core.display.DisplayPreferenceService;
import com.inpuzah.club.core.display.DisplayPreferences;
import org.slf4j.LoggerFactory;
import com.inpuzah.club.core.session.ProfileSessionManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SettingsMenuControllerTest {
    private final UUID uuid = UUID.randomUUID();
    private final ProfileSessionManager profiles = new ProfileSessionManager();
    private final FakeSettings settings = new FakeSettings();
    private final Queue<Runnable> main = new ConcurrentLinkedQueue<>();
    private final List<String> messages = new ArrayList<>();
    private final List<PlayerSettingsService.State> rendered = new ArrayList<>();
    private boolean permitted = true;
    private boolean online = true;
    private int opened;
    private int closed;
    private Inventory top = inventory(null);
    private final Player player = proxy(Player.class, (method, args) -> switch (method) {
        case "getUniqueId" -> uuid;
        case "getOpenInventory" -> this.view;
        case "hasPermission" -> permitted;
        case "sendMessage" -> { messages.add(plain((Component) args[0])); yield null; }
        case "openInventory" -> { top = (Inventory) args[0]; opened++; yield this.view; }
        case "closeInventory" -> {
            var old = top;
            top = inventory(null);
            closed++;
            this.controller.onClose(new InventoryCloseEvent(viewFor(old)));
            yield null;
        }
        default -> throw new AssertionError(method);
    });
    private final InventoryView view = proxy(InventoryView.class, (method, args) -> switch (method) {
        case "getTopInventory" -> top;
        case "getPlayer" -> player;
        case "convertSlot" -> args[0];
        default -> throw new AssertionError(method);
    });
    private final Server server = proxy(Server.class, (method, args) -> switch (method) {
        case "getPlayer" -> online ? player : null;
        case "createInventory" -> {
            assertEquals(27, args[1]);
            yield inventory((InventoryHolder) args[0]);
        }
        default -> throw new AssertionError(method);
    });
    private final SettingsMenuController controller = new SettingsMenuController(
            server, profiles, settings, main::add, 1000, (inventory, snapshot, state) -> rendered.add(state));

    @BeforeEach
    void join() {
        profiles.markReady(uuid, profiles.begin(uuid).token(), PlayerProfile.newProfile(uuid, "Player", Instant.EPOCH));
    }

    @Test
    void commandRequiresPermissionPlayerReadyProfileAndCorrectSyntax() {
        permitted = false;
        open();
        assertTrue(messages.getLast().contains("permission"));
        permitted = true;
        var console = proxy(CommandSender.class, (method, args) -> {
            if (method.equals("hasPermission")) { return true; }
            if (method.equals("sendMessage")) { messages.add(plain((Component) args[0])); return null; }
            throw new AssertionError(method);
        });
        controller.onCommand(console, null, "", new String[0]);
        assertTrue(messages.getLast().contains("player"));
        controller.onCommand(player, null, "", new String[]{"extra"});
        assertTrue(messages.getLast().contains("Usage"));
        profiles.beginRefresh(uuid);
        open();
        assertTrue(messages.getLast().contains("profile"));
        assertEquals(0, opened);
    }

    @Test
    void unavailableSettingsNeverOpenWithFalseDefaults() {
        settings.state = PlayerSettingsService.State.UNAVAILABLE;
        settings.value = null;
        open();
        assertEquals(0, opened);
        assertTrue(messages.getLast().contains("loading or unavailable"));
        assertEquals(1, settings.reads);
    }

    @Test
    void ordinaryClickDispatchesOneSaveAndRapidClicksAreBlocked() {
        open();
        var click = click(SettingsMenu.BOSSBAR, ClickType.LEFT);
        controller.onClick(click);
        controller.onClick(click(SettingsMenu.BOSSBAR, ClickType.LEFT));
        assertTrue(click.isCancelled());
        assertEquals(0, settings.writes);
        drain();
        assertEquals(1, settings.writes);
        assertTrue(rendered.contains(PlayerSettingsService.State.SAVING));
        assertEquals(false, settings.desired);
        settings.complete();
        drain();
        assertFalse(settings.value.bossbarVisible());
        assertEquals(PlayerSettingsService.State.READY, rendered.getLast());
    }

    @Test
    void allMovementPathsAreCancelledWithoutMutations() {
        open();
        for (ClickType type : List.of(ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND,
                ClickType.DOUBLE_CLICK, ClickType.DROP, ClickType.CONTROL_DROP, ClickType.MIDDLE)) {
            var click = click(SettingsMenu.SCOREBOARD, type);
            controller.onClick(click);
            assertTrue(click.isCancelled());
        }
        for (int slot : List.of(4, SettingsMenu.INFO, 0, 27, -999)) {
            var click = click(slot, ClickType.LEFT);
            controller.onClick(click);
            assertTrue(click.isCancelled());
        }
        var drag = new InventoryDragEvent(view, null, null, false, Map.of());
        controller.onDrag(drag);
        assertTrue(drag.isCancelled());
        drain();
        assertEquals(0, settings.writes);
    }

    @Test
    void closeIsDeferredAndPendingSaveFinishesWithoutReopeningMenu() {
        open();
        controller.onClick(click(SettingsMenu.SCOREBOARD, ClickType.RIGHT));
        drain();
        assertEquals(1, settings.writes);
        controller.onClick(click(SettingsMenu.CLOSE, ClickType.LEFT));
        assertEquals(0, closed);
        drain();
        assertEquals(1, closed);
        int renders = rendered.size();
        settings.complete();
        drain();
        assertEquals(renders, rendered.size());
        assertEquals(1, opened);
    }

    @Test
    void staleClickAfterClosingOrReplacingInventoryDoesNothing() {
        open();
        controller.onClick(click(SettingsMenu.BOSSBAR, ClickType.LEFT));
        player.closeInventory();
        drain();
        assertEquals(0, settings.writes);
        open();
        var old = top;
        open();
        controller.onClick(new InventoryClickEvent(viewFor(old), InventoryType.SlotType.CONTAINER,
                SettingsMenu.BOSSBAR, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        drain();
        assertEquals(0, settings.writes);
    }

    @Test
    void permissionRevocationOrUnavailableProfilePreventsQueuedWrite() {
        open();
        controller.onClick(click(SettingsMenu.BOSSBAR, ClickType.LEFT));
        permitted = false;
        drain();
        assertEquals(0, settings.writes);
        assertEquals(1, closed);
        permitted = true;
        open();
        controller.onClick(click(SettingsMenu.BOSSBAR, ClickType.LEFT));
        profiles.beginRefresh(uuid);
        drain();
        assertEquals(0, settings.writes);
        assertEquals(PlayerSettingsService.State.UNAVAILABLE, rendered.getLast());
    }

    @Test
    void rejoinAndForeignViewerCannotReceiveOldCallbacksOrOpenOwnedMenu() {
        open();
        controller.onClick(click(SettingsMenu.BOSSBAR, ClickType.LEFT));
        drain();
        profiles.close(uuid);
        join();
        int renders = rendered.size();
        settings.complete();
        drain();
        assertEquals(renders, rendered.size());
        var foreignHolder = new SettingsMenuHolder(UUID.randomUUID(), 1);
        var event = new InventoryOpenEvent(viewFor(inventory(foreignHolder)));
        controller.onOpen(event);
        assertTrue(event.isCancelled());
    }

    @Test
    void writeErrorDoesNotClaimSuccessAndShutdownClosesOnlyOwnedMenu() {
        open();
        controller.onClick(click(SettingsMenu.BOSSBAR, ClickType.LEFT));
        drain();
        settings.state = PlayerSettingsService.State.UNAVAILABLE;
        settings.pending.completeExceptionally(new IllegalStateException("outage"));
        drain();
        assertTrue(messages.getLast().contains("Could not confirm"));
        assertTrue(settings.value.bossbarVisible());
        controller.close();
        assertEquals(1, closed);
        open();
        assertEquals(1, opened);
    }

    @Test
    void statusLabelsCoverConfirmedPendingAndUnavailableStates() {
        assertEquals("Enabled", SettingsMenu.status(true, PlayerSettingsService.State.READY));
        assertEquals("Disabled", SettingsMenu.status(false, PlayerSettingsService.State.READY));
        assertEquals("Saving...", SettingsMenu.status(true, PlayerSettingsService.State.SAVING));
        assertEquals("Loading...", SettingsMenu.status(true, PlayerSettingsService.State.LOADING));
        assertEquals("Unavailable", SettingsMenu.status(false, PlayerSettingsService.State.UNAVAILABLE));
    }

    @Test
    void guiAndExternalApiMutationsShareOneConfirmedSnapshotAndStoragePath() throws Exception {
        var persisted = new AtomicReference<>(DisplayPreferences.DEFAULT);
        var storage = new DisplayPreferenceService(new DisplayPreferenceRepository() {
            @Override public DisplayPreferences load(UUID id) { return persisted.get(); }
            @Override public DisplayPreferences save(UUID id, boolean bossbar, boolean enabled) {
                return persisted.updateAndGet(old -> bossbar ? new DisplayPreferences(enabled, old.scoreboard())
                        : new DisplayPreferences(old.bossbar(), enabled));
            }
        });
        var service = new DefaultPlayerSettingsService(storage, profiles, Duration.ofSeconds(1),
                LoggerFactory.getLogger(getClass()));
        var snapshots = new ArrayList<PlayerSettings>();
        var menu = new SettingsMenuController(server, profiles, service, main::add, 1000,
                (inventory, snapshot, state) -> snapshots.add(snapshot));
        try {
            service.begin(uuid);
            service.get(uuid).get(5, TimeUnit.SECONDS);
            service.observe(id -> main.add(() -> menu.refresh(id)));
            menu.onCommand(player, null, "", new String[0]);
            menu.onClick(click(SettingsMenu.BOSSBAR, ClickType.LEFT));
            drain();
            storage.load(uuid).get(5, TimeUnit.SECONDS);
            drain();
            assertEquals(new PlayerSettings(uuid, false, true), snapshots.getLast());
            assertEquals(new DisplayPreferences(false, true), persisted.get());
            service.setScoreboardVisible(uuid, false).get(5, TimeUnit.SECONDS);
            drain();
            assertEquals(new PlayerSettings(uuid, false, false), snapshots.getLast());
            assertEquals(snapshots.getLast(), service.cached(uuid).orElseThrow());
        } finally {
            service.observe(id -> {});
            menu.close();
            service.shutdown(Duration.ofSeconds(5));
        }
    }

    private void open() { controller.onCommand(player, null, "settings", new String[0]); }
    private void drain() { while (!main.isEmpty()) { main.remove().run(); } }
    private InventoryClickEvent click(int slot, ClickType type) {
        return new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, slot, type, InventoryAction.PICKUP_ALL);
    }
    private InventoryView viewFor(Inventory inventory) {
        return proxy(InventoryView.class, (method, args) -> switch (method) {
            case "getTopInventory" -> inventory;
            case "getPlayer" -> player;
            case "convertSlot" -> args[0];
            default -> throw new AssertionError(method);
        });
    }
    private static Inventory inventory(InventoryHolder holder) {
        return proxy(Inventory.class, (method, args) -> switch (method) {
            case "getHolder" -> holder;
            case "getSize" -> 27;
            default -> throw new AssertionError(method);
        });
    }
    private static String plain(Component value) { return PlainTextComponentSerializer.plainText().serialize(value); }
    @FunctionalInterface private interface Call { Object invoke(String method, Object[] args); }
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

    private final class FakeSettings implements PlayerSettingsService {
        PlayerSettings value = new PlayerSettings(uuid, true, true);
        State state = State.READY;
        int writes;
        int reads;
        boolean bossbar;
        boolean desired;
        CompletableFuture<PlayerSettings> pending;
        @Override public CompletableFuture<PlayerSettings> get(UUID id) { reads++; return CompletableFuture.completedFuture(value); }
        @Override public Optional<PlayerSettings> cached(UUID id) { return Optional.ofNullable(value); }
        @Override public State state(UUID id) { return state; }
        @Override public CompletableFuture<PlayerSettings> setBossbarVisible(UUID id, boolean visible) { bossbar = true; return save(visible); }
        @Override public CompletableFuture<PlayerSettings> setScoreboardVisible(UUID id, boolean visible) { bossbar = false; return save(visible); }
        private CompletableFuture<PlayerSettings> save(boolean visible) {
            writes++;
            desired = visible;
            state = State.SAVING;
            pending = new CompletableFuture<>();
            return pending;
        }
        void complete() {
            value = bossbar ? new PlayerSettings(uuid, desired, value.scoreboardVisible())
                    : new PlayerSettings(uuid, value.bossbarVisible(), desired);
            state = State.READY;
            pending.complete(value);
        }
        @Override public int shutdown(Duration timeout) { throw new AssertionError(); }
    }
}
