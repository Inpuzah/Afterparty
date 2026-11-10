package com.inpuzah.club.core.social.menu;

import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.persistence.Migrations;
import com.inpuzah.club.core.repository.JdbcProfileRepository;
import com.inpuzah.club.core.session.ProfileSessionManager;
import com.inpuzah.club.core.social.model.FriendRequest;
import com.inpuzah.club.core.social.model.FriendSummary;
import com.inpuzah.club.core.social.model.Relationship;
import com.inpuzah.club.core.social.model.SocialAction;
import com.inpuzah.club.core.social.model.SocialPreferences;
import com.inpuzah.club.core.social.model.SocialResult;
import com.inpuzah.club.core.social.service.SocialService;
import com.inpuzah.club.core.social.service.DefaultSocialService;
import com.inpuzah.club.core.social.repository.JdbcSocialRepository;
import com.inpuzah.club.core.social.SocialConfig;
import com.inpuzah.club.core.social.command.SocialCommand;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
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
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
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

import static org.junit.jupiter.api.Assertions.*;

class InteractionMenuControllerTest {
    private final UUID viewerId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();
    private final ProfileSessionManager profiles = new ProfileSessionManager();
    private final FakeSocial social = new FakeSocial();
    private final Queue<Runnable> main = new ConcurrentLinkedQueue<>();
    private final List<String> messages = new ArrayList<>();
    private final List<String> notifications = new ArrayList<>();
    private final List<Relationship> rendered = new ArrayList<>();
    private final List<UUID> renderedTargets = new ArrayList<>();
    private final List<PlayerInteractionMenu.State> states = new ArrayList<>();
    private boolean permitted = true;
    private boolean sneaking = true;
    private boolean connected = true;
    private boolean targetConnected = true;
    private int opened;
    private int closed;
    private Inventory top = inventory(null);
    private final Player viewer = player(viewerId, "Viewer", messages);
    private final Player target = player(targetId, "Target", notifications);
    private final Player other = player(otherId, "Other", notifications);
    private final InventoryView view = viewForCurrent();
    private final Server server = proxy(Server.class, (method, args) -> switch (method) {
        case "getPlayer" -> viewerId.equals(args[0]) ? connected ? viewer : null
                : targetId.equals(args[0]) ? targetConnected ? target : null : other;
        case "createInventory" -> {
            assertEquals(27, args[1]);
            yield inventory((InventoryHolder) args[0]);
        }
        default -> throw new AssertionError(method);
    });
    private final InteractionMenuController controller = controller(true, main::add, 1000);

    @BeforeEach
    void join() {
        ready(viewerId, "Viewer");
        ready(targetId, "Target");
        ready(otherId, "Other");
    }

    @Test
    void onlySneakingMainHandUncancelledPlayerInteractionsOpenWhenEnabled() {
        sneaking = false;
        controller.onInteract(interact(target, EquipmentSlot.HAND));
        sneaking = true;
        var offhand = interact(target, EquipmentSlot.OFF_HAND);
        controller.onInteract(offhand);
        assertFalse(offhand.isCancelled());
        var protectedEvent = interact(target, EquipmentSlot.HAND);
        protectedEvent.setCancelled(true);
        controller.onInteract(protectedEvent);
        controller.onInteract(interact(viewer, EquipmentSlot.HAND));
        var disabled = controller(false, main::add, 1000);
        var ordinary = interact(target, EquipmentSlot.HAND);
        disabled.onInteract(ordinary);
        assertFalse(ordinary.isCancelled());
        drain();
        assertEquals(0, opened);
        assertEquals(0, social.reads);
    }

    @Test
    void permissionsProfilesAndExistingInventoryPreventOpening() {
        permitted = false;
        controller.onInteract(interact(target, EquipmentSlot.HAND));
        permitted = true;
        top = inventory(new InventoryHolder() {
            @Override public Inventory getInventory() { return top; }
        });
        controller.onInteract(interact(target, EquipmentSlot.HAND));
        top = inventory(null);
        profiles.beginRefresh(targetId);
        controller.onInteract(interact(target, EquipmentSlot.HAND));
        drain();
        assertEquals(0, opened);
        assertEquals(0, social.reads);
    }

    @Test
    void opensDeferredOnceAndRendersCachedTargetWithLoadingThenRelationship() {
        social.read = new CompletableFuture<>();
        var event = interact(target, EquipmentSlot.HAND);
        controller.onInteract(event);
        controller.onInteract(interact(target, EquipmentSlot.HAND));
        assertTrue(event.isCancelled());
        assertEquals(0, opened);
        drain();
        assertEquals(1, opened);
        assertEquals(PlayerInteractionMenu.State.LOADING, states.getLast());
        assertEquals(targetId, renderedTargets.getLast());
        social.read.complete(Relationship.INCOMING_REQUEST);
        drain();
        assertEquals(Relationship.INCOMING_REQUEST, rendered.getLast());
        assertEquals(PlayerInteractionMenu.State.READY, states.getLast());
    }

    @Test
    void openingRechecksInventoryLoginPermissionAndTargetConnection() {
        controller.onInteract(interact(target, EquipmentSlot.HAND));
        top = inventory(null);
        drain();
        assertEquals(0, opened);
        controller.onInteract(interact(target, EquipmentSlot.HAND));
        ready(targetId, "Target");
        drain();
        assertEquals(0, opened);
        controller.onInteract(interact(target, EquipmentSlot.HAND));
        targetConnected = false;
        drain();
        assertEquals(0, opened);
        targetConnected = true;
        controller.onInteract(interact(target, EquipmentSlot.HAND));
        permitted = false;
        drain();
        assertEquals(0, opened);
    }

    @Test
    void actionsMapRelationshipWithoutBlindToggles() {
        assertEquals(SocialAction.REQUEST, PlayerInteractionMenu.friendAction(Relationship.NONE));
        assertEquals(SocialAction.ACCEPT, PlayerInteractionMenu.friendAction(Relationship.INCOMING_REQUEST));
        assertEquals(SocialAction.CANCEL, PlayerInteractionMenu.friendAction(Relationship.OUTGOING_REQUEST));
        assertEquals(SocialAction.REMOVE, PlayerInteractionMenu.friendAction(Relationship.FRIENDS));
        assertNull(PlayerInteractionMenu.friendAction(Relationship.BLOCKED));
        for (var entry : Map.of(Relationship.NONE, SocialAction.REQUEST, Relationship.INCOMING_REQUEST, SocialAction.ACCEPT,
                Relationship.OUTGOING_REQUEST, SocialAction.CANCEL, Relationship.FRIENDS, SocialAction.REMOVE).entrySet()) {
            social.read = CompletableFuture.completedFuture(entry.getKey());
            open(target);
            controller.onClick(click(PlayerInteractionMenu.FRIEND, ClickType.LEFT));
            drain();
            assertEquals(entry.getValue(), social.action);
            social.write.complete(SocialResult.REQUEST_MISSING);
            drain();
            viewer.closeInventory();
            social.write = new CompletableFuture<>();
        }
    }

    @Test
    void loadingClickCannotBecomeAnUnseenActionWhenQueuedReadCompletesFirst() {
        social.read = new CompletableFuture<>();
        open(target);
        assertEquals(PlayerInteractionMenu.State.LOADING, states.getLast());
        social.read.complete(Relationship.INCOMING_REQUEST);
        controller.onClick(click(PlayerInteractionMenu.FRIEND, ClickType.LEFT));
        drain();
        assertEquals(PlayerInteractionMenu.State.READY, states.getLast());
        assertEquals(0, social.writes);
        controller.onClick(click(PlayerInteractionMenu.FRIEND, ClickType.LEFT));
        drain();
        assertEquals(SocialAction.ACCEPT, social.action);
    }

    @Test
    void oneQueuedMutationAndPendingStatePreventRapidDuplicateActions() {
        open(target);
        controller.onClick(click(PlayerInteractionMenu.FRIEND, ClickType.LEFT));
        controller.onClick(click(PlayerInteractionMenu.BLOCK, ClickType.RIGHT));
        assertEquals(0, social.writes);
        drain();
        assertEquals(1, social.writes);
        assertEquals(SocialAction.REQUEST, social.action);
        assertEquals(PlayerInteractionMenu.State.SAVING, states.getLast());
        controller.onClick(click(PlayerInteractionMenu.FRIEND, ClickType.LEFT));
        drain();
        assertEquals(1, social.writes);
        social.read = CompletableFuture.completedFuture(Relationship.OUTGOING_REQUEST);
        social.write.complete(SocialResult.REQUEST_SENT);
        drain();
        assertEquals(Relationship.OUTGOING_REQUEST, rendered.getLast());
        assertTrue(notifications.getLast().contains("Viewer sent"));
    }

    @Test
    void blockAndUnblockUseViewerRelativeStateAndBlockedFriendActionDoesNothing() {
        social.read = CompletableFuture.completedFuture(Relationship.BLOCKED);
        open(target);
        controller.onClick(click(PlayerInteractionMenu.FRIEND, ClickType.LEFT));
        drain();
        assertEquals(0, social.writes);
        controller.onClick(click(PlayerInteractionMenu.BLOCK, ClickType.LEFT));
        drain();
        assertEquals(SocialAction.UNBLOCK, social.action);
        social.read = CompletableFuture.completedFuture(Relationship.NONE);
        social.write.complete(SocialResult.UNBLOCKED);
        drain();
        social.write = new CompletableFuture<>();
        controller.onClick(click(PlayerInteractionMenu.BLOCK, ClickType.LEFT));
        drain();
        assertEquals(SocialAction.BLOCK, social.action);
    }

    @Test
    void movementDecorativeSummaryBottomAndOutsideSlotsCannotMoveOrMutate() {
        open(target);
        for (var type : List.of(ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND,
                ClickType.DOUBLE_CLICK, ClickType.DROP, ClickType.CONTROL_DROP, ClickType.MIDDLE)) {
            var event = click(PlayerInteractionMenu.FRIEND, type);
            controller.onClick(event);
            assertTrue(event.isCancelled());
        }
        for (int slot : List.of(4, PlayerInteractionMenu.SUMMARY, 0, 27, -999)) {
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
    void closeIsDeferredAndAcceptedWriteCanFinishWithoutOldUiOrReopen() {
        open(target);
        controller.onClick(click(PlayerInteractionMenu.FRIEND, ClickType.LEFT));
        drain();
        controller.onClick(click(PlayerInteractionMenu.CLOSE, ClickType.LEFT));
        assertEquals(0, closed);
        drain();
        assertEquals(1, closed);
        int renders = states.size();
        social.write.complete(SocialResult.REQUEST_SENT);
        drain();
        assertEquals(renders, states.size());
        assertEquals(1, opened);
    }

    @Test
    void replacedTargetAndMenuRejectOldLoads() {
        var old = new CompletableFuture<Relationship>();
        social.read = old;
        open(target);
        viewer.closeInventory();
        social.read = CompletableFuture.completedFuture(Relationship.FRIENDS);
        open(other);
        int renders = states.size();
        old.complete(Relationship.BLOCKED);
        drain();
        assertEquals(renders, states.size());
        assertEquals(otherId, renderedTargets.getLast());
        assertEquals(Relationship.FRIENDS, rendered.getLast());
    }

    @Test
    void reopeningAnotherTargetWaitsForAcceptedMutationBeforeReading() {
        open(target);
        controller.onClick(click(PlayerInteractionMenu.FRIEND, ClickType.LEFT));
        drain();
        viewer.closeInventory();
        open(other);
        assertEquals(1, social.reads);
        social.read = CompletableFuture.completedFuture(Relationship.NONE);
        social.write.complete(SocialResult.REQUEST_SENT);
        drain();
        assertEquals(2, social.reads);
        assertEquals(otherId, renderedTargets.getLast());
        assertEquals(PlayerInteractionMenu.State.READY, states.getLast());
    }

    @Test
    void targetDisconnectClosesMenuAndQueuedActionDoesNotReachService() {
        open(target);
        controller.onClick(click(PlayerInteractionMenu.FRIEND, ClickType.LEFT));
        controller.onQuit(new PlayerQuitEvent(target, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        drain();
        assertEquals(1, closed);
        assertEquals(0, social.writes);
    }

    @Test
    void reconnectAndPermissionRevocationPreventStaleMutationsAndNotifications() {
        open(target);
        controller.onClick(click(PlayerInteractionMenu.FRIEND, ClickType.LEFT));
        ready(targetId, "Target");
        drain();
        assertEquals(0, social.writes);
        assertEquals(1, closed);
        messages.clear();
        open(target);
        controller.onClick(click(PlayerInteractionMenu.FRIEND, ClickType.LEFT));
        drain();
        ready(viewerId, "Viewer");
        ready(targetId, "Target");
        social.write.complete(SocialResult.REQUEST_SENT);
        drain();
        assertTrue(messages.isEmpty());
        assertTrue(notifications.isEmpty());
        viewer.closeInventory();
        open(target);
        controller.onClick(click(PlayerInteractionMenu.FRIEND, ClickType.LEFT));
        permitted = false;
        drain();
        assertEquals(3, closed);
        assertEquals(1, social.writes);
    }

    @Test
    void unavailableProfileBlocksMutationAndClosesTargetPreviewWithoutSnapshot() {
        open(target);
        controller.onClick(click(PlayerInteractionMenu.FRIEND, ClickType.LEFT));
        profiles.beginRefresh(targetId);
        drain();
        assertEquals(0, social.writes);
        assertEquals(1, closed);
    }

    @Test
    void failedLoadRetriesAndUncertainMutationReloadsWithoutSuccessClaims() {
        social.read = CompletableFuture.failedFuture(new IllegalStateException("outage"));
        open(target);
        assertEquals(PlayerInteractionMenu.State.UNAVAILABLE, states.getLast());
        social.read = CompletableFuture.completedFuture(Relationship.NONE);
        controller.onClick(click(PlayerInteractionMenu.FRIEND, ClickType.LEFT));
        drain();
        assertEquals(0, social.writes);
        assertEquals(PlayerInteractionMenu.State.READY, states.getLast());
        controller.onClick(click(PlayerInteractionMenu.FRIEND, ClickType.LEFT));
        drain();
        social.read = CompletableFuture.completedFuture(Relationship.OUTGOING_REQUEST);
        social.write.completeExceptionally(new IllegalStateException("lost acknowledgement"));
        drain();
        assertTrue(messages.getLast().contains("Could not confirm"));
        assertEquals(Relationship.OUTGOING_REQUEST, rendered.getLast());
        assertTrue(notifications.isEmpty());
    }

    @Test
    void genericRejectionDoesNotRevealOtherPlayersBlockListOrNotifyThem() {
        open(target);
        controller.onClick(click(PlayerInteractionMenu.FRIEND, ClickType.LEFT));
        drain();
        social.write.complete(SocialResult.CANNOT_REQUEST);
        drain();
        assertTrue(messages.getLast().contains("cannot be sent"));
        assertFalse(messages.getLast().toLowerCase().contains("block"));
        assertTrue(notifications.isEmpty());
    }

    @Test
    void foreignHolderCannotOpenAndForeignInventoriesAreNotCancelled() {
        var holder = new InteractionMenuHolder(UUID.randomUUID(), targetId, 1, 2);
        var owned = inventory(holder);
        holder.bind(owned);
        var event = new InventoryOpenEvent(viewFor(owned));
        controller.onOpen(event);
        assertTrue(event.isCancelled());
        var ordinary = click(11, ClickType.LEFT);
        controller.onClick(ordinary);
        assertFalse(ordinary.isCancelled());
    }

    @Test
    void shutdownDropsScheduledOpenAndLateCallbackAndClosesOwnedInventory() {
        controller.onInteract(interact(target, EquipmentSlot.HAND));
        controller.close();
        drain();
        assertEquals(0, opened);
        var active = controller(true, main::add, 1000);
        active.onInteract(interact(target, EquipmentSlot.HAND));
        drain();
        active.onClick(click(PlayerInteractionMenu.FRIEND, ClickType.LEFT));
        drain();
        int renders = states.size();
        active.close();
        social.write.complete(SocialResult.REQUEST_SENT);
        drain();
        assertEquals(renders, states.size());
        assertTrue(notifications.isEmpty());
    }

    @Test
    void menuActionsPersistAcceptBlockUnblockAndEnforcePrivacyInPostgres() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().start()) {
            var data = postgres.getPostgresDatabase();
            Migrations.apply(data, getClass().getClassLoader());
            var profileRepository = new JdbcProfileRepository(data);
            profileRepository.upsertOnJoin(viewerId, "Viewer");
            profileRepository.upsertOnJoin(targetId, "Target");
            var repository = new JdbcSocialRepository(data, SocialConfig.DEFAULT);
            assertEquals(SocialResult.REQUEST_SENT, repository.mutate(SocialAction.REQUEST, targetId, viewerId));
            var service = DefaultSocialService.create(repository, 1, 10, LoggerFactory.getLogger(getClass()));
            var menu = new InteractionMenuController(server, profiles, service, main::add, 5000,
                    LoggerFactory.getLogger(getClass()), true, (inventory, profile, relationship, state) -> {
                        rendered.add(relationship);
                        states.add(state);
                    }, inventory -> inventory.getHolder(false) == null);
            try {
                menu.onInteract(interact(target, EquipmentSlot.HAND));
                drain();
                settle(service);
                assertEquals(Relationship.INCOMING_REQUEST, rendered.getLast());
                menu.onClick(click(PlayerInteractionMenu.FRIEND, ClickType.LEFT));
                drain();
                settle(service);
                assertEquals(Relationship.FRIENDS, rendered.getLast());
                assertEquals(Relationship.FRIENDS, repository.relationship(targetId, viewerId));
                menu.onClick(click(PlayerInteractionMenu.BLOCK, ClickType.LEFT));
                drain();
                settle(service);
                assertEquals(Relationship.BLOCKED, rendered.getLast());
                assertTrue(repository.listFriends(targetId, 0, 10).isEmpty());
                assertEquals(Relationship.NONE, repository.relationship(targetId, viewerId));
                menu.onClick(click(PlayerInteractionMenu.BLOCK, ClickType.LEFT));
                drain();
                settle(service);
                assertEquals(Relationship.NONE, rendered.getLast());
                repository.setPreferences(targetId, false);
                menu.onClick(click(PlayerInteractionMenu.FRIEND, ClickType.LEFT));
                drain();
                settle(service);
                assertEquals(SocialCommand.describe(SocialResult.CANNOT_REQUEST), messages.getLast());
                assertTrue(repository.listIncomingRequests(targetId, 0, 10).isEmpty());
                assertEquals(Relationship.NONE, new JdbcSocialRepository(data, SocialConfig.DEFAULT).relationship(viewerId, targetId));
            } finally {
                menu.close();
                assertEquals(0, service.shutdown(Duration.ofSeconds(5)));
            }
        }
    }

    @Test
    void timeoutDoesNotCancelAcceptedMutationAndLateCommitRefreshes() throws Exception {
        var timeoutScheduled = new CountDownLatch(1);
        var testThread = Thread.currentThread();
        var timed = controller(true, task -> {
            main.add(task);
            if (Thread.currentThread() != testThread) { timeoutScheduled.countDown(); }
        }, 100);
        timed.onInteract(interact(target, EquipmentSlot.HAND));
        drain();
        timed.onClick(click(PlayerInteractionMenu.FRIEND, ClickType.LEFT));
        drain();
        assertTrue(timeoutScheduled.await(5, TimeUnit.SECONDS));
        drain();
        assertFalse(social.write.isDone());
        assertTrue(messages.getLast().contains("not confirmed"));
        assertEquals(PlayerInteractionMenu.State.SAVING, states.getLast());
        social.read = CompletableFuture.completedFuture(Relationship.OUTGOING_REQUEST);
        social.write.complete(SocialResult.REQUEST_SENT);
        drain();
        assertEquals(Relationship.OUTGOING_REQUEST, rendered.getLast());
        timed.close();
    }

    @Test
    void partyInviteUsesExactSessionAndSharedNotificationWithoutChangingSocialRelationship() {
        var writes = new ArrayList<Object[]>();
        var result = new CompletableFuture<com.inpuzah.club.core.party.model.PartyOutcome>();
        var parties = proxy(com.inpuzah.club.core.party.service.PartyService.class, (method, args) -> {
            assertEquals("invite", method);
            writes.add(args);
            return result;
        });
        controller.partyInvitations(new com.inpuzah.club.core.party.PartyInvitations(server, profiles, parties,
                main::add, () -> true, id -> true));
        open(target);
        controller.onClick(click(PlayerInteractionMenu.PARTY, ClickType.LEFT));
        controller.onClick(click(PlayerInteractionMenu.PARTY, ClickType.LEFT));
        drain();
        assertEquals(1, writes.size());
        assertEquals(viewerId, writes.getFirst()[0]);
        assertEquals(targetId, writes.getFirst()[1]);
        assertEquals(0, social.writes);
        assertTrue(notifications.isEmpty());
        result.complete(new com.inpuzah.club.core.party.model.PartyOutcome(
                com.inpuzah.club.core.party.model.PartyOutcome.Code.INVITED,
                java.util.Optional.of(UUID.randomUUID()), java.util.Optional.of(UUID.randomUUID())));
        drain();
        assertEquals(1, notifications.size());
        assertTrue(notifications.getLast().contains("invited"));
        assertEquals(Relationship.NONE, rendered.getLast());
    }

    @Test
    void partyInviteWithoutRuntimeOrAfterTargetRejoinDoesNotMutateSocialState() {
        open(target);
        controller.onClick(click(PlayerInteractionMenu.PARTY, ClickType.LEFT));
        drain();
        assertTrue(messages.getLast().contains("disabled"));
        ready(targetId, "Target");
        controller.onClick(click(PlayerInteractionMenu.PARTY, ClickType.LEFT));
        drain();
        assertEquals(0, social.writes);
    }

    private void ready(UUID uuid, String name) {
        profiles.markReady(uuid, profiles.begin(uuid).token(), PlayerProfile.newProfile(uuid, name, Instant.EPOCH));
    }
    private void settle(SocialService service) throws Exception {
        service.relationship(viewerId, targetId).get(5, TimeUnit.SECONDS);
        drain();
        service.relationship(viewerId, targetId).get(5, TimeUnit.SECONDS);
        drain();
    }
    private InteractionMenuController controller(boolean enabled, java.util.concurrent.Executor executor, long timeout) {
        return new InteractionMenuController(server, profiles, social, executor, timeout, LoggerFactory.getLogger(getClass()),
                enabled, (inventory, profile, relationship, state) -> {
                    renderedTargets.add(profile.uuid());
                    rendered.add(relationship);
                    states.add(state);
                }, inventory -> inventory.getHolder(false) == null);
    }
    private void open(Player target) { controller.onInteract(interact(target, EquipmentSlot.HAND)); drain(); }
    private PlayerInteractEntityEvent interact(Player target, EquipmentSlot hand) {
        return new PlayerInteractEntityEvent(viewer, target, hand);
    }
    private void drain() { while (!main.isEmpty()) { main.remove().run(); } }
    private InventoryClickEvent click(int slot, ClickType type) {
        return new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, slot, type, InventoryAction.PICKUP_ALL);
    }
    private Player player(UUID uuid, String name, List<String> output) {
        return proxy(Player.class, (method, args) -> switch (method) {
            case "getUniqueId" -> uuid;
            case "getName" -> name;
            case "isSneaking" -> sneaking;
            case "hasPermission" -> permitted;
            case "getOpenInventory" -> this.view;
            case "sendMessage" -> { output.add(PlainTextComponentSerializer.plainText().serialize((Component) args[0])); yield null; }
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
    }
    private InventoryView viewForCurrent() {
        return proxy(InventoryView.class, (method, args) -> switch (method) {
            case "getTopInventory" -> top;
            case "getPlayer" -> viewer;
            case "convertSlot" -> args[0];
            default -> throw new AssertionError(method);
        });
    }
    private InventoryView viewFor(Inventory inventory) {
        return proxy(InventoryView.class, (method, args) -> switch (method) {
            case "getTopInventory" -> inventory;
            case "getPlayer" -> viewer;
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
    private static final class FakeSocial implements SocialService {
        int reads;
        int writes;
        SocialAction action;
        CompletableFuture<Relationship> read = CompletableFuture.completedFuture(Relationship.NONE);
        CompletableFuture<SocialResult> write = new CompletableFuture<>();
        @Override public CompletableFuture<Relationship> relationship(UUID viewer, UUID other) { reads++; return read; }
        @Override public CompletableFuture<SocialResult> mutate(SocialAction action, UUID actor, UUID other) {
            writes++;
            this.action = action;
            return write;
        }
        @Override public CompletableFuture<List<FriendSummary>> listFriends(UUID player, int offset, int limit) { throw new AssertionError(); }
        @Override public CompletableFuture<List<FriendRequest>> listIncomingRequests(UUID player, int offset, int limit) { throw new AssertionError(); }
        @Override public CompletableFuture<List<FriendSummary>> listBlocked(UUID player, int offset, int limit) { throw new AssertionError(); }
        @Override public CompletableFuture<SocialPreferences> preferences(UUID player) { throw new AssertionError(); }
        @Override public CompletableFuture<SocialPreferences> setPreferences(UUID player, boolean enabled) { throw new AssertionError(); }
        @Override public CompletableFuture<SocialPreferences> setPartyPreferences(UUID player, boolean enabled) { throw new AssertionError(); }
        @Override public int shutdown(Duration timeout) { throw new AssertionError(); }
    }
}
