package com.inpuzah.club.core.party.menu;

import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.party.PartyPolicy;
import com.inpuzah.club.core.party.model.Party;
import com.inpuzah.club.core.party.model.PartyMember;
import com.inpuzah.club.core.party.model.PartyOutcome;
import com.inpuzah.club.core.party.repository.JdbcPartyRepository;
import com.inpuzah.club.core.party.repository.PartyEpochLease;
import com.inpuzah.club.core.party.service.DefaultPartyService;
import com.inpuzah.club.core.party.service.PartyService;
import com.inpuzah.club.core.persistence.Migrations;
import com.inpuzah.club.core.repository.JdbcProfileRepository;
import com.inpuzah.club.core.session.ProfileSessionManager;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PartyMenuControllerTest {
    private final UUID uuid = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final ProfileSessionManager profiles = new ProfileSessionManager();
    private final LinkedBlockingQueue<Runnable> main = new LinkedBlockingQueue<>();
    private final List<Component> messages = new ArrayList<>();
    private final List<PartyMenu.State> states = new ArrayList<>();
    private final List<Party> snapshots = new ArrayList<>();
    private final List<Integer> pages = new ArrayList<>();
    private final List<Boolean> confirmations = new ArrayList<>();
    private boolean permission = true, enabled = true, online = true;
    private int opened, closed, reads, writes;
    private String operation;
    private Object[] arguments;
    private CompletableFuture<Optional<Party>> read = CompletableFuture.completedFuture(Optional.empty());
    private CompletableFuture<PartyOutcome> write = new CompletableFuture<>();
    private Inventory top = inventory(null);
    private final Player player = proxy(Player.class, (method, args) -> switch (method) {
        case "getUniqueId" -> uuid;
        case "getOpenInventory" -> this.view;
        case "hasPermission" -> permission;
        case "sendMessage" -> { messages.add((Component) args[0]); yield null; }
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
            this.controller.onClose(new InventoryCloseEvent(viewFor(old)));
            closed++;
            yield null;
        }
        default -> throw new AssertionError(method);
    });
    private final InventoryView view = viewFor(null);
    private final Server server = proxy(Server.class, (method, args) -> switch (method) {
        case "getPlayer" -> online ? player : null;
        case "getOnlinePlayers" -> online ? List.of(player) : List.of();
        case "createInventory" -> {
            assertEquals(27, args[1]);
            yield inventory((InventoryHolder) args[0]);
        }
        default -> throw new AssertionError(method);
    });
    private final PartyService service = proxy(PartyService.class, (method, args) -> {
        if (method.equals("getParty")) { reads++; return read; }
        writes++;
        operation = method;
        arguments = args;
        return write;
    });
    private final PartyMenuController controller = controller(service, 5000);

    @BeforeEach
    void join() {
        profiles.markReady(uuid, profiles.begin(uuid).token(), PlayerProfile.newProfile(uuid, "Player", Instant.EPOCH));
    }

    @Test
    void reconnectAdmissionGatePreventsOpenAndDeferredActions() {
        var admitted = new java.util.concurrent.atomic.AtomicBoolean(false);
        controller.admission(id -> admitted.get());
        controller.open(player);
        assertEquals(0, opened);
        admitted.set(true);
        open();
        controller.onClick(click(PartyMenu.MEMBERSHIP, ClickType.LEFT));
        admitted.set(false);
        drain();
        assertEquals(0, writes);
        assertEquals(1, closed);
    }

    @Test
    void openRequiresPermissionReadyProfileAndEnabledSubsystem() {
        permission = false;
        controller.open(player);
        permission = true;
        enabled = false;
        controller.open(player);
        enabled = true;
        profiles.beginRefresh(uuid);
        controller.open(player);
        assertEquals(0, opened);
        assertEquals(0, reads);
        assertTrue(last().contains("ready profile"));
    }

    @Test
    void loadPublishesOnlyOnMainAndLoadingClicksNeverTurnIntoUnseenActions() {
        read = new CompletableFuture<>();
        controller.open(player);
        assertEquals(PartyMenu.State.LOADING, states.getLast());
        read.complete(Optional.empty());
        controller.onClick(click(PartyMenu.MEMBERSHIP, ClickType.LEFT));
        drain();
        assertEquals(PartyMenu.State.READY, states.getLast());
        assertEquals(0, writes);
        assertEquals(1, reads);
    }

    @Test
    void createIsDeferredRevalidatedAndSinglePendingAcrossRapidClicks() {
        open();
        controller.onClick(click(PartyMenu.MEMBERSHIP, ClickType.LEFT));
        controller.onClick(click(PartyMenu.MEMBERSHIP, ClickType.RIGHT));
        assertEquals(0, writes);
        drain();
        assertEquals("create", operation);
        assertEquals(1, writes);
        assertEquals(2, reads);
        assertEquals(PartyMenu.State.SAVING, states.getLast());
        controller.onClick(click(PartyMenu.REFRESH, ClickType.LEFT));
        drain();
        assertEquals(2, reads);
        var saved = party(uuid, 1, 0);
        read = CompletableFuture.completedFuture(Optional.of(saved));
        write.complete(PartyOutcome.party(PartyOutcome.Code.CREATED, saved.id()));
        drain();
        assertEquals(saved, snapshots.getLast());
        assertEquals(PartyMenu.State.READY, states.getLast());
        assertTrue(last().contains("confirmed: created"));
    }

    @Test
    void leaderLeaveDoesNotSilentlyBecomeDisbandAndCapturesReadyPresence() {
        var party = party(uuid, 2, 0);
        read = CompletableFuture.completedFuture(Optional.of(party));
        open();
        controller.onClick(click(PartyMenu.MEMBERSHIP, ClickType.LEFT));
        drain();
        assertEquals("leaveIfCurrent", operation);
        assertEquals(Set.of(uuid), arguments[1]);
        assertEquals(party.id(), arguments[2]);
        assertEquals(party.revision(), arguments[3]);
        assertEquals(party, snapshots.getLast());
    }

    @Test
    void disbandRequiresSeparateRightThenLeftConfirmationAndRefreshClearsIt() {
        read = CompletableFuture.completedFuture(Optional.of(party(uuid, 2, 0)));
        open();
        controller.onClick(click(PartyMenu.MEMBERSHIP, ClickType.RIGHT));
        controller.onClick(click(PartyMenu.MEMBERSHIP, ClickType.LEFT));
        drain();
        assertEquals(0, writes);
        assertTrue(confirmations.getLast());
        controller.onClick(click(PartyMenu.REFRESH, ClickType.LEFT));
        drain();
        assertFalse(confirmations.getLast());
        controller.onClick(click(PartyMenu.MEMBERSHIP, ClickType.RIGHT));
        drain();
        controller.onClick(click(PartyMenu.MEMBERSHIP, ClickType.LEFT));
        drain();
        assertEquals("disbandIfCurrent", operation);
        assertEquals(1, writes);
    }

    @Test
    void nonLeaderRightClickCanOnlyLeave() {
        read = CompletableFuture.completedFuture(Optional.of(party(other, 2, 0)));
        open();
        controller.onClick(click(PartyMenu.MEMBERSHIP, ClickType.RIGHT));
        drain();
        assertEquals("leaveIfCurrent", operation);
        assertFalse(confirmations.getLast());
    }

    @Test
    void staleRevisionOrPartyIdentityPreventsMutationAndReloadsInsteadOfInventingSuccess() {
        var original = party(uuid, 2, 0);
        read = CompletableFuture.completedFuture(Optional.of(original));
        open();
        read = CompletableFuture.completedFuture(Optional.of(new Party(original.id(), original.instanceId(),
                uuid, original.createdAt(), original.updatedAt(), 1, original.maxMembers(), original.members())));
        controller.onClick(click(PartyMenu.MEMBERSHIP, ClickType.LEFT));
        drain();
        assertEquals(0, writes);
        assertTrue(last().contains("not submitted"));
        assertEquals(1, snapshots.getLast().revision());
        read = CompletableFuture.completedFuture(Optional.of(party(uuid, 2, 1)));
        controller.onClick(click(PartyMenu.MEMBERSHIP, ClickType.LEFT));
        drain();
        assertEquals(0, writes);
    }

    @Test
    void preflightRevalidatesPermissionProfileLoginAndInventoryBeforeWrite() {
        open();
        read = new CompletableFuture<>();
        controller.onClick(click(PartyMenu.MEMBERSHIP, ClickType.LEFT));
        drain();
        permission = false;
        read.complete(Optional.empty());
        drain();
        assertEquals(0, writes);
        assertEquals(1, closed);
        permission = true;
        read = CompletableFuture.completedFuture(Optional.empty());
        open();
        controller.onClick(click(PartyMenu.MEMBERSHIP, ClickType.LEFT));
        profiles.beginRefresh(uuid);
        drain();
        assertEquals(0, writes);
    }

    @Test
    void closeDuringPreflightDoesNotSubmitWriteOrLetOldCallbackTouchReplacement() {
        open();
        read = new CompletableFuture<>();
        controller.onClick(click(PartyMenu.MEMBERSHIP, ClickType.LEFT));
        drain();
        player.closeInventory();
        var preflight = read;
        read = CompletableFuture.completedFuture(Optional.empty());
        controller.open(player);
        drain();
        assertEquals(PartyMenu.State.LOADING, states.getLast());
        preflight.complete(Optional.empty());
        drain();
        assertEquals(0, writes);
        assertEquals(PartyMenu.State.READY, states.getLast());
    }

    @Test
    void acceptedWriteSurvivesRejoinAndNewReadWaitsForItsResult() {
        open();
        controller.onClick(click(PartyMenu.MEMBERSHIP, ClickType.LEFT));
        drain();
        player.closeInventory();
        profiles.close(uuid);
        join();
        controller.open(player);
        drain();
        assertEquals(2, reads);
        assertEquals(PartyMenu.State.LOADING, states.getLast());
        var saved = party(uuid, 1, 0);
        read = CompletableFuture.completedFuture(Optional.of(saved));
        write.complete(PartyOutcome.party(PartyOutcome.Code.CREATED, saved.id()));
        drain();
        assertEquals(saved, snapshots.getLast());
        assertEquals(PartyMenu.State.READY, states.getLast());
    }

    @Test
    void failureRetainsConfirmedMembersAndRecoversAuthoritatively() {
        var saved = party(uuid, 2, 0);
        read = CompletableFuture.completedFuture(Optional.of(saved));
        open();
        controller.onClick(click(PartyMenu.MEMBERSHIP, ClickType.LEFT));
        drain();
        read = CompletableFuture.failedFuture(new IllegalStateException("outage"));
        write.completeExceptionally(new IllegalStateException("acknowledgement lost"));
        drain();
        assertEquals(PartyMenu.State.UNAVAILABLE, states.getLast());
        assertEquals(saved, snapshots.getLast());
        read = CompletableFuture.completedFuture(Optional.empty());
        controller.onClick(click(PartyMenu.REFRESH, ClickType.LEFT));
        drain();
        assertEquals(PartyMenu.State.READY, states.getLast());
        assertNull(snapshots.getLast());
        assertEquals(1, writes);
    }

    @Test
    void timeoutCannotCancelAcceptedWriteOrUnlockSecondMutation() throws Exception {
        var scheduled = new CountDownLatch(1);
        var current = Thread.currentThread();
        var menu = new PartyMenuController(server, profiles, service, task -> {
            main.add(task);
            if (Thread.currentThread() != current) { scheduled.countDown(); }
        }, 100, () -> enabled, LoggerFactory.getLogger(getClass()), this::render);
        try {
            menu.open(player);
            drain();
            menu.onClick(click(PartyMenu.MEMBERSHIP, ClickType.LEFT));
            drain();
            assertTrue(scheduled.await(5, TimeUnit.SECONDS));
            drain();
            assertFalse(write.isDone());
            assertTrue(last().contains("not confirmed yet"));
            menu.onClick(click(PartyMenu.MEMBERSHIP, ClickType.LEFT));
            drain();
            assertEquals(1, writes);
            read = CompletableFuture.completedFuture(Optional.of(party(uuid, 1, 0)));
            write.complete(PartyOutcome.party(PartyOutcome.Code.CREATED, read.join().orElseThrow().id()));
            drain();
            assertEquals(PartyMenu.State.READY, states.getLast());
        } finally {
            menu.close();
        }
    }

    @Test
    void allMovementAndDragPathsAreCancelledWithoutActions() {
        open();
        for (var type : List.of(ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT, ClickType.NUMBER_KEY,
                ClickType.SWAP_OFFHAND, ClickType.DOUBLE_CLICK, ClickType.DROP, ClickType.CONTROL_DROP, ClickType.MIDDLE)) {
            var event = click(PartyMenu.MEMBERSHIP, type);
            controller.onClick(event);
            assertTrue(event.isCancelled());
        }
        for (int slot : List.of(-999, 27, 35, 3, 5, 9)) {
            var event = click(slot, ClickType.LEFT);
            controller.onClick(event);
            assertTrue(event.isCancelled());
        }
        var drag = new InventoryDragEvent(view, null, null, false, Map.of());
        controller.onDrag(drag);
        assertTrue(drag.isCancelled());
        drain();
        assertEquals(0, writes);
        assertEquals(1, reads);
    }

    @Test
    void pagesCoverAll32MembersAndMemberCommandsSuggestExactUuidWithoutExecuting() {
        var saved = party(uuid, 32, 0);
        read = CompletableFuture.completedFuture(Optional.of(saved));
        open();
        assertEquals(5, PartyMenu.pages(saved));
        for (int page = 1; page < 6; page++) {
            controller.onClick(click(PartyMenu.NEXT, ClickType.LEFT));
            drain();
            assertEquals(Math.min(page, 4), pages.getLast());
        }
        controller.onClick(click(PartyMenu.FIRST_MEMBER, ClickType.LEFT));
        drain();
        var member = saved.members().get(28);
        assertEquals(ClickEvent.suggestCommand("/party kick " + member.playerId()),
                messages.getLast().clickEvent());
        assertEquals(ClickEvent.suggestCommand("/party promote " + member.playerId()),
                messages.getLast().children().getFirst().clickEvent());
        assertEquals(0, writes);
        controller.onClick(click(PartyMenu.PREVIOUS, ClickType.RIGHT));
        drain();
        assertEquals(3, pages.getLast());
    }

    @Test
    void helpActionsAreHonestAndNeverDispatchStorageMutations() {
        open();
        controller.onClick(click(PartyMenu.INVITES, ClickType.LEFT));
        drain();
        assertEquals(ClickEvent.runCommand("/party invites"), messages.getLast().clickEvent());
        controller.onClick(click(PartyMenu.INVITE, ClickType.LEFT));
        drain();
        assertTrue(last().contains("/party invite"));
        controller.onClick(click(PartyMenu.CHAT, ClickType.LEFT));
        drain();
        assertTrue(last().contains("disabled"));
        assertEquals(0, writes);
    }

    @Test
    void staleHoldersForeignViewerAndShutdownCannotActOnReplacementMenu() {
        open();
        var old = top;
        controller.open(player);
        drain();
        controller.onClick(new InventoryClickEvent(viewFor(old), InventoryType.SlotType.CONTAINER,
                PartyMenu.MEMBERSHIP, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        var foreign = new PartyMenuHolder(other, profiles.get(uuid).orElseThrow().token());
        foreign.bind(inventory(foreign));
        var event = new InventoryOpenEvent(viewFor(foreign.getInventory()));
        controller.onOpen(event);
        assertTrue(event.isCancelled());
        controller.onClick(click(PartyMenu.MEMBERSHIP, ClickType.LEFT));
        controller.close();
        drain();
        assertEquals(0, writes);
        assertEquals(1, closed);
        controller.open(player);
        assertEquals(2, opened);
    }

    @Test
    void transactionalStaleResultNeverClaimsSuccessAndReloadsNewVersion() {
        var before = party(uuid, 2, 0);
        read = CompletableFuture.completedFuture(Optional.of(before));
        open();
        controller.onClick(click(PartyMenu.MEMBERSHIP, ClickType.LEFT));
        drain();
        assertEquals("leaveIfCurrent", operation);
        var after = new Party(before.id(), before.instanceId(), uuid, before.createdAt(),
                before.updatedAt(), 1, before.maxMembers(), before.members());
        read = CompletableFuture.completedFuture(Optional.of(after));
        write.complete(PartyOutcome.of(PartyOutcome.Code.STALE_PARTY));
        drain();
        assertTrue(last().contains("not applied"));
        assertEquals(after, snapshots.getLast());
        assertEquals(PartyMenu.State.READY, states.getLast());
    }

    @Test
    void foreignInventoriesAreNotModifiedAndShutdownDoesNotCloseThem() {
        var foreign = inventory(null);
        var event = new InventoryClickEvent(viewFor(foreign), InventoryType.SlotType.CONTAINER,
                PartyMenu.MEMBERSHIP, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        controller.onClick(event);
        assertFalse(event.isCancelled());
        controller.close();
        assertEquals(0, closed);
    }

    @Test
    void disablingRuntimeOrRevokingPermissionInvalidatesOpenMenu() {
        open();
        enabled = false;
        controller.refresh(uuid);
        assertEquals(1, closed);
        enabled = true;
        open();
        permission = false;
        controller.refresh(uuid);
        assertEquals(2, closed);
    }

    @Test
    void realMenuCreateAndConfirmedDisbandPersistThroughBoundedServiceAndPostgres() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().start()) {
            var data = new PGSimpleDataSource();
            data.setServerNames(new String[]{"127.0.0.1"});
            data.setPortNumbers(new int[]{postgres.getPort()});
            data.setDatabaseName("postgres");
            data.setUser("postgres");
            Migrations.apply(data, getClass().getClassLoader());
            new JdbcProfileRepository(data).upsertOnJoin(uuid, "Player");
            try (var lease = PartyEpochLease.acquire(data, "party-menu")) {
                var real = DefaultPartyService.create(new JdbcPartyRepository(data, lease, PartyPolicy.DEFAULT),
                        1, 10, LoggerFactory.getLogger(getClass()));
                var menu = controller(real, 5000);
                try {
                    menu.open(player);
                    awaitReady();
                    menu.onClick(click(PartyMenu.MEMBERSHIP, ClickType.LEFT));
                    drain();
                    awaitReady();
                    var created = real.getParty(uuid).get(5, TimeUnit.SECONDS);
                    assertTrue(created.isPresent());
                    assertEquals(PartyMenu.State.READY, states.getLast());
                    assertEquals(created.orElseThrow().id(), snapshots.getLast().id());
                    menu.onClick(click(PartyMenu.MEMBERSHIP, ClickType.RIGHT));
                    drain();
                    assertTrue(real.getParty(uuid).get(5, TimeUnit.SECONDS).isPresent());
                    menu.onClick(click(PartyMenu.MEMBERSHIP, ClickType.LEFT));
                    drain();
                    awaitReady();
                    assertTrue(real.getParty(uuid).get(5, TimeUnit.SECONDS).isEmpty());
                    assertNull(snapshots.getLast());
                } finally {
                    menu.close();
                    real.shutdown(Duration.ofSeconds(5));
                }
            }
        }
    }

    private PartyMenuController controller(PartyService service, long timeout) {
        return new PartyMenuController(server, profiles, service, main::add, timeout,
                () -> enabled, LoggerFactory.getLogger(getClass()), this::render);
    }
    private void render(Inventory inventory, Party party, PartyMenu.State state, int page,
                        boolean confirm, UUID viewer, Set<UUID> online) {
        assertEquals(uuid, viewer);
        assertTrue(online.contains(uuid));
        snapshots.add(party);
        states.add(state);
        pages.add(page);
        confirmations.add(confirm);
    }
    private Party party(UUID leader, int count, long revision) {
        var members = new ArrayList<PartyMember>();
        members.add(new PartyMember(uuid, "Player", Instant.EPOCH));
        if (count > 1) { members.add(new PartyMember(other, "Other", Instant.EPOCH.plusSeconds(1))); }
        for (int index = 2; index < count; index++) {
            members.add(new PartyMember(UUID.randomUUID(), "Member" + index, Instant.EPOCH.plusSeconds(index)));
        }
        return new Party(UUID.randomUUID(), UUID.randomUUID(), leader, Instant.EPOCH,
                Instant.EPOCH.plusSeconds(60), revision, 32, members);
    }
    private void open() { controller.open(player); drain(); }
    private void awaitReady() throws Exception {
        while (states.getLast() != PartyMenu.State.READY) {
            var task = main.poll(5, TimeUnit.SECONDS);
            assertNotNull(task, "Expected an asynchronous menu completion");
            task.run();
        }
        drain();
    }
    private void drain() { while (!main.isEmpty()) { main.remove().run(); } }
    private String last() { return PlainTextComponentSerializer.plainText().serialize(messages.getLast()); }
    private InventoryClickEvent click(int slot, ClickType type) {
        return new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, slot, type, InventoryAction.PICKUP_ALL);
    }
    private InventoryView viewFor(Inventory inventory) {
        return proxy(InventoryView.class, (method, args) -> switch (method) {
            case "getTopInventory" -> inventory == null ? top : inventory;
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
}
