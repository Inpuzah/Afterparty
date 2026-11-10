package com.inpuzah.club.core.party;

import com.inpuzah.club.core.command.CommandFeedback;
import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.party.command.PartyCommand;
import com.inpuzah.club.core.party.model.Party;
import com.inpuzah.club.core.party.model.PartyInvite;
import com.inpuzah.club.core.party.model.PartyMember;
import com.inpuzah.club.core.party.model.PartyOutcome;
import com.inpuzah.club.core.party.service.PartyService;
import com.inpuzah.club.core.party.service.DefaultPartyService;
import com.inpuzah.club.core.party.repository.PartyEpochLease;
import com.inpuzah.club.core.party.repository.JdbcPartyRepository;
import com.inpuzah.club.core.party.repository.JdbcPartyPreferencesRepository;
import com.inpuzah.club.core.persistence.Migrations;
import com.inpuzah.club.core.repository.JdbcProfileRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.postgresql.ds.PGSimpleDataSource;
import com.inpuzah.club.core.session.ProfileSessionManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.inpuzah.club.core.party.model.PartyOutcome.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class PartyCommandTest {
    private final UUID aliceId = UUID.randomUUID();
    private final UUID bobId = UUID.randomUUID();
    private final UUID inviteId = UUID.randomUUID();
    private final UUID partyId = UUID.randomUUID();
    private final List<Component> replies = new ArrayList<>();
    private final List<Component> notifications = new ArrayList<>();
    private final Queue<Runnable> main = new ConcurrentLinkedQueue<>();
    private final ProfileSessionManager sessions = new ProfileSessionManager();
    private boolean permission = true;
    private boolean enabled = true;
    private boolean aliceConnected = true;
    private boolean bobConnected = true;
    private int menus;
    private int calls;
    private String operation;
    private Object[] arguments;
    private CompletableFuture<PartyOutcome> result = new CompletableFuture<>();
    private Optional<Party> party = Optional.empty();
    private List<PartyInvite> invitations = List.of();
    private final Player alice = player(aliceId, "Alice", replies);
    private final Player bob = player(bobId, "Bob", notifications);
    private final Server server = proxy(Server.class, (method, args) -> switch (method) {
        case "getPlayerExact" -> "Bob".equals(args[0]) && bobConnected ? bob
                : "Alice".equals(args[0]) && aliceConnected ? alice : null;
        case "getPlayer" -> aliceId.equals(args[0]) ? aliceConnected ? alice : null
                : bobId.equals(args[0]) && bobConnected ? bob : null;
        case "getOnlinePlayers" -> bobConnected ? List.of(alice, bob) : List.of(alice);
        default -> throw new AssertionError(method);
    });
    private final PartyService service = proxy(PartyService.class, (method, args) -> {
        calls++;
        operation = method;
        arguments = args;
        return switch (method) {
            case "getParty" -> CompletableFuture.completedFuture(party);
            case "getInvites" -> CompletableFuture.completedFuture(invitations);
            default -> result;
        };
    });
    private final PartyCommand command = command(Duration.ofSeconds(5));

    @BeforeEach
    void join() {
        ready(aliceId, "Alice");
        ready(bobId, "Bob");
    }

    @Test
    void runtimeSharedInvitationAdapterNotifiesExactlyOnceAndReservesPendingAcrossCommands() {
        command.invitations(new PartyInvitations(server, sessions, service, main::add, () -> enabled, id -> true));
        command.onCommand(alice, null, "party", new String[]{"invite", "Bob"});
        command.onCommand(alice, null, "party", new String[]{"invite", "Bob"});
        assertEquals(1, calls);
        result.complete(new PartyOutcome(INVITED, Optional.of(partyId), Optional.of(inviteId)));
        drain();
        assertEquals(1, notifications.size());
        assertTrue(PlainTextComponentSerializer.plainText().serialize(notifications.getFirst()).contains("invited"));
    }

    @Test
    void reconnectBarrierRejectsActorAndInviteTargetAndHelpUsesConfiguredGrace() {
        var admitted = new java.util.HashSet<>(Set.of(aliceId, bobId));
        var gated = new PartyCommand(server, sessions, service, new CommandFeedback(server, sessions, main::add),
                Duration.ofSeconds(5), LoggerFactory.getLogger(getClass()), () -> enabled, ignored -> menus++,
                admitted::contains, Duration.ofSeconds(47));
        admitted.remove(aliceId);
        gated.onCommand(alice, null, "party", new String[]{"create"});
        assertTrue(last().contains("loading or unavailable"));
        assertEquals(0, calls);
        admitted.add(aliceId);
        admitted.remove(bobId);
        gated.onCommand(alice, null, "party", new String[]{"invite", "Bob"});
        assertTrue(last().contains("loading or unavailable"));
        assertEquals(0, calls);
        gated.onCommand(alice, null, "party", new String[]{"help"});
        assertTrue(last().contains("47 seconds"));
    }

    @Test
    void permissionConsoleDisabledAndLoadingNeverReachStorageOrMenu() {
        permission = false;
        invoke("create");
        assertTrue(last().contains("permission"));
        permission = true;
        var console = proxy(CommandSender.class, (method, args) -> switch (method) {
            case "hasPermission" -> true;
            case "sendMessage" -> { replies.add((Component) args[0]); yield null; }
            default -> throw new AssertionError(method);
        });
        command.onCommand(console, null, "party", new String[]{"create"});
        assertTrue(last().contains("player"));
        enabled = false;
        invoke();
        assertTrue(last().contains("disabled"));
        enabled = true;
        sessions.beginRefresh(aliceId);
        invoke("create");
        assertTrue(last().contains("loading"));
        assertEquals(0, calls);
        assertEquals(0, menus);
    }

    @Test
    void noArgsDelegatesMenuAndHelpExplainsEphemeralLifetimeWithoutSql() {
        invoke();
        assertEquals(1, menus);
        invoke("help");
        assertTrue(last().contains("restart"));
        assertTrue(last().contains("300 seconds"));
        assertEquals(0, calls);
    }

    @Test
    void rejectsMalformedSyntaxNamesPagesAndUuidAbbreviations() {
        for (String[] args : List.of(new String[]{"wrong"}, new String[]{"create", "extra"},
                new String[]{"invite"}, new String[]{"invite", "Missing"}, new String[]{"invite", bobId.toString()},
                new String[]{"invite", "Alice"}, new String[]{"accept", "Bob"},
                new String[]{"accept", "1-1-1-1-1"}, new String[]{"deny", "bad"},
                new String[]{"kick", "Missing"}, new String[]{"promote", "Alice"},
                new String[]{"invites", "0"}, new String[]{"invites", "1000001"},
                new String[]{"invites", "overflow"}, new String[]{"invites", "1", "extra"})) {
            invoke(args);
        }
        assertEquals(15, replies.size());
        assertEquals(0, calls);
        sessions.beginRefresh(bobId);
        invoke("invite", "Bob");
        assertTrue(last().contains("Target profile"));
        assertEquals(0, calls);
    }

    @Test
    void inviteRepliesAndClickableExactIdArriveOnlyAfterCommitOnMainThread() {
        invoke("invite", "Bob");
        assertEquals("invite", operation);
        assertEquals(aliceId, arguments[0]);
        assertEquals(bobId, arguments[1]);
        drain();
        assertTrue(replies.isEmpty());
        assertTrue(notifications.isEmpty());
        result.complete(invited());
        assertTrue(replies.isEmpty());
        drain();
        assertTrue(last().contains("saved"));
        assertTrue(plain(notifications.getFirst()).contains("Alice invited"));
        var buttons = notifications.getLast();
        assertEquals(ClickEvent.runCommand("/party accept " + inviteId), buttons.clickEvent());
        assertTrue(hasClick(buttons, "/party deny " + inviteId));
        assertTrue(plain(buttons).contains(inviteId.toString()));
    }

    @Test
    void duplicatesAndGenericPrivateRejectionsNeverNotifyOrExposeBlocks() {
        for (var code : List.of(ALREADY_INVITED, BLOCKED_OR_RESTRICTED, RATE_LIMITED)) {
            result = new CompletableFuture<>();
            invoke("invite", "Bob");
            result.complete(PartyOutcome.of(code));
            drain();
            assertEquals(PartyCommand.describe(code), last());
            assertFalse(last().toLowerCase().contains("block"));
            assertTrue(notifications.isEmpty());
        }
        for (var code : PartyOutcome.Code.values()) {
            assertFalse(PartyCommand.describe(code).isBlank());
        }
    }

    @Test
    void mapsAllMutationsAndUsesAuthoritativeInviteAndMemberUuids() {
        invoke("create");
        assertEquals("create", operation);
        invoke("accept", inviteId.toString());
        assertEquals("accept", operation);
        assertEquals(inviteId, arguments[1]);
        invoke("deny", inviteId.toString());
        assertEquals("deny", operation);
        invoke("kick", bobId.toString());
        assertEquals("kick", operation);
        assertEquals(bobId, arguments[1]);
        invoke("promote", "Bob");
        assertEquals("promote", operation);
        assertEquals(bobId, arguments[1]);
        sessions.beginRefresh(bobId);
        invoke("leave");
        assertEquals("leave", operation);
        assertEquals(Set.of(aliceId), arguments[1]);
        invoke("disband");
        assertEquals("disband", operation);
        assertEquals(aliceId, arguments[0]);
    }

    @Test
    void membersShowLocalPresenceNamesLeaderAndCapacityFromSnapshot() {
        party = Optional.of(new Party(partyId, UUID.randomUUID(), aliceId, Instant.EPOCH, Instant.EPOCH, 1, 8,
                List.of(new PartyMember(aliceId, "Alice", Instant.EPOCH), new PartyMember(bobId, "Renamed", Instant.EPOCH))));
        invoke("list");
        assertTrue(replies.isEmpty());
        drain();
        assertTrue(plain(replies.getFirst()).contains("2/8"));
        assertTrue(replies.stream().anyMatch(reply -> plain(reply).contains("[Leader] Alice")));
        assertTrue(replies.stream().anyMatch(reply -> plain(reply).contains("Renamed") && plain(reply).contains("online")));
        bobConnected = false;
        replies.clear();
        invoke("list");
        drain();
        assertTrue(replies.stream().anyMatch(reply -> plain(reply).contains("offline")
                && plain(reply).contains(bobId.toString())));
        party = Optional.empty();
        invoke("list");
        drain();
        assertTrue(last().contains("not in a party"));
    }

    @Test
    void incomingInvitesArePagedAndUseExactClickableIds() {
        invitations = List.of(new PartyInvite(inviteId, partyId, bobId, aliceId, Instant.EPOCH, Instant.EPOCH.plusSeconds(120)));
        invoke("invites", "2");
        assertEquals("getInvites", operation);
        assertEquals(10, arguments[1]);
        assertEquals(10, arguments[2]);
        drain();
        assertTrue(plain(replies.getFirst()).contains("page 2"));
        assertTrue(replies.stream().anyMatch(reply -> hasClick(reply, "/party accept " + inviteId)));
        invitations = List.of();
        invoke("invites");
        drain();
        assertTrue(last().contains("none"));
    }

    @Test
    void quitRejoinOrSubsystemShutdownDropsOldRepliesAndRecipientNotifications() {
        invoke("invite", "Bob");
        sessions.close(aliceId);
        sessions.close(bobId);
        join();
        result.complete(invited());
        drain();
        assertTrue(replies.isEmpty());
        assertTrue(notifications.isEmpty());
        result = new CompletableFuture<>();
        invoke("invite", "Bob");
        enabled = false;
        result.complete(invited());
        drain();
        assertTrue(replies.isEmpty());
        assertTrue(notifications.isEmpty());
    }

    @Test
    void revokedPermissionUnavailableProfilesAndDisconnectedPlayersDropCallbacks() {
        invoke("invite", "Bob");
        permission = false;
        result.complete(invited());
        drain();
        assertTrue(replies.isEmpty());
        assertTrue(notifications.isEmpty());
        permission = true;
        result = new CompletableFuture<>();
        invoke("invite", "Bob");
        sessions.beginRefresh(aliceId);
        sessions.beginRefresh(bobId);
        result.complete(invited());
        drain();
        assertTrue(replies.isEmpty());
        assertTrue(notifications.isEmpty());
        join();
        result = new CompletableFuture<>();
        invoke("invite", "Bob");
        aliceConnected = false;
        bobConnected = false;
        result.complete(invited());
        drain();
        assertTrue(replies.isEmpty());
        assertTrue(notifications.isEmpty());
    }

    @Test
    void storageFailureShowsUncertainOutcomeWithoutFalseSuccessOrNotification() {
        invoke("invite", "Bob");
        result.completeExceptionally(new IllegalStateException("simulated outage"));
        drain();
        assertTrue(last().contains("not confirmed"));
        assertTrue(last().contains("may still complete"));
        assertTrue(notifications.isEmpty());
    }

    @Test
    void timeoutLeavesAcceptedWriteAliveAndLateCommitCanNotifyOriginalRecipient() throws Exception {
        var callbackQueued = new CountDownLatch(1);
        var feedback = new CommandFeedback(server, sessions, task -> { main.add(task); callbackQueued.countDown(); });
        var timed = new PartyCommand(server, sessions, service, feedback, Duration.ofMillis(100),
                LoggerFactory.getLogger(getClass()), () -> enabled, ignored -> menus++);
        timed.onCommand(alice, null, "party", new String[]{"invite", "Bob"});
        assertTrue(callbackQueued.await(5, TimeUnit.SECONDS));
        drain();
        assertFalse(result.isDone());
        assertTrue(last().contains("not confirmed"));
        assertTrue(notifications.isEmpty());
        result.complete(invited());
        drain();
        assertTrue(last().contains("not confirmed"));
        assertFalse(notifications.isEmpty());
    }

    @Test
    void realCommandsPersistInviteAcceptPromoteLeaveAndEnforcePrivacyInPostgres() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().start()) {
            var data = new PGSimpleDataSource();
            data.setServerNames(new String[]{"127.0.0.1"});
            data.setPortNumbers(new int[]{postgres.getPort()});
            data.setDatabaseName("postgres");
            data.setUser("postgres");
            Migrations.apply(data, getClass().getClassLoader());
            var profiles = new JdbcProfileRepository(data);
            profiles.upsertOnJoin(aliceId, "Alice");
            profiles.upsertOnJoin(bobId, "Bob");
            try (var lease = PartyEpochLease.acquire(data, "lobby")) {
                var repository = new JdbcPartyRepository(data, lease, PartyPolicy.DEFAULT);
                var real = DefaultPartyService.create(repository, 1, 10, LoggerFactory.getLogger(getClass()));
                var wired = new PartyCommand(server, sessions, real, new CommandFeedback(server, sessions, main::add),
                        Duration.ofSeconds(5), LoggerFactory.getLogger(getClass()), () -> enabled, ignored -> menus++);
                try {
                    wired.onCommand(alice, null, "party", new String[]{"invite", "Bob"});
                    var invites = real.getInvites(bobId, 0, 10).get(5, TimeUnit.SECONDS);
                    drain();
                    assertEquals(1, invites.size());
                    UUID savedId = invites.getFirst().id();
                    assertTrue(hasClick(notifications.getLast(), "/party accept " + savedId));
                    wired.onCommand(bob, null, "party", new String[]{"accept", savedId.toString()});
                    var joined = real.getParty(bobId).get(5, TimeUnit.SECONDS).orElseThrow();
                    drain();
                    assertEquals(2, joined.members().size());
                    assertTrue(plain(notifications.getLast()).contains("joined"));
                    wired.onCommand(alice, null, "party", new String[]{"promote", "Bob"});
                    assertEquals(bobId, real.getParty(aliceId).get(5, TimeUnit.SECONDS).orElseThrow().leaderId());
                    drain();
                    assertTrue(last().contains("transferred"));
                    wired.onCommand(alice, null, "party", new String[]{"leave"});
                    assertTrue(real.getParty(aliceId).get(5, TimeUnit.SECONDS).isEmpty());
                    drain();
                    assertTrue(last().contains("left"));
                    wired.onCommand(bob, null, "party", new String[]{"disband"});
                    assertTrue(real.getParty(bobId).get(5, TimeUnit.SECONDS).isEmpty());
                    drain();
                    new JdbcPartyPreferencesRepository(data).save(bobId, false);
                    int notificationsBefore = notifications.size();
                    wired.onCommand(alice, null, "party", new String[]{"invite", "Bob"});
                    assertTrue(real.getParty(aliceId).get(5, TimeUnit.SECONDS).isEmpty());
                    drain();
                    assertEquals(PartyCommand.describe(BLOCKED_OR_RESTRICTED), last());
                    assertEquals(notificationsBefore, notifications.size());
                    assertTrue(repository.getInvites(bobId, 0, 10).isEmpty());
                } finally {
                    assertEquals(0, real.shutdown(Duration.ofSeconds(5)));
                }
            }
        }
    }

    @Test
    void tabCompletionIsPermissionReadinessAndEnableGatedAndDoesNotQueryStorage() {
        assertEquals(List.of("invite", "invites"), command.onTabComplete(alice, null, "party", new String[]{"inv"}));
        assertEquals(List.of("Bob"), command.onTabComplete(alice, null, "party", new String[]{"invite", "b"}));
        assertTrue(command.onTabComplete(alice, null, "party", new String[]{"accept", ""}).isEmpty());
        sessions.beginRefresh(bobId);
        assertTrue(command.onTabComplete(alice, null, "party", new String[]{"kick", ""}).isEmpty());
        enabled = false;
        assertTrue(command.onTabComplete(alice, null, "party", new String[]{""}).isEmpty());
        enabled = true;
        permission = false;
        assertTrue(command.onTabComplete(alice, null, "party", new String[]{""}).isEmpty());
        assertEquals(0, calls);
    }

    private PartyCommand command(Duration timeout) {
        return new PartyCommand(server, sessions, service, new CommandFeedback(server, sessions, main::add),
                timeout, LoggerFactory.getLogger(getClass()), () -> enabled, ignored -> menus++);
    }
    private PartyOutcome invited() { return new PartyOutcome(INVITED, Optional.of(partyId), Optional.of(inviteId)); }
    private void invoke(String... args) { assertTrue(command.onCommand(alice, null, "party", args)); }
    private void drain() { while (!main.isEmpty()) { main.remove().run(); } }
    private void ready(UUID id, String name) {
        sessions.markReady(id, sessions.begin(id).token(), PlayerProfile.newProfile(id, name, Instant.EPOCH));
    }
    private String last() { return plain(replies.getLast()); }
    private static String plain(Component value) { return PlainTextComponentSerializer.plainText().serialize(value); }
    private static boolean hasClick(Component component, String value) {
        return ClickEvent.runCommand(value).equals(component.clickEvent())
                || component.children().stream().anyMatch(child -> hasClick(child, value));
    }
    private Player player(UUID id, String name, List<Component> output) {
        return proxy(Player.class, (method, args) -> switch (method) {
            case "getUniqueId" -> id;
            case "getName" -> name;
            case "hasPermission" -> permission;
            case "sendMessage" -> { output.add((Component) args[0]); yield null; }
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
