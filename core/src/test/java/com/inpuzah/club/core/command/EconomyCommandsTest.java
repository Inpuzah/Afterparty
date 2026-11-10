package com.inpuzah.club.core.command;

import com.inpuzah.club.core.config.AdminCommandConfig;
import com.inpuzah.club.core.config.ProfileConfig;
import com.inpuzah.club.core.economy.BalanceSnapshot;
import com.inpuzah.club.core.economy.CurrencyOperation;
import com.inpuzah.club.core.economy.CurrencyOperationResult;
import com.inpuzah.club.core.economy.CurrencyOperationType;
import com.inpuzah.club.core.economy.EconomyException;
import com.inpuzah.club.core.economy.EconomyService;
import com.inpuzah.club.core.progression.ProgressionException;
import com.inpuzah.club.core.progression.ProgressionService;
import com.inpuzah.club.core.progression.ProgressionSnapshot;
import com.inpuzah.club.core.progression.XpAwardRequest;
import com.inpuzah.club.core.progression.XpAwardResult;
import com.inpuzah.club.core.repository.ProfileStorageException;
import com.inpuzah.club.core.session.ProfileSessionManager;
import com.inpuzah.club.core.session.SessionState;
import com.inpuzah.club.core.listener.PlayerSessionListener;
import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.service.ProfileRefreshService;
import com.inpuzah.club.core.service.ProfileService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyCommandsTest {
    private final UUID playerId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final Set<String> permissions = new HashSet<>(Set.of("club.balance.view", "club.admin.xp", "club.admin.coins"));
    private final List<String> messages = new ArrayList<>();
    private final Queue<Runnable> mainThread = new ConcurrentLinkedQueue<>();
    private final ProfileSessionManager sessions = new ProfileSessionManager();
    private final Player target = player(playerId, "Target");
    private final Player admin = player(adminId, "Admin");
    private boolean connected = true;
    private final Server server = proxy(Server.class, (method, args) -> switch (method) {
        case "getPlayerExact" -> "Target".equals(args[0]) ? target : null;
        case "getPlayer" -> connected && adminId.equals(args[0]) ? admin : null;
        case "getOnlinePlayers" -> List.of(target, admin);
        default -> throw new AssertionError(method);
    });
    private final CommandSender console = proxy(CommandSender.class, this::senderCall);
    private final FakeEconomy economy = new FakeEconomy();
    private final FakeProgression progression = new FakeProgression();
    private final CommandFeedback feedback = new CommandFeedback(server, sessions, mainThread::add);
    private final BalanceCommand balance = new BalanceCommand(
            economy, feedback, Duration.ofSeconds(1), LoggerFactory.getLogger(getClass()));
    private final ClubAdminCommand commands = commands(new AdminCommandConfig(0, 0));

    @Test
    void permissionsDenyBothKindsWithoutServiceCalls() {
        permissions.clear();
        invoke(console, "xp", "add", "Target", "10");
        invoke(console, "coins", "remove", "Target", "10");
        balance.onCommand(admin, null, "balance", new String[0]);
        assertEquals(3, messages.size());
        assertTrue(messages.stream().allMatch(message -> message.contains("permission")));
        assertNull(economy.operation);
        assertNull(progression.request);
        assertEquals(0, economy.reads);
        assertTrue(commands.onTabComplete(console, null, "clubadmin", new String[]{""}).isEmpty());
    }

    @Test
    void validatesSyntaxAmountsMetadataAndUnknownOfflineNames() {
        String[][] invalid = {
                {}, {"xp"}, {"xp", "remove", "Target", "10"}, {"coins", "set", "Target", "10"},
                {"coins", "add", "Unknown", "10"}, {"coins", "add", "1-1-1-1-1", "10"},
                {"xp", "add", "Target", "0"}, {"xp", "add", "Target", "-1"},
                {"xp", "add", "Target", "1.5"}, {"xp", "add", "Target", "+1"},
                {"xp", "add", "Target", "9223372036854775808"},
                {"coins", "add", "Target", "10", "x".repeat(256)},
                {"xp", "add", "Target", "10", "bad\nreason"}
        };
        for (String[] args : invalid) {
            invoke(console, args);
        }
        assertEquals(invalid.length, messages.size());
        assertNull(economy.operation);
        assertNull(progression.request);
    }

    @Test
    void optionalLimitsAreEnforcedWithoutChangingDomainRules() {
        ClubAdminCommand limited = commands(new AdminCommandConfig(10, 20));
        limited.onCommand(console, null, "clubadmin", new String[]{"xp", "add", "Target", "11"});
        limited.onCommand(console, null, "clubadmin", new String[]{"coins", "remove", "Target", "21"});
        assertNull(economy.operation);
        assertNull(progression.request);
        assertTrue(messages.get(0).contains("between 1 and 10"));
        assertTrue(messages.get(1).contains("between 1 and 20"));
    }

    @Test
    void unlimitedXpAcceptsLongMaxAndRepliesOnlyOnMainThreadAfterCommit() {
        sessions.begin(adminId);
        invoke(admin, "xp", "add", "Target", Long.toString(Long.MAX_VALUE), "Great", "work");
        assertTrue(messages.isEmpty());
        var request = progression.request;
        assertEquals(playerId, request.playerId());
        assertEquals(adminId, request.actorId());
        assertEquals(Long.MAX_VALUE, request.amount());
        assertEquals("admin-command", request.source());
        assertEquals("Great work", request.reason());
        progression.result.complete(new XpAwardResult(request, 0, Long.MAX_VALUE, false));
        assertTrue(messages.isEmpty());
        drain();
        assertTrue(messages.getFirst().contains(Long.toString(Long.MAX_VALUE)));
        assertTrue(messages.getFirst().contains("level 100"));
    }

    @Test
    void coinCreditAndDebitHaveAuditMetadataAndFreshOperationIds() {
        invoke(console, "coins", "add", playerId.toString(), "100");
        CurrencyOperation credit = economy.operation;
        assertNull(credit.actorId());
        assertEquals("Administrator command", credit.reason());
        assertEquals(CurrencyOperationType.CREDIT, credit.type());
        economy.result.complete(new CurrencyOperationResult(credit, CurrencyOperationResult.Status.SUCCESS, 0, 100, false));
        drain();
        assertTrue(messages.getFirst().contains("100 Club Coins"));

        economy.result = new CompletableFuture<>();
        invoke(admin, "coins", "remove", "Target", "75", "Purchase");
        assertEquals(CurrencyOperationType.DEBIT, economy.operation.type());
        assertEquals(adminId, economy.operation.actorId());
        assertEquals("Purchase", economy.operation.reason());
        assertNotEquals(credit.operationId(), economy.operation.operationId());
        economy.result.complete(new CurrencyOperationResult(economy.operation, CurrencyOperationResult.Status.SUCCESS, 100, 25, false));
        drain();
        assertTrue(messages.getLast().contains("25 Club Coins"));
    }

    @Test
    void insufficientFundsIsNotReportedAsSaved() {
        invoke(console, "coins", "remove", "Target", "75");
        economy.result.complete(new CurrencyOperationResult(economy.operation,
                CurrencyOperationResult.Status.INSUFFICIENT_FUNDS, 25, 25, false));
        drain();
        assertTrue(messages.getFirst().contains("Insufficient"));
        assertFalse(messages.getFirst().contains("saved"));
    }

    @Test
    void unknownUuidIsPassedToBackendWithoutCreatingProfile() {
        UUID unknown = UUID.randomUUID();
        invoke(console, "coins", "add", unknown.toString(), "10");
        assertEquals(unknown, economy.operation.playerId());
        economy.result.completeExceptionally(new EconomyException(EconomyException.Code.PROFILE_NOT_FOUND, "missing"));
        drain();
        assertEquals("That player has no saved profile.", messages.getFirst());
    }

    @Test
    void databaseFailureDoesNotLeakSqlOrClaimFailureWasUncommitted() {
        invoke(console, "xp", "add", "Target", "10");
        progression.result.completeExceptionally(new ProfileStorageException("secret SQL details", null));
        drain();
        assertTrue(messages.getFirst().contains("may have committed"));
        assertTrue(messages.getFirst().contains(progression.request.operationId().toString()));
        assertFalse(messages.getFirst().contains("secret"));
    }

    @Test
    void domainErrorsHaveSafeMessages() {
        invoke(console, "coins", "add", "Target", "1000000001");
        economy.result.completeExceptionally(new EconomyException(EconomyException.Code.BALANCE_LIMIT_EXCEEDED, "internal"));
        drain();
        assertTrue(messages.getFirst().contains("balance cap"));
        invoke(console, "xp", "add", "Target", "10");
        progression.result.completeExceptionally(new ProgressionException(ProgressionException.Code.XP_OVERFLOW, "internal"));
        drain();
        assertTrue(messages.getLast().contains("lifetime XP"));
    }

    @Test
    void quitAndRejoinSuppressRepliesButDoNotCancelMutation() {
        sessions.begin(adminId);
        invoke(admin, "xp", "add", "Target", "10");
        sessions.close(adminId);
        sessions.begin(adminId);
        progression.result.complete(new XpAwardResult(progression.request, 0, 10, false));
        drain();
        assertTrue(messages.isEmpty());
        assertFalse(progression.result.isCancelled());
    }

    @Test
    void disconnectedPlayerDoesNotReceiveDelayedReply() {
        invoke(admin, "coins", "add", "Target", "10");
        connected = false;
        economy.result.complete(new CurrencyOperationResult(economy.operation, CurrencyOperationResult.Status.SUCCESS, 0, 10, false));
        drain();
        assertTrue(messages.isEmpty());
    }

    @Test
    void balanceReadsFreshAsyncValueAndRejectsConsoleAndArguments() {
        balance.onCommand(console, null, "balance", new String[0]);
        balance.onCommand(admin, null, "balance", new String[]{"Target"});
        assertEquals(0, economy.reads);
        assertTrue(messages.get(0).contains("player"));
        assertTrue(messages.get(1).contains("Usage"));
        messages.clear();
        balance.onCommand(admin, null, "balance", new String[0]);
        assertEquals(1, economy.reads);
        assertTrue(messages.isEmpty());
        economy.balance.complete(new BalanceSnapshot(adminId, 321));
        assertTrue(messages.isEmpty());
        drain();
        assertEquals(List.of("Club Coins: 321"), messages);
    }

    @Test
    void balanceOutageIsFriendlyAndDoesNotInventZero() {
        balance.onCommand(admin, null, "balance", new String[0]);
        economy.balance.completeExceptionally(new ProfileStorageException("sensitive SQL", null));
        drain();
        assertEquals(List.of("Balances are temporarily unavailable."), messages);
    }

    @Test
    void tabCompletionUsesOnlyPermittedCommandsAndOnlineNames() {
        assertEquals(List.of("xp", "coins"), commands.onTabComplete(console, null, "", new String[]{""}));
        assertEquals(List.of("remove"), commands.onTabComplete(console, null, "", new String[]{"coins", "r"}));
        assertEquals(List.of("Target"), commands.onTabComplete(console, null, "", new String[]{"xp", "add", "Ta"}));
        permissions.remove("club.admin.coins");
        assertTrue(commands.onTabComplete(console, null, "", new String[]{"coins", ""}).isEmpty());
    }

    @Test
    void timedOutAdminResponseDoesNotCancelAcceptedMutation() throws Exception {
        CountDownLatch queued = new CountDownLatch(1);
        var timedFeedback = new CommandFeedback(server, sessions, task -> {
            mainThread.add(task);
            queued.countDown();
        });
        var timed = new ClubAdminCommand(server, economy, progression, timedFeedback, new AdminCommandConfig(0, 0),
                Duration.ofMillis(1), LoggerFactory.getLogger(getClass()));
        timed.onCommand(console, null, "", new String[]{"xp", "add", "Target", "10"});
        assertTrue(queued.await(5, TimeUnit.SECONDS));
        drain();
        assertTrue(messages.getFirst().contains("may have committed"));
        assertFalse(progression.result.isDone());
        progression.result.complete(new XpAwardResult(progression.request, 0, 10, false));
        drain();
        assertEquals(1, messages.size());
    }

    @Test
    void joinCompletionAfterMutationRefreshRequeriesInsteadOfPublishingStaleSnapshot() {
        UUID id = adminId;
        var initial = new CompletableFuture<PlayerProfile>();
        List<CompletableFuture<Optional<PlayerProfile>>> reads = new ArrayList<>();
        ProfileService profiles = new ProfileService() {
            @Override public CompletableFuture<PlayerProfile> getOrCreate(UUID uuid, String name) { return initial; }
            @Override public CompletableFuture<Optional<PlayerProfile>> findById(UUID uuid) {
                var result = new CompletableFuture<Optional<PlayerProfile>>();
                reads.add(result);
                return result;
            }
            @Override public CompletableFuture<Void> touchLastSeen(UUID uuid) { return CompletableFuture.completedFuture(null); }
            @Override public int shutdown(Duration timeout) { throw new AssertionError(); }
        };
        var logger = LoggerFactory.getLogger(getClass());
        var refreshes = new ProfileRefreshService(profiles, sessions, Duration.ofSeconds(30), logger);
        var listener = new PlayerSessionListener(server, logger, profiles, sessions,
                new ProfileConfig(Duration.ofSeconds(30), false), mainThread::add, refreshes);
        listener.startSession(admin);
        refreshes.refresh(id);
        reads.getFirst().complete(Optional.empty());
        assertEquals(SessionState.UNAVAILABLE, sessions.get(id).orElseThrow().state());
        initial.complete(new PlayerProfile(id, "Admin", Instant.EPOCH, Instant.EPOCH, 1, 0, 0));
        drain();
        assertEquals(2, reads.size());
        reads.get(1).complete(Optional.of(new PlayerProfile(id, "Admin", Instant.EPOCH, Instant.EPOCH, 2, 100, 25)));
        assertEquals(25, sessions.get(id).orElseThrow().profile().coins());
        assertEquals(100, sessions.get(id).orElseThrow().profile().experience());
        new ProfileCommand(sessions).onCommand(admin, null, "profile", new String[0]);
        assertTrue(messages.getLast().contains("Coins: 25"));
        assertTrue(messages.getLast().contains("Experience: 100 XP"));
        assertEquals(2, reads.size());
    }

    private ClubAdminCommand commands(AdminCommandConfig limits) {
        return new ClubAdminCommand(server, economy, progression, feedback, limits, Duration.ofSeconds(30),
                LoggerFactory.getLogger(getClass()));
    }

    private void invoke(CommandSender sender, String... args) {
        assertTrue(commands.onCommand(sender, null, "clubadmin", args));
    }

    private void drain() {
        while (!mainThread.isEmpty()) {
            mainThread.remove().run();
        }
    }

    private Player player(UUID uuid, String name) {
        return proxy(Player.class, (method, args) -> switch (method) {
            case "getUniqueId" -> uuid;
            case "getName" -> name;
            default -> senderCall(method, args);
        });
    }

    private Object senderCall(String method, Object[] args) {
        return switch (method) {
            case "hasPermission" -> permissions.contains(args[0]);
            case "sendMessage" -> {
                assertInstanceOf(Component.class, args[0]);
                messages.add(text((Component) args[0]));
                yield null;
            }
            default -> throw new AssertionError(method);
        };
    }

    private static String text(Component component) {
        String value = component instanceof TextComponent text ? text.content() : "";
        return value + component.children().stream().map(EconomyCommandsTest::text).reduce("", String::concat);
    }

    @FunctionalInterface
    private interface Call { Object invoke(String method, Object[] args); }

    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> type.getSimpleName() + " test double";
                    default -> throw new AssertionError(method);
                };
            }
            return call.invoke(method.getName(), args);
        }));
    }

    private static class FakeEconomy implements EconomyService {
        CurrencyOperation operation;
        CompletableFuture<CurrencyOperationResult> result = new CompletableFuture<>();
        CompletableFuture<BalanceSnapshot> balance = new CompletableFuture<>();
        int reads;
        @Override public CompletableFuture<BalanceSnapshot> balance(UUID id) { reads++; return balance; }
        @Override public CompletableFuture<CurrencyOperationResult> credit(UUID op, UUID id, long amount, String source, String reason, UUID actor) {
            operation = new CurrencyOperation(op, id, amount, CurrencyOperationType.CREDIT, source, reason, actor);
            return result;
        }
        @Override public CompletableFuture<CurrencyOperationResult> debit(UUID op, UUID id, long amount, String source, String reason, UUID actor) {
            operation = new CurrencyOperation(op, id, amount, CurrencyOperationType.DEBIT, source, reason, actor);
            return result;
        }
        @Override public int shutdown(Duration timeout) { throw new AssertionError(); }
    }

    private static class FakeProgression implements ProgressionService {
        XpAwardRequest request;
        CompletableFuture<XpAwardResult> result = new CompletableFuture<>();
        @Override public CompletableFuture<ProgressionSnapshot> getProgress(UUID id) { throw new AssertionError(); }
        @Override public CompletableFuture<XpAwardResult> awardXp(UUID op, UUID id, long amount, String source, String reason, UUID actor) {
            request = new XpAwardRequest(op, id, amount, source, reason, actor);
            return result;
        }
        @Override public int shutdown(Duration timeout) { throw new AssertionError(); }
    }
}
