package com.inpuzah.club.core.party.chat;

import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.party.PartyPolicy;
import com.inpuzah.club.core.party.model.Party;
import com.inpuzah.club.core.party.model.PartyMember;
import com.inpuzah.club.core.party.repository.JdbcPartyRepository;
import com.inpuzah.club.core.party.repository.PartyEpochLease;
import com.inpuzah.club.core.party.repository.PartyException;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
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
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class PartyChatCommandTest {
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    private final ProfileSessionManager profiles = new ProfileSessionManager();
    private final LinkedBlockingQueue<Runnable> main = new LinkedBlockingQueue<>();
    private final Map<UUID, Player> online = new HashMap<>();
    private final Map<UUID, List<Component>> messages = new HashMap<>();
    private final Set<UUID> denied = new HashSet<>(), muted = new HashSet<>(), notAdmitted = new HashSet<>();
    private final AtomicLong time = new AtomicLong();
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
    private CompletableFuture<Void> work;
    private boolean enabled = true, cancel;
    private int calls, moderationCalls;
    private RuntimeException failure;
    private final Player alice = player(a, "Alice"), bob = player(b, "Bob");
    private final Party party = new Party(UUID.randomUUID(), UUID.randomUUID(), a, Instant.EPOCH, Instant.EPOCH,
            0, 8, List.of(new PartyMember(a, "Alice", Instant.EPOCH), new PartyMember(b, "Bob", Instant.EPOCH)));
    private final Server server = proxy(Server.class, (method, args) -> switch (method) {
        case "getPlayer" -> online.get((UUID) args[0]);
        default -> throw new AssertionError(method);
    });
    private final PartyService service = proxy(PartyService.class, (method, args) -> {
        assertEquals("withParty", method);
        calls++;
        work = CompletableFuture.runAsync(() -> {
            if (failure != null) { throw failure; }
            deliver(args[1]);
        }, worker);
        return work.copy();
    });
    private final PartyChatCommand chat = command(service);

    @AfterEach void stop() throws Exception {
        chat.close();
        worker.shutdownNow();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test void literalMessageDeliveredOnlyOnMainToCurrentMembers() throws Exception {
        send("<red>Hello</red> @everyone /kick %coins%");
        assertTrue(messages.get(b).isEmpty());
        finish();
        assertEquals("[Party] Alice: <red>Hello</red> @everyone /kick %coins%", last(b));
        assertEquals(last(a), last(b));
        assertEquals(1, moderationCalls);
        assertNull(messages.get(b).getLast().clickEvent());
    }

    @Test void moderationCancellationNeverDeliversAndDoesNotLogMessage() throws Exception {
        cancel = true;
        send("secret");
        finish();
        assertTrue(messages.get(b).isEmpty());
        assertTrue(last(a).contains("blocked by moderation"));
    }

    @Test void exact256CodeUnitBoundaryIsAccepted() throws Exception {
        send("x".repeat(256));
        finish();
        assertEquals("[Party] Alice: " + "x".repeat(256), last(b));
    }

    @Test void mutePermissionBlocksSendButNotReception() throws Exception {
        muted.add(a);
        send("blocked");
        assertEquals(0, calls);
        muted.remove(a);
        muted.add(b);
        send("allowed");
        finish();
        assertTrue(last(b).contains("allowed"));
    }

    @Test void permissionAdmissionAndReadyProfileAreRequiredForEveryRecipient() throws Exception {
        notAdmitted.add(b);
        send("no leak");
        finish();
        assertTrue(messages.get(b).isEmpty());
        time.addAndGet(Duration.ofSeconds(1).toNanos());
        notAdmitted.clear();
        denied.add(b);
        send("still no leak");
        finish();
        assertTrue(messages.get(b).isEmpty());
    }

    @Test void senderRejoinBeforeDeliverySuppressesOldMessage() throws Exception {
        send("old login");
        ready(a, "Alice");
        finish();
        assertTrue(messages.get(b).isEmpty());
    }

    @Test void recipientRejoinBeforeMainCallbackDoesNotReceiveOldMessage() throws Exception {
        send("old audience");
        Runnable task = main.poll(5, TimeUnit.SECONDS);
        assertNotNull(task);
        ready(b, "Bob");
        task.run();
        work.get(5, TimeUnit.SECONDS);
        drain();
        assertTrue(messages.get(b).isEmpty());
    }

    @Test void disabledAfterSubmissionOrClosedNeverDelivers() throws Exception {
        send("disabled");
        enabled = false;
        finish();
        assertTrue(messages.get(b).isEmpty());
        enabled = true;
        time.addAndGet(Duration.ofSeconds(1).toNanos());
        send("closed");
        chat.close();
        finish();
        assertTrue(messages.get(b).isEmpty());
    }

    @Test void emptyOverlongAndControlOrFormatCharactersDoNotReachStorage() {
        for (String invalid : List.of("", " ", "x".repeat(257), "line\nbreak", "bad\u0000", "bad\u202E",
                "line\u2028break", "paragraph\u2029break", "bad\uD800")) {
            send(invalid);
        }
        assertEquals(0, calls);
        assertTrue(last(a).contains("Usage:"));
    }

    @Test void rateLimitAndSinglePendingBoundSubmissionsAndAllowExactBoundary() throws Exception {
        send("one");
        time.addAndGet(Duration.ofSeconds(2).toNanos());
        send("pending");
        assertEquals(1, calls);
        finish();
        send("two");
        finish();
        send("too soon");
        assertEquals(2, calls);
        time.addAndGet(Duration.ofSeconds(1).toNanos());
        send("boundary");
        finish();
        assertEquals(3, calls);
    }

    @Test void expiredMainCallbackIsCancelledBeforeMembershipLockCanBeReleased() throws Exception {
        send("expired");
        assertThrows(java.util.concurrent.ExecutionException.class, () -> work.get(4, TimeUnit.SECONDS));
        drain();
        assertTrue(messages.get(b).isEmpty());
        assertEquals(0, moderationCalls);
        assertTrue(last(a).contains("not confirmed"));
    }

    @Test void notInPartyReturnsExplicitFeedbackWithoutModerationOrDelivery() throws Exception {
        failure = new PartyException(PartyException.Code.NOT_IN_PARTY, "Not a member");
        send("not a member");
        assertThrows(java.util.concurrent.ExecutionException.class, () -> work.get(5, TimeUnit.SECONDS));
        drain();
        assertTrue(last(a).contains("not in a party"));
        assertEquals(0, moderationCalls);
    }

    @Test void realDatabaseChatNeverDeliversToCommittedFormerMembersOrClosedParty() throws Exception {
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
            try (var lease = PartyEpochLease.acquire(source, "chat-test")) {
                var real = DefaultPartyService.create(new JdbcPartyRepository(source, lease, PartyPolicy.DEFAULT),
                        1, 16, LoggerFactory.getLogger(getClass()));
                var command = command(real);
                try {
                    var invite = real.invite(a, b).get(5, TimeUnit.SECONDS).inviteId().orElseThrow();
                    real.accept(b, invite).get(5, TimeUnit.SECONDS);
                    command.onCommand(alice, null, "pc", new String[]{"before"});
                    var task = main.poll(5, TimeUnit.SECONDS);
                    assertNotNull(task);
                    task.run();
                    real.getParty(a).get(5, TimeUnit.SECONDS);
                    drain();
                    assertTrue(last(b).endsWith("before"));
                    int received = messages.get(b).size();
                    real.kick(a, b).get(5, TimeUnit.SECONDS);
                    time.addAndGet(Duration.ofSeconds(1).toNanos());
                    command.onCommand(alice, null, "pc", new String[]{"after kick"});
                    task = main.poll(5, TimeUnit.SECONDS);
                    assertNotNull(task);
                    task.run();
                    real.getParty(a).get(5, TimeUnit.SECONDS);
                    drain();
                    assertEquals(received, messages.get(b).size());
                    real.disband(a).get(5, TimeUnit.SECONDS);
                    time.addAndGet(Duration.ofSeconds(1).toNanos());
                    command.onCommand(alice, null, "pc", new String[]{"after disband"});
                    real.getParty(a).get(5, TimeUnit.SECONDS);
                    drain();
                    assertTrue(last(a).contains("not in a party"));
                    assertEquals(received, messages.get(b).size());
                } finally {
                    command.close();
                    real.shutdown(Duration.ofSeconds(5));
                }
            }
        }
    }

    private PartyChatCommand command(PartyService source) {
        return new PartyChatCommand(server, profiles, source, main::add, () -> enabled, id -> !notAdmitted.contains(id),
                LoggerFactory.getLogger(getClass()), event -> { moderationCalls++; event.setCancelled(cancel); }, time::get);
    }
    private void send(String message) { chat.onCommand(alice, null, "pc", new String[]{message}); }
    private void finish() throws Exception {
        var task = main.poll(5, TimeUnit.SECONDS);
        assertNotNull(task);
        task.run();
        try { work.get(5, TimeUnit.SECONDS); } catch (java.util.concurrent.ExecutionException expected) {
            assertInstanceOf(IllegalStateException.class, expected.getCause());
        }
        worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
        drain();
    }
    @SuppressWarnings("unchecked")
    private void deliver(Object callback) { ((Consumer<Party>) callback).accept(party); }
    private void ready(UUID id, String name) {
        profiles.markReady(id, profiles.begin(id).token(), PlayerProfile.newProfile(id, name, Instant.EPOCH));
    }
    private Player player(UUID id, String name) {
        ready(id, name);
        messages.put(id, new ArrayList<>());
        Player player = proxy(Player.class, (method, args) -> switch (method) {
            case "getUniqueId" -> id;
            case "getName" -> name;
            case "hasPermission" -> args[0].equals("club.party.muted") ? muted.contains(id) : !denied.contains(id);
            case "sendMessage" -> { messages.get(id).add((Component) args[0]); yield null; }
            default -> throw new AssertionError(method);
        });
        online.put(id, player);
        return player;
    }
    private void drain() { Runnable task; while ((task = main.poll()) != null) { task.run(); } }
    private String last(UUID id) { return PlainTextComponentSerializer.plainText().serialize(messages.get(id).getLast()); }
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
