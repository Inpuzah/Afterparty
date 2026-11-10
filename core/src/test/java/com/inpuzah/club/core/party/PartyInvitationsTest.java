package com.inpuzah.club.core.party;

import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.party.model.PartyOutcome;
import com.inpuzah.club.core.party.service.PartyService;
import com.inpuzah.club.core.session.ProfileSessionManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

class PartyInvitationsTest {
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    private final ProfileSessionManager profiles = new ProfileSessionManager();
    private final LinkedBlockingQueue<Runnable> main = new LinkedBlockingQueue<>();
    private final Map<UUID, Player> online = new HashMap<>();
    private final List<Component> notices = new ArrayList<>();
    private boolean enabled = true, permission = true, admitted = true;
    private int calls;
    private final Player alice = player(a, "Alice"), bob = player(b, "Bob");
    private final CompletableFuture<PartyOutcome> write = new CompletableFuture<>();
    private final Server server = proxy(Server.class, (method, args) -> online.get((UUID) args[0]));
    private final PartyService service = proxy(PartyService.class, (method, args) -> {
        assertEquals("invite", method);
        assertEquals(a, args[0]); assertEquals(b, args[1]);
        calls++;
        return write;
    });
    private final PartyInvitations invitations = new PartyInvitations(server, profiles, service, main::add,
            () -> enabled, id -> admitted);

    @BeforeEach void join() { ready(a, "Alice"); ready(b, "Bob"); }

    @Test void newCommittedInviteNotifiesOnceWithImmutableAcceptDenyIdentity() {
        invitations.invite(alice, bob);
        assertTrue(notices.isEmpty());
        var outcome = invited();
        write.complete(outcome);
        drain();
        assertEquals(1, notices.size());
        assertTrue(containsClick(notices.getFirst(), ClickEvent.runCommand("/party accept " + outcome.inviteId().orElseThrow())));
        assertTrue(containsClick(notices.getFirst(), ClickEvent.runCommand("/party deny " + outcome.inviteId().orElseThrow())));
    }

    @Test void cancelledConsumerCannotCancelCommitAndPendingBlocksAllOtherSurfaces() {
        var result = invitations.invite(alice, bob);
        result.cancel(false);
        assertEquals(PartyOutcome.Code.RATE_LIMITED, invitations.invite(alice, bob).join().code());
        assertEquals(1, calls);
        assertFalse(write.isDone());
        write.complete(invited());
        drain();
        assertEquals(1, notices.size());
    }

    @Test void recipientRejoinSuppressesOriginalSessionNoticeWithoutPretendingMutationCancelled() {
        var result = invitations.invite(alice, bob);
        ready(b, "Bob");
        write.complete(invited());
        drain();
        assertEquals(PartyOutcome.Code.INVITED, result.join().code());
        assertTrue(notices.isEmpty());
    }

    @Test void rejectedInvitesNeverSendSuccessNotices() {
        invitations.invite(alice, bob);
        write.complete(PartyOutcome.of(PartyOutcome.Code.BLOCKED_OR_RESTRICTED));
        drain();
        assertTrue(notices.isEmpty());
        assertEquals(PartyOutcome.Code.BLOCKED_OR_RESTRICTED, invitations.invite(alice, bob).join().code());
        drain();
        assertTrue(notices.isEmpty());
    }

    @Test void failedMutationRetainsFailureAndNeverNotifiesSuccess() {
        var result = invitations.invite(alice, bob);
        write.completeExceptionally(new IllegalStateException("unknown commit"));
        drain();
        assertTrue(result.isCompletedExceptionally());
        assertTrue(notices.isEmpty());
    }

    @Test void disabledPermissionReadinessAdmissionOrOfflineTargetNeverSubmits() {
        enabled = false; invitations.invite(alice, bob);
        enabled = true; permission = false; invitations.invite(alice, bob);
        permission = true; admitted = false; invitations.invite(alice, bob);
        admitted = true; profiles.beginRefresh(b); invitations.invite(alice, bob);
        ready(b, "Bob"); online.remove(b); invitations.invite(alice, bob);
        assertEquals(0, calls);
    }

    @Test void runtimeLossSuppressesCommittedNotice() {
        invitations.invite(alice, bob);
        write.complete(invited());
        enabled = false;
        drain();
        assertTrue(notices.isEmpty());
    }

    private PartyOutcome invited() { return new PartyOutcome(PartyOutcome.Code.INVITED,
            Optional.of(UUID.randomUUID()), Optional.of(UUID.randomUUID())); }
    private void ready(UUID id, String name) {
        profiles.markReady(id, profiles.begin(id).token(), PlayerProfile.newProfile(id, name, Instant.EPOCH));
    }
    private Player player(UUID id, String name) {
        var player = proxy(Player.class, (method, args) -> switch (method) {
            case "getUniqueId" -> id;
            case "getName" -> name;
            case "hasPermission" -> permission;
            case "sendMessage" -> { notices.add((Component) args[0]); yield null; }
            default -> throw new AssertionError(method);
        });
        online.put(id, player);
        return player;
    }
    private void drain() { Runnable task; while ((task = main.poll()) != null) { task.run(); } }
    private boolean containsClick(Component text, ClickEvent<?> click) {
        return click.equals(text.clickEvent()) || text.children().stream().anyMatch(child -> containsClick(child, click));
    }
    private interface Invocation { Object call(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (value, method, args) -> invocation.call(method.getName(), args)));
    }
}
