package com.inpuzah.club.core.social.menu;

import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.persistence.Migrations;
import com.inpuzah.club.core.repository.JdbcProfileRepository;
import com.inpuzah.club.core.display.JdbcDisplayPreferenceRepository;
import com.inpuzah.club.core.display.DisplayPreferences;
import com.inpuzah.club.core.social.SocialConfig;
import com.inpuzah.club.core.social.repository.JdbcSocialRepository;
import com.inpuzah.club.core.social.service.DefaultSocialService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import com.inpuzah.club.core.session.ProfileSessionManager;
import com.inpuzah.club.core.social.model.FriendRequest;
import com.inpuzah.club.core.social.model.FriendSummary;
import com.inpuzah.club.core.social.model.Relationship;
import com.inpuzah.club.core.social.model.SocialAction;
import com.inpuzah.club.core.social.model.SocialPreferences;
import com.inpuzah.club.core.social.model.SocialResult;
import com.inpuzah.club.core.social.service.SocialService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PrivacyMenuControllerTest {
    private final UUID uuid = UUID.randomUUID();
    private final ProfileSessionManager profiles = new ProfileSessionManager();
    private final FakeSocial social = new FakeSocial();
    private final Queue<Runnable> main = new ConcurrentLinkedQueue<>();
    private final List<String> messages = new ArrayList<>();
    private final List<PrivacyMenu.State> states = new ArrayList<>();
    private final List<SocialPreferences> snapshots = new ArrayList<>();
    private boolean settingsPermission = true;
    private boolean socialPermission = true;
    private boolean online = true;
    private int opened;
    private int closed;
    private Inventory top = inventory(null);
    private final Player player = proxy(Player.class, (method, args) -> switch (method) {
        case "getUniqueId" -> uuid;
        case "getOpenInventory" -> this.view;
        case "hasPermission" -> args[0].equals("club.settings") ? settingsPermission : socialPermission;
        case "sendMessage" -> { messages.add(plain((Component) args[0])); yield null; }
        case "openInventory" -> {
            var old = top;
            top = (Inventory) args[0];
            this.controller.onClose(new InventoryCloseEvent(viewFor(old)));
            opened++;
            yield this.view;
        }
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
    private final PrivacyMenuController controller = controller(1000);

    @BeforeEach
    void join() {
        profiles.markReady(uuid, profiles.begin(uuid).token(), PlayerProfile.newProfile(uuid, "Player", Instant.EPOCH));
    }

    @Test
    void openingRequiresBothPermissionsAndReadyProfile() {
        socialPermission = false;
        controller.open(player);
        socialPermission = true;
        settingsPermission = false;
        controller.open(player);
        settingsPermission = true;
        profiles.beginRefresh(uuid);
        controller.open(player);
        assertEquals(0, opened);
        assertEquals(0, social.reads);
        assertEquals(3, messages.size());
    }

    @Test
    void loadUsesExplicitLoadingAndPublishesConfirmedPolicyOnlyOnMainThread() {
        social.read = new CompletableFuture<>();
        controller.open(player);
        assertEquals(PrivacyMenu.State.LOADING, states.getLast());
        assertNull(snapshots.getLast());
        social.read.complete(new SocialPreferences(uuid, false));
        assertEquals(PrivacyMenu.State.LOADING, states.getLast());
        drain();
        assertEquals(PrivacyMenu.State.READY, states.getLast());
        assertFalse(snapshots.getLast().friendRequestsEnabled());
    }

    @Test
    void rapidClicksQueueOneMutationAndRetainLastConfirmedPolicyWhileSaving() {
        openReady();
        var click = click(PrivacyMenu.REQUESTS, ClickType.LEFT);
        controller.onClick(click);
        controller.onClick(click(PrivacyMenu.REQUESTS, ClickType.RIGHT));
        assertTrue(click.isCancelled());
        assertEquals(0, social.writes);
        drain();
        assertEquals(1, social.writes);
        assertFalse(social.desired);
        assertEquals(PrivacyMenu.State.SAVING, states.getLast());
        assertTrue(snapshots.getLast().friendRequestsEnabled());
        controller.onClick(click(PrivacyMenu.REQUESTS, ClickType.LEFT));
        drain();
        assertEquals(1, social.writes);
        complete(false);
        assertEquals(PrivacyMenu.State.READY, states.getLast());
        assertFalse(snapshots.getLast().friendRequestsEnabled());
    }

    @Test
    void loadedNobodyCanBeChangedBackToEveryone() {
        social.read = CompletableFuture.completedFuture(new SocialPreferences(uuid, false));
        openReady();
        controller.onClick(click(PrivacyMenu.REQUESTS, ClickType.RIGHT));
        drain();
        assertTrue(social.desired);
        complete(true);
        assertTrue(snapshots.getLast().friendRequestsEnabled());
    }

    @Test
    void allMovementPathsAndDecorativeBottomOutsideSlotsAreCancelledWithoutSaves() {
        openReady();
        for (ClickType type : List.of(ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND,
                ClickType.DOUBLE_CLICK, ClickType.DROP, ClickType.CONTROL_DROP, ClickType.MIDDLE)) {
            var event = click(PrivacyMenu.REQUESTS, type);
            controller.onClick(event);
            assertTrue(event.isCancelled());
        }
        for (int slot : List.of(4, 0, 27, -999)) {
            var event = click(slot, ClickType.LEFT);
            controller.onClick(event);
            assertTrue(event.isCancelled());
        }
        var drag = new InventoryDragEvent(view, null, null, false, Map.of());
        controller.onDrag(drag);
        assertTrue(drag.isCancelled());
        drain();
        assertEquals(0, social.writes);
    }

    @Test
    void closeAndBackAreDeferredAndCanBeUsedDuringPendingSave() {
        var backs = new AtomicInteger();
        controller.backToSettings(viewer -> {
            assertSame(player, viewer);
            backs.incrementAndGet();
        });
        openReady();
        controller.onClick(click(PrivacyMenu.REQUESTS, ClickType.LEFT));
        drain();
        controller.onClick(click(PrivacyMenu.BACK, ClickType.LEFT));
        assertEquals(0, backs.get());
        drain();
        assertEquals(1, backs.get());
        int renders = states.size();
        complete(false);
        assertEquals(renders, states.size());
        assertEquals(1, opened);
        openReady();
        controller.onClick(click(PrivacyMenu.CLOSE, ClickType.LEFT));
        assertEquals(1, closed);
        drain();
        assertEquals(2, closed);
    }

    @Test
    void closedAndReopenedMenuWaitsForPriorSaveBeforeReadingAgain() {
        openReady();
        controller.onClick(click(PrivacyMenu.REQUESTS, ClickType.LEFT));
        drain();
        player.closeInventory();
        controller.open(player);
        assertEquals(1, social.reads);
        assertEquals(PrivacyMenu.State.LOADING, states.getLast());
        social.read = CompletableFuture.completedFuture(new SocialPreferences(uuid, false));
        complete(false);
        assertEquals(2, social.reads);
        assertFalse(snapshots.getLast().friendRequestsEnabled());
    }

    @Test
    void reconnectRejectsOldCallbacksButPreservesOrderingOfAcceptedPersistence() {
        openReady();
        controller.onClick(click(PrivacyMenu.REQUESTS, ClickType.LEFT));
        drain();
        controller.onQuit(new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        profiles.close(uuid);
        join();
        controller.open(player);
        int rendered = states.size();
        assertEquals(1, social.reads);
        social.read = CompletableFuture.completedFuture(new SocialPreferences(uuid, false));
        complete(false);
        assertEquals(rendered + 1, states.size());
        assertFalse(snapshots.getLast().friendRequestsEnabled());
    }

    @Test
    void staleLoadAfterReplacementCannotOverwriteNewMenu() {
        var first = new CompletableFuture<SocialPreferences>();
        social.read = first;
        controller.open(player);
        social.read = CompletableFuture.completedFuture(new SocialPreferences(uuid, false));
        controller.open(player);
        drain();
        int rendered = states.size();
        first.complete(new SocialPreferences(uuid, true));
        drain();
        assertEquals(rendered, states.size());
        assertFalse(snapshots.getLast().friendRequestsEnabled());
    }

    @Test
    void loadFailureIsUnavailableAndClickRetriesWithoutWritingUnknownDefaults() {
        social.read = CompletableFuture.failedFuture(new IllegalStateException("outage"));
        controller.open(player);
        drain();
        assertEquals(PrivacyMenu.State.UNAVAILABLE, states.getLast());
        assertNull(snapshots.getLast());
        social.read = CompletableFuture.completedFuture(new SocialPreferences(uuid, false));
        controller.onClick(click(PrivacyMenu.REQUESTS, ClickType.LEFT));
        drain();
        assertEquals(0, social.writes);
        assertEquals(2, social.reads);
        assertEquals(PrivacyMenu.State.READY, states.getLast());
        assertFalse(snapshots.getLast().friendRequestsEnabled());
    }

    @Test
    void failedSaveRecoversAuthoritativeChoiceWithoutClaimingSuccess() {
        openReady();
        controller.onClick(click(PrivacyMenu.REQUESTS, ClickType.LEFT));
        drain();
        social.read = CompletableFuture.completedFuture(new SocialPreferences(uuid, false));
        social.write.completeExceptionally(new IllegalStateException("lost acknowledgement"));
        drain();
        assertTrue(messages.getLast().contains("Could not confirm"));
        assertEquals(2, social.reads);
        assertFalse(snapshots.getLast().friendRequestsEnabled());
        assertEquals(PrivacyMenu.State.READY, states.getLast());
    }

    @Test
    void failureDuringRecoveryKeepsLastConfirmedAndAllowsRetry() {
        openReady();
        controller.onClick(click(PrivacyMenu.REQUESTS, ClickType.LEFT));
        drain();
        social.read = CompletableFuture.failedFuture(new IllegalStateException("outage"));
        social.write.completeExceptionally(new IllegalStateException("outage"));
        drain();
        assertEquals(PrivacyMenu.State.UNAVAILABLE, states.getLast());
        assertTrue(snapshots.getLast().friendRequestsEnabled());
        social.read = CompletableFuture.completedFuture(new SocialPreferences(uuid, false));
        controller.onClick(click(PrivacyMenu.REQUESTS, ClickType.LEFT));
        drain();
        assertFalse(snapshots.getLast().friendRequestsEnabled());
        assertEquals(1, social.writes);
    }

    @Test
    void permissionRevocationAndProfileUnavailableRejectDeferredWrites() {
        openReady();
        controller.onClick(click(PrivacyMenu.REQUESTS, ClickType.LEFT));
        socialPermission = false;
        drain();
        assertEquals(1, closed);
        assertEquals(0, social.writes);
        socialPermission = true;
        openReady();
        controller.onClick(click(PrivacyMenu.REQUESTS, ClickType.LEFT));
        profiles.beginRefresh(uuid);
        drain();
        assertEquals(0, social.writes);
        assertEquals(PrivacyMenu.State.UNAVAILABLE, states.getLast());
    }

    @Test
    void foreignViewerAndStaleHolderCannotOpenAndForeignInventoriesAreUntouched() {
        openReady();
        var old = top;
        player.closeInventory();
        controller.open(player);
        controller.onClick(new InventoryClickEvent(viewFor(old), InventoryType.SlotType.CONTAINER,
                PrivacyMenu.REQUESTS, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        drain();
        assertEquals(0, social.writes);
        var foreign = new PrivacyMenuHolder(UUID.randomUUID(), 1);
        var owned = inventory(foreign);
        foreign.bind(owned);
        var event = new InventoryOpenEvent(viewFor(owned));
        controller.onOpen(event);
        assertTrue(event.isCancelled());
        player.closeInventory();
        var ordinary = click(13, ClickType.LEFT);
        controller.onClick(ordinary);
        assertFalse(ordinary.isCancelled());
    }

    @Test
    void shutdownClosesOnlyOwnedMenuAndDropsQueuedAndLateWork() {
        openReady();
        controller.onClick(click(PrivacyMenu.REQUESTS, ClickType.LEFT));
        drain();
        int renders = states.size();
        controller.close();
        complete(false);
        assertEquals(renders, states.size());
        assertEquals(1, closed);
        controller.open(player);
        assertEquals(1, opened);
    }

    @Test
    void timedOutCallerDoesNotCancelSaveAndLateCommitRefreshesCurrentMenu() throws Exception {
        var timeoutScheduled = new CountDownLatch(1);
        var testThread = Thread.currentThread();
        var timed = new PrivacyMenuController(server, profiles, social,
                task -> {
                    main.add(task);
                    if (Thread.currentThread() != testThread) { timeoutScheduled.countDown(); }
                }, 100,
                LoggerFactory.getLogger(getClass()), this::render);
        timed.open(player);
        drain();
        timed.onClick(click(PrivacyMenu.REQUESTS, ClickType.LEFT));
        drain();
        assertTrue(timeoutScheduled.await(5, TimeUnit.SECONDS));
        drain();
        assertFalse(social.write.isDone());
        assertTrue(messages.getLast().contains("not confirmed"));
        assertEquals(PrivacyMenu.State.SAVING, states.getLast());
        social.write.complete(new SocialPreferences(uuid, false));
        drain();
        assertFalse(snapshots.getLast().friendRequestsEnabled());
        assertEquals(PrivacyMenu.State.READY, states.getLast());
        timed.close();
    }

    @Test
    void labelsDistinguishUnknownLoadingPendingAndEveryoneNobody() {
        assertEquals("Everyone", PrivacyMenu.status(new SocialPreferences(uuid, true), PrivacyMenu.State.READY));
        assertEquals("Nobody", PrivacyMenu.status(new SocialPreferences(uuid, false), PrivacyMenu.State.READY));
        assertEquals("Unavailable", PrivacyMenu.status(null, PrivacyMenu.State.READY));
        assertEquals("Loading...", PrivacyMenu.status(null, PrivacyMenu.State.LOADING));
        assertEquals("Saving...", PrivacyMenu.status(null, PrivacyMenu.State.SAVING));
        assertEquals("Unavailable", PrivacyMenu.status(null, PrivacyMenu.State.UNAVAILABLE));
    }

    @Test
    void menuSavePersistsAndIsEnforcedByRequestsWithoutChangingDisplayPreferences() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().start()) {
            var data = postgres.getPostgresDatabase();
            Migrations.apply(data, getClass().getClassLoader());
            var profileRepository = new JdbcProfileRepository(data);
            profileRepository.upsertOnJoin(uuid, "Player");
            UUID sender = UUID.randomUUID();
            profileRepository.upsertOnJoin(sender, "Sender");
            var displays = new JdbcDisplayPreferenceRepository(data);
            displays.save(uuid, true, false);
            var repository = new JdbcSocialRepository(data, SocialConfig.DEFAULT);
            var service = DefaultSocialService.create(repository, 1, 10, LoggerFactory.getLogger(getClass()));
            var menu = new PrivacyMenuController(server, profiles, service, main::add, 5000,
                    LoggerFactory.getLogger(getClass()), this::render);
            try {
                menu.open(player);
                service.preferences(uuid).get(5, TimeUnit.SECONDS);
                drain();
                assertTrue(snapshots.getLast().friendRequestsEnabled());
                menu.onClick(click(PrivacyMenu.REQUESTS, ClickType.LEFT));
                drain();
                service.preferences(uuid).get(5, TimeUnit.SECONDS);
                drain();
                assertFalse(snapshots.getLast().friendRequestsEnabled());
                assertEquals(SocialResult.CANNOT_REQUEST,
                        service.mutate(SocialAction.REQUEST, sender, uuid).get(5, TimeUnit.SECONDS));
                assertEquals(new DisplayPreferences(false, true), displays.load(uuid));
            } finally {
                menu.close();
                service.shutdown(Duration.ofSeconds(5));
            }
            var recreated = DefaultSocialService.create(new JdbcSocialRepository(data, SocialConfig.DEFAULT),
                    1, 10, LoggerFactory.getLogger(getClass()));
            var newMenu = new PrivacyMenuController(server, profiles, recreated, main::add, 5000,
                    LoggerFactory.getLogger(getClass()), this::render);
            try {
                newMenu.open(player);
                recreated.preferences(uuid).get(5, TimeUnit.SECONDS);
                drain();
                assertFalse(snapshots.getLast().friendRequestsEnabled());
                newMenu.onClick(click(PrivacyMenu.REQUESTS, ClickType.LEFT));
                drain();
                recreated.preferences(uuid).get(5, TimeUnit.SECONDS);
                drain();
                assertTrue(snapshots.getLast().friendRequestsEnabled());
                assertEquals(SocialResult.REQUEST_SENT,
                        recreated.mutate(SocialAction.REQUEST, sender, uuid).get(5, TimeUnit.SECONDS));
                assertEquals(new DisplayPreferences(false, true), displays.load(uuid));
            } finally {
                newMenu.close();
                recreated.shutdown(Duration.ofSeconds(5));
            }
        }
    }

    private PrivacyMenuController controller(long timeout) {
        return new PrivacyMenuController(server, profiles, social, main::add, timeout,
                LoggerFactory.getLogger(getClass()), this::render);
    }
    private void render(Inventory inventory, SocialPreferences preferences, PrivacyMenu.State state) {
        snapshots.add(preferences);
        states.add(state);
    }
    private void openReady() { controller.open(player); drain(); }
    private void complete(boolean enabled) { social.write.complete(new SocialPreferences(uuid, enabled)); drain(); }
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

    private final class FakeSocial implements SocialService {
        @Override public CompletableFuture<Relationship> relationship(UUID player, UUID other) {
            throw new AssertionError();
        }
        int reads;
        int writes;
        boolean desired;
        CompletableFuture<SocialPreferences> read = CompletableFuture.completedFuture(new SocialPreferences(uuid, true));
        CompletableFuture<SocialPreferences> write = new CompletableFuture<>();
        @Override public CompletableFuture<SocialPreferences> preferences(UUID player) { reads++; return read; }
        @Override public CompletableFuture<SocialPreferences> setPreferences(UUID player, boolean enabled) {
            writes++; desired = enabled; return write;
        }
        @Override public CompletableFuture<SocialResult> mutate(SocialAction action, UUID actor, UUID other) { throw new AssertionError(); }
        @Override public CompletableFuture<List<FriendSummary>> listFriends(UUID player, int offset, int limit) { throw new AssertionError(); }
        @Override public CompletableFuture<List<FriendRequest>> listIncomingRequests(UUID player, int offset, int limit) { throw new AssertionError(); }
        @Override public CompletableFuture<List<FriendSummary>> listBlocked(UUID player, int offset, int limit) { throw new AssertionError(); }
        @Override public int shutdown(Duration timeout) { throw new AssertionError(); }
    }
}
