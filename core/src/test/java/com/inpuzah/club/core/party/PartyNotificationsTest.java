package com.inpuzah.club.core.party;

import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.party.model.Party;
import com.inpuzah.club.core.party.model.PartyChange;
import com.inpuzah.club.core.party.model.PartyMember;
import com.inpuzah.club.core.party.model.PartyOutcome;
import com.inpuzah.club.core.session.ProfileSessionManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

class PartyNotificationsTest {
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), stranger = UUID.randomUUID();
    private final ProfileSessionManager profiles = new ProfileSessionManager();
    private final LinkedBlockingQueue<Runnable> main = new LinkedBlockingQueue<>();
    private final Map<UUID, List<String>> messages = new HashMap<>();
    private final Map<UUID, Player> players = new HashMap<>();
    private boolean enabled = true, permitted = true;
    private final Server server = proxy(Server.class, (method, args) -> {
        assertEquals("getPlayer", method);
        return players.get(args[0]);
    });
    private final PartyNotifications notifications = new PartyNotifications(server, profiles, main::add, () -> enabled);

    @Test void joinedMemberAndExistingMembersReceiveCommittedJoinAndNeverStrangers() {
        ready(a, "Alice"); ready(b, "Bob"); ready(stranger, "Other");
        var before = party(a, false);
        notifications.accept(change(PartyOutcome.Code.ACCEPTED, before, party(a, true)));
        assertTrue(messages.get(a).isEmpty());
        main.remove().run();
        assertEquals(List.of("[Party] Bob joined the party."), messages.get(a));
        assertEquals(messages.get(a), messages.get(b));
        assertTrue(messages.get(stranger).isEmpty());
        assertEquals(List.of("Bob joined the party."),
                PartyNotifications.lines(change(PartyOutcome.Code.ACCEPTED, null, party(a, true))));
    }

    @Test void kickLeaveSuccessionAndDisbandHaveDistinctLiteralNotices() {
        var both = party(a, true);
        assertTrue(PartyNotifications.lines(change(PartyOutcome.Code.KICKED, both, party(a, false)))
                .contains("Bob was removed from the party."));
        assertTrue(PartyNotifications.lines(change(PartyOutcome.Code.LEFT, both, party(a, false)))
                .contains("Bob left the party."));
        assertEquals(List.of("Bob is now party leader."),
                PartyNotifications.lines(change(PartyOutcome.Code.PROMOTED, both, party(b, true))));
        assertTrue(PartyNotifications.lines(change(PartyOutcome.Code.DISBANDED, both, null)).contains("The party closed."));
    }

    @Test void rejoinOfflineLoadingPermissionAndRuntimeLossSuppressStaleCallbacks() {
        ready(a, "Alice"); ready(b, "Bob");
        var event = change(PartyOutcome.Code.ACCEPTED, party(a, false), party(a, true));
        notifications.accept(event);
        ready(b, "Bob");
        profiles.beginRefresh(a);
        main.remove().run();
        assertTrue(messages.get(a).isEmpty());
        assertTrue(messages.get(b).isEmpty());
        notifications.accept(event);
        enabled = false;
        main.remove().run();
        assertTrue(messages.get(b).isEmpty());
        enabled = true;
        notifications.accept(event);
        permitted = false;
        main.remove().run();
        assertTrue(messages.get(b).isEmpty());
        permitted = true;
        notifications.accept(event);
        players.remove(b);
        main.remove().run();
        assertTrue(messages.get(b).isEmpty());
    }

    private PartyChange change(PartyOutcome.Code code, Party before, Party after) {
        return new PartyChange(code == PartyOutcome.Code.ACCEPTED ? b : a,
                PartyOutcome.of(code), Optional.ofNullable(before), Optional.ofNullable(after));
    }
    private Party party(UUID leader, boolean both) {
        var members = new ArrayList<PartyMember>();
        members.add(new PartyMember(a, "Alice", Instant.EPOCH));
        if (both) { members.add(new PartyMember(b, "Bob", Instant.EPOCH)); }
        return new Party(a, a, leader, Instant.EPOCH, Instant.EPOCH, 0, 8, members);
    }
    private void ready(UUID id, String name) {
        profiles.markReady(id, profiles.begin(id).token(), PlayerProfile.newProfile(id, name, Instant.EPOCH));
        messages.putIfAbsent(id, new ArrayList<>());
        players.put(id, proxy(Player.class, (method, args) -> switch (method) {
            case "hasPermission" -> permitted;
            case "sendMessage" -> {
                messages.get(id).add(PlainTextComponentSerializer.plainText().serialize((Component) args[0]));
                yield null;
            }
            default -> throw new AssertionError(method);
        }));
    }
    private interface Invocation { Object call(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (value, method, args) -> invocation.call(method.getName(), args)));
    }
}
