package com.inpuzah.club.core.social;

import com.inpuzah.club.core.command.CommandFeedback;
import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.session.ProfileSessionManager;
import com.inpuzah.club.core.social.command.SocialCommand;
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
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SocialCommandTest {
    private final UUID aliceId = UUID.randomUUID();
    private final UUID bobId = UUID.randomUUID();
    private final List<String> aliceMessages = new ArrayList<>();
    private final List<String> bobMessages = new ArrayList<>();
    private final Queue<Runnable> main = new ConcurrentLinkedQueue<>();
    private final ProfileSessionManager sessions = new ProfileSessionManager();
    private boolean permitted = true;
    private boolean connected = true;
    private boolean bobConnected = true;
    private final Player alice = player(aliceId, "Alice", aliceMessages);
    private final Player bob = player(bobId, "Bob", bobMessages);
    private final Server server = proxy(Server.class, (method, args) -> switch (method) {
        case "getPlayerExact" -> "Bob".equals(args[0]) && bobConnected ? bob : null;
        case "getPlayer" -> aliceId.equals(args[0]) ? (connected ? alice : null) : bobConnected ? bob : null;
        case "getOnlinePlayers" -> List.of(alice, bob);
        default -> throw new AssertionError(method);
    });
    private final FakeSocial social = new FakeSocial();
    private final CommandFeedback feedback = new CommandFeedback(server, sessions, main::add);

    @BeforeEach
    void join() {
        sessions.markReady(aliceId, sessions.begin(aliceId).token(), PlayerProfile.newProfile(aliceId, "Alice", Instant.EPOCH));
        sessions.markReady(bobId, sessions.begin(bobId).token(), PlayerProfile.newProfile(bobId, "Bob", Instant.EPOCH));
    }

    @Test
    void permissionsConsoleAndLoadingProfileNeverReachStorage() {
        permitted = false;
        invoke(SocialCommand.Kind.FRIEND, "add", "Bob");
        assertTrue(aliceMessages.getLast().contains("permission"));
        permitted = true;
        var console = proxy(CommandSender.class, (method, args) -> {
            if (method.equals("hasPermission")) { return true; }
            if (method.equals("sendMessage")) { aliceMessages.add(plain((Component) args[0])); return null; }
            throw new AssertionError(method);
        });
        command(SocialCommand.Kind.FRIENDS).onCommand(console, null, "", new String[0]);
        assertTrue(aliceMessages.getLast().contains("player"));
        sessions.beginRefresh(aliceId);
        invoke(SocialCommand.Kind.FRIEND, "add", "Bob");
        assertTrue(aliceMessages.getLast().contains("loading"));
        assertEquals(0, social.calls);
    }

    @Test
    void validatesSyntaxPagesAndOnlineTargetsWithoutOfflineIdentityLookup() {
        invoke(SocialCommand.Kind.FRIEND);
        invoke(SocialCommand.Kind.FRIEND, "wrong", "Bob");
        invoke(SocialCommand.Kind.FRIEND, "add", "Missing");
        invoke(SocialCommand.Kind.FRIEND, "add", bobId.toString());
        invoke(SocialCommand.Kind.FRIEND, "accept", "1-1-1-1-1");
        invoke(SocialCommand.Kind.FRIENDS, "-1");
        invoke(SocialCommand.Kind.FRIENDS, "1000001");
        invoke(SocialCommand.Kind.BLOCK, "Missing");
        invoke(SocialCommand.Kind.BLOCKED, "1", "extra");
        assertEquals(9, aliceMessages.size());
        assertEquals(0, social.calls);
        sessions.beginRefresh(bobId);
        invoke(SocialCommand.Kind.FRIEND, "add", "Bob");
        assertTrue(aliceMessages.getLast().contains("Target profile"));
    }

    @Test
    void requestRepliesAndRecipientNotificationWaitForCommitAndMainThread() {
        invoke(SocialCommand.Kind.FRIEND, "add", "Bob");
        assertEquals(SocialAction.REQUEST, social.action);
        assertEquals(aliceId, social.actor);
        assertEquals(bobId, social.other);
        drain();
        assertTrue(aliceMessages.isEmpty());
        assertTrue(bobMessages.isEmpty());
        social.result.complete(SocialResult.REQUEST_SENT);
        assertTrue(aliceMessages.isEmpty());
        drain();
        assertTrue(aliceMessages.getLast().contains("saved"));
        assertTrue(bobMessages.getLast().contains("Alice sent"));
    }

    @Test
    void duplicatesAndPrivateBlockRejectionsDoNotSpamOrRevealBlockDetails() {
        invoke(SocialCommand.Kind.FRIEND, "add", "Bob");
        social.result.complete(SocialResult.ALREADY_REQUESTED);
        drain();
        assertTrue(aliceMessages.getLast().contains("already"));
        assertTrue(bobMessages.isEmpty());
        social.result = new CompletableFuture<>();
        invoke(SocialCommand.Kind.FRIEND, "add", "Bob");
        social.result.complete(SocialResult.CANNOT_REQUEST);
        drain();
        assertTrue(aliceMessages.getLast().contains("cannot be sent"));
        assertFalse(aliceMessages.getLast().toLowerCase().contains("block"));
        assertTrue(bobMessages.isEmpty());
    }

    @Test
    void mapsAllFriendshipAndBlockCommandsIncludingKnownOfflineUuids() {
        for (var entry : List.of(
                Map.entry("accept", SocialAction.ACCEPT), Map.entry("deny", SocialAction.DENY),
                Map.entry("cancel", SocialAction.CANCEL), Map.entry("remove", SocialAction.REMOVE))) {
            invoke(SocialCommand.Kind.FRIEND, entry.getKey(), bobId.toString());
            assertEquals(entry.getValue(), social.action);
            assertEquals(bobId, social.other);
        }
        invoke(SocialCommand.Kind.BLOCK, "Bob");
        assertEquals(SocialAction.BLOCK, social.action);
        invoke(SocialCommand.Kind.UNBLOCK, bobId.toString());
        assertEquals(SocialAction.UNBLOCK, social.action);
    }

    @Test
    void listReadsArePagedAndPresenceIsLocalOnly() {
        social.friends = List.of(new FriendSummary(bobId, "Bob"));
        invoke(SocialCommand.Kind.FRIENDS, "2");
        assertEquals(10, social.offset);
        assertEquals(10, social.limit);
        drain();
        assertTrue(aliceMessages.getLast().contains("online"));
        bobConnected = false;
        invoke(SocialCommand.Kind.FRIENDS);
        drain();
        assertTrue(aliceMessages.getLast().contains("offline"));
        assertTrue(aliceMessages.getLast().contains(bobId.toString()));
        social.requests = List.of(new FriendRequest(bobId, "Bob", Instant.now().plusSeconds(100)));
        invoke(SocialCommand.Kind.FRIEND, "requests");
        drain();
        assertTrue(aliceMessages.getLast().contains(bobId.toString()));
        invoke(SocialCommand.Kind.BLOCKED);
        drain();
        assertTrue(aliceMessages.getLast().contains("none"));
    }

    @Test
    void disconnectRejoinDiscardsSenderAndRecipientCallbacks() {
        invoke(SocialCommand.Kind.FRIEND, "add", "Bob");
        sessions.close(aliceId);
        sessions.close(bobId);
        join();
        social.result.complete(SocialResult.REQUEST_SENT);
        drain();
        assertTrue(aliceMessages.isEmpty());
        assertTrue(bobMessages.isEmpty());
        social.result = new CompletableFuture<>();
        invoke(SocialCommand.Kind.FRIEND, "add", "Bob");
        connected = false;
        bobConnected = false;
        social.result.complete(SocialResult.REQUEST_SENT);
        drain();
        assertTrue(aliceMessages.isEmpty());
        assertTrue(bobMessages.isEmpty());
    }

    @Test
    void failuresNeverClaimSuccessOrExposeDatabaseDetails() {
        invoke(SocialCommand.Kind.FRIEND, "add", "Bob");
        social.result.completeExceptionally(new IllegalStateException("private SQL details"));
        drain();
        assertTrue(aliceMessages.getLast().contains("temporarily unavailable"));
        assertFalse(aliceMessages.getLast().contains("SQL"));
        assertTrue(bobMessages.isEmpty());
    }

    @Test
    void timeoutDoesNotCancelOriginalSaveOrSuppressLateRecipientNotification() throws Exception {
        var scheduled = new CountDownLatch(1);
        var delayedFeedback = new CommandFeedback(server, sessions, task -> { main.add(task); scheduled.countDown(); });
        var command = new SocialCommand(SocialCommand.Kind.FRIEND, server, sessions, social, delayedFeedback,
                Duration.ofMillis(1), LoggerFactory.getLogger(getClass()));
        command.onCommand(alice, null, "", new String[]{"add", "Bob"});
        assertTrue(scheduled.await(5, TimeUnit.SECONDS));
        drain();
        assertTrue(aliceMessages.getLast().contains("timed out"));
        assertFalse(social.result.isDone());
        social.result.complete(SocialResult.REQUEST_SENT);
        drain();
        assertTrue(bobMessages.getLast().contains("Alice sent"));
    }

    @Test
    void tabCompletionUsesOnlyReadyOnlineTargetsAndPermission() {
        var command = command(SocialCommand.Kind.FRIEND);
        assertEquals(List.of("add", "accept"), command.onTabComplete(alice, null, "", new String[]{"a"}));
        assertEquals(List.of("Bob"), command.onTabComplete(alice, null, "", new String[]{"add", "B"}));
        sessions.beginRefresh(bobId);
        assertTrue(command.onTabComplete(alice, null, "", new String[]{"add", ""}).isEmpty());
        permitted = false;
        assertTrue(command.onTabComplete(alice, null, "", new String[]{""}).isEmpty());
    }

    private SocialCommand command(SocialCommand.Kind kind) {
        return new SocialCommand(kind, server, sessions, social, feedback, Duration.ofSeconds(5),
                LoggerFactory.getLogger(getClass()));
    }
    private void invoke(SocialCommand.Kind kind, String... args) { command(kind).onCommand(alice, null, "", args); }
    private void drain() { while (!main.isEmpty()) { main.remove().run(); } }
    private Player player(UUID uuid, String name, List<String> messages) {
        return proxy(Player.class, (method, args) -> switch (method) {
            case "getUniqueId" -> uuid;
            case "getName" -> name;
            case "hasPermission" -> permitted;
            case "sendMessage" -> { messages.add(plain((Component) args[0])); yield null; }
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

    private static final class FakeSocial implements SocialService {
        @Override public CompletableFuture<Relationship> relationship(UUID player, UUID other) {
            throw new AssertionError();
        }
        int calls;
        int offset;
        int limit;
        SocialAction action;
        UUID actor;
        UUID other;
        CompletableFuture<SocialResult> result = new CompletableFuture<>();
        List<FriendSummary> friends = List.of();
        List<FriendRequest> requests = List.of();
        @Override public CompletableFuture<SocialResult> mutate(SocialAction action, UUID actor, UUID other) {
            calls++;
            this.action = action;
            this.actor = actor;
            this.other = other;
            return result;
        }
        @Override public CompletableFuture<List<FriendSummary>> listFriends(UUID player, int offset, int limit) {
            calls++; this.offset = offset; this.limit = limit; return CompletableFuture.completedFuture(friends);
        }
        @Override public CompletableFuture<List<FriendRequest>> listIncomingRequests(UUID player, int offset, int limit) {
            calls++; this.offset = offset; this.limit = limit; return CompletableFuture.completedFuture(requests);
        }
        @Override public CompletableFuture<List<FriendSummary>> listBlocked(UUID player, int offset, int limit) {
            calls++; return CompletableFuture.completedFuture(List.of());
        }
        @Override public CompletableFuture<SocialPreferences> preferences(UUID player) { throw new AssertionError(); }
        @Override public CompletableFuture<SocialPreferences> setPreferences(UUID player, boolean enabled) { throw new AssertionError(); }
        @Override public int shutdown(Duration timeout) { throw new AssertionError(); }
    }
}
