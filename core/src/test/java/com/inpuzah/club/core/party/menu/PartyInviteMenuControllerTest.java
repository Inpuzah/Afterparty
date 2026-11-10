package com.inpuzah.club.core.party.menu;

import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.party.PartyInvitations;
import com.inpuzah.club.core.party.PartyPolicy;
import com.inpuzah.club.core.party.model.PartyInvite;
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
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PartyInviteMenuControllerTest {
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    private final ProfileSessionManager profiles = new ProfileSessionManager();
    private final LinkedBlockingQueue<Runnable> main = new LinkedBlockingQueue<>();
    private final Map<UUID, Player> online = new HashMap<>();
    private final List<Component> messages = new ArrayList<>();
    private final Player alice = player(a, "Alice"), bob = player(b, "Bob");
    private boolean enabled = true, permission = true, admitted = true;
    private int writes, reads, backs;
    private String operation;
    private Object[] arguments;
    private Inventory top = inventory(null);
    private CompletableFuture<List<PartyInvite>> invites = CompletableFuture.completedFuture(List.of());
    private CompletableFuture<PartyOutcome> write = new CompletableFuture<>();
    private final InventoryView view = proxy(InventoryView.class, (method, args) -> switch (method) {
        case "getTopInventory" -> top;
        case "getPlayer" -> alice;
        case "convertSlot" -> args[0];
        default -> throw new AssertionError(method);
    });
    private final Server server = proxy(Server.class, (method, args) -> switch (method) {
        case "getPlayer" -> online.get((UUID) args[0]);
        case "getOnlinePlayers" -> List.copyOf(online.values());
        case "createInventory" -> inventory((InventoryHolder) args[0]);
        default -> throw new AssertionError(method);
    });
    private final PartyService service = proxy(PartyService.class, (method, args) -> {
        if (method.equals("getParty")) { reads++; return CompletableFuture.completedFuture(Optional.empty()); }
        if (method.equals("getInvites")) { reads++; arguments = args; return invites; }
        writes++;
        operation = method;
        arguments = args;
        return write;
    });
    private PartyInviteMenuController controller = controller(service, 5000);

    @BeforeEach void join() { ready(a, "Alice"); ready(b, "Bob"); }

    @Test void incomingLeftAcceptAndRightDenyUseExactDisplayedInviteIdentity() {
        var invite = invite();
        invites = CompletableFuture.completedFuture(List.of(invite));
        controller.openIncoming(alice);
        drain();
        click(0, ClickType.LEFT);
        drain();
        assertEquals("accept", operation);
        assertEquals(invite.id(), arguments[1]);
        assertEquals(1, writes);
        click(0, ClickType.RIGHT);
        drain();
        assertEquals(1, writes);
        write.complete(PartyOutcome.of(PartyOutcome.Code.ACCEPTED));
        drain();
        write = new CompletableFuture<>();
        click(0, ClickType.RIGHT);
        drain();
        assertEquals("deny", operation);
        assertEquals(invite.id(), arguments[1]);
    }

    @Test void candidatesRetainExactReadySessionAndInviteOnlyOnce() {
        controller.openCandidates(alice);
        drain();
        assertEquals(1, holder().entries.size());
        assertEquals(b, holder().entries.getFirst().id());
        click(0, ClickType.LEFT);
        click(0, ClickType.LEFT);
        drain();
        assertEquals("invite", operation);
        assertEquals(b, arguments[1]);
        assertEquals(1, writes);
    }

    @Test void targetRejoinOrDepartureBeforeDeferredClickNeverSubmitsOldInvitation() {
        controller.openCandidates(alice);
        drain();
        click(0, ClickType.LEFT);
        ready(b, "Bob");
        drain();
        assertEquals(0, writes);
        click(0, ClickType.LEFT);
        online.remove(b);
        drain();
        assertEquals(0, writes);
        assertTrue(holder().entries.isEmpty());
    }

    @Test void loadingClickCannotBecomeAnUnseenAccept() {
        invites = new CompletableFuture<>();
        controller.openIncoming(alice);
        click(0, ClickType.LEFT);
        invites.complete(List.of(invite()));
        drain();
        assertEquals(0, writes);
        assertEquals(PartyMenu.State.READY, holder().state);
    }

    @Test void pageSizeIsBoundedAndExactIdsSurviveNavigation() {
        invites = CompletableFuture.completedFuture(java.util.stream.IntStream.range(0, 19).mapToObj(index -> invite()).toList());
        controller.openIncoming(alice);
        drain();
        assertEquals(18, holder().entries.size());
        assertTrue(holder().next);
        assertEquals(0, arguments[1]);
        assertEquals(19, arguments[2]);
        var next = invite();
        invites = CompletableFuture.completedFuture(List.of(next));
        click(PartyInviteMenu.NEXT, ClickType.LEFT);
        drain();
        assertEquals(18, arguments[1]);
        assertEquals(1, holder().page);
        assertFalse(holder().next);
        click(0, ClickType.LEFT);
        drain();
        assertEquals(next.id(), arguments[1]);
    }

    @Test void permissionAdmissionAndFeatureAreRecheckedAtDeferredExecution() {
        controller.openCandidates(alice);
        drain();
        click(0, ClickType.LEFT);
        permission = false;
        drain();
        assertEquals(0, writes);
        permission = true;
        admitted = false;
        controller.openCandidates(alice);
        assertEquals(0, writes);
        admitted = true;
        enabled = false;
        controller.openIncoming(alice);
        assertEquals(0, writes);
    }

    @Test void viewerRejoinOrInventoryReplacementRejectsQueuedMutation() {
        controller.openCandidates(alice);
        drain();
        click(0, ClickType.LEFT);
        ready(a, "Alice");
        drain();
        assertEquals(0, writes);
        controller.openCandidates(alice);
        drain();
        click(0, ClickType.LEFT);
        top = inventory(null);
        drain();
        assertEquals(0, writes);
    }

    @Test void pendingMutationSurvivesCloseAndReopenWithoutOvertakingOrCancelling() {
        invites = CompletableFuture.completedFuture(List.of(invite()));
        controller.openIncoming(alice);
        drain();
        click(0, ClickType.LEFT);
        drain();
        int before = reads;
        controller.onClose(new InventoryCloseEvent(view));
        controller.openIncoming(alice);
        drain();
        assertEquals(PartyMenu.State.LOADING, holder().state);
        assertEquals(before, reads);
        click(0, ClickType.RIGHT);
        write.complete(PartyOutcome.of(PartyOutcome.Code.ACCEPTED));
        drain();
        assertEquals(1, writes);
        assertEquals(PartyMenu.State.READY, holder().state);
        assertFalse(write.isCancelled());
    }

    @Test void failedReadPublishesUnavailableAndExplicitRefreshRetries() {
        invites = CompletableFuture.failedFuture(new IllegalStateException("offline"));
        controller.openIncoming(alice);
        drain();
        assertEquals(PartyMenu.State.UNAVAILABLE, holder().state);
        invites = CompletableFuture.completedFuture(List.of(invite()));
        click(PartyInviteMenu.REFRESH, ClickType.LEFT);
        drain();
        assertEquals(PartyMenu.State.READY, holder().state);
    }

    @Test void failedWriteNeverClaimsSuccessAndReloadsSavedState() {
        invites = CompletableFuture.completedFuture(List.of(invite()));
        controller.openIncoming(alice);
        drain();
        click(0, ClickType.LEFT);
        drain();
        write.completeExceptionally(new IllegalStateException("uncertain"));
        drain();
        assertTrue(messages.stream().map(PlainTextComponentSerializer.plainText()::serialize)
                .anyMatch(line -> line.contains("may have completed")));
        assertEquals(PartyMenu.State.READY, holder().state);
    }

    @Test void timedOutMutationRemainsPendingAndLateCommitStillReloads() throws Exception {
        controller = controller(service, 50);
        invites = CompletableFuture.completedFuture(List.of(invite()));
        controller.openIncoming(alice);
        drain();
        click(0, ClickType.LEFT);
        drain();
        var deadline = main.poll(5, TimeUnit.SECONDS);
        assertNotNull(deadline);
        deadline.run();
        assertFalse(write.isDone());
        assertEquals(PartyMenu.State.SAVING, holder().state);
        click(0, ClickType.LEFT);
        drain();
        assertEquals(1, writes);
        write.complete(PartyOutcome.of(PartyOutcome.Code.ACCEPTED));
        drain();
        assertEquals(PartyMenu.State.READY, holder().state);
        assertFalse(write.isCancelled());
    }

    @Test void dragsBottomClicksAndShiftClicksCannotMoveMenuItems() {
        controller.openCandidates(alice);
        drain();
        var shift = click(0, ClickType.SHIFT_LEFT);
        var bottom = click(30, ClickType.LEFT);
        var drag = new InventoryDragEvent(view, null, null, false, Map.of());
        controller.onDrag(drag);
        drain();
        assertTrue(shift.isCancelled());
        assertTrue(bottom.isCancelled());
        assertTrue(drag.isCancelled());
        assertEquals(0, writes);
    }

    @Test void backNavigatesAndShutdownPreventsFurtherActions() {
        controller.openIncoming(alice);
        drain();
        click(PartyInviteMenu.BACK, ClickType.LEFT);
        drain();
        assertEquals(1, backs);
        controller.close();
        click(0, ClickType.LEFT);
        drain();
        assertEquals(0, writes);
    }

    @Test void realCandidateInviteAndIncomingAcceptPersistEndToEnd() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().start()) {
            var source = new PGSimpleDataSource();
            source.setServerNames(new String[]{"127.0.0.1"});
            source.setPortNumbers(new int[]{postgres.getPort()});
            source.setDatabaseName("postgres");
            source.setUser("postgres");
            Migrations.apply(source, getClass().getClassLoader());
            var repository = new JdbcProfileRepository(source);
            repository.upsertOnJoin(a, "Alice");
            repository.upsertOnJoin(b, "Bob");
            try (var lease = PartyEpochLease.acquire(source, "invite-menu")) {
                var real = DefaultPartyService.create(new JdbcPartyRepository(source, lease, PartyPolicy.DEFAULT),
                        1, 16, LoggerFactory.getLogger(getClass()));
                controller = controller(real, 5000);
                try {
                    real.invite(b, a).get(5, TimeUnit.SECONDS);
                    controller.openIncoming(alice);
                    awaitReady();
                    click(0, ClickType.LEFT);
                    drain();
                    awaitReady();
                    assertTrue(real.getParty(b).get(5, TimeUnit.SECONDS).orElseThrow().isMember(a));
                    real.disband(b).get(5, TimeUnit.SECONDS);
                    controller.openCandidates(alice);
                    awaitReady();
                    click(0, ClickType.LEFT);
                    drain();
                    awaitReady();
                    var invitations = real.getInvites(b, 0, 19).get(5, TimeUnit.SECONDS);
                    assertEquals(1, invitations.size());
                    assertEquals(a, invitations.getFirst().inviterId());
                    real.accept(b, invitations.getFirst().id()).get(5, TimeUnit.SECONDS);
                    assertTrue(real.getParty(a).get(5, TimeUnit.SECONDS).orElseThrow().isMember(b));
                } finally {
                    controller.close();
                    real.shutdown(Duration.ofSeconds(5));
                }
            }
        }
    }

    private PartyInviteMenuController controller(PartyService source, long timeout) {
        var invitations = new PartyInvitations(server, profiles, source, main::add, () -> enabled, id -> admitted);
        return new PartyInviteMenuController(server, profiles, source, invitations, main::add, timeout,
                () -> enabled, player -> backs++, LoggerFactory.getLogger(getClass()), (inventory, holder) -> {});
    }
    private void awaitReady() throws Exception {
        while (holder().state != PartyMenu.State.READY) {
            var task = main.poll(5, TimeUnit.SECONDS);
            assertNotNull(task);
            task.run();
        }
        drain();
    }
    private PartyInviteMenuHolder holder() { return (PartyInviteMenuHolder) top.getHolder(false); }
    private PartyInvite invite() {
        return new PartyInvite(UUID.randomUUID(), UUID.randomUUID(), b, a, Instant.EPOCH, Instant.EPOCH.plusSeconds(120));
    }
    private InventoryClickEvent click(int slot, ClickType type) {
        var event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, slot, type, InventoryAction.PICKUP_ALL);
        controller.onClick(event);
        return event;
    }
    private void ready(UUID id, String name) {
        profiles.markReady(id, profiles.begin(id).token(), PlayerProfile.newProfile(id, name, Instant.EPOCH));
    }
    private Player player(UUID id, String name) {
        Player player = proxy(Player.class, (method, args) -> switch (method) {
            case "getUniqueId" -> id;
            case "getName" -> name;
            case "getOpenInventory" -> view;
            case "hasPermission" -> permission;
            case "openInventory" -> { top = (Inventory) args[0]; yield view; }
            case "closeInventory" -> { top = inventory(null); yield null; }
            case "sendMessage" -> { messages.add((Component) args[0]); yield null; }
            default -> throw new AssertionError(method);
        });
        online.put(id, player);
        return player;
    }
    private void drain() { Runnable task; while ((task = main.poll()) != null) { task.run(); } }
    private static Inventory inventory(InventoryHolder holder) {
        return proxy(Inventory.class, (method, args) -> switch (method) {
            case "getHolder" -> holder;
            case "getSize" -> 27;
            default -> throw new AssertionError(method);
        });
    }
    private interface Invocation { Object call(String method, Object[] args); }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (value, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> value == args[0];
                    case "hashCode" -> System.identityHashCode(value);
                    case "toString" -> type.getSimpleName();
                    default -> throw new AssertionError(method);
                };
            }
            return invocation.call(method.getName(), args);
        });
    }
}
