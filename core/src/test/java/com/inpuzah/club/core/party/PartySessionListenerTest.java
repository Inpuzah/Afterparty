package com.inpuzah.club.core.party;

import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.party.model.PartyOutcome;
import com.inpuzah.club.core.party.service.PartyService;
import com.inpuzah.club.core.session.ProfileSessionManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class PartySessionListenerTest {
    private final UUID uuid = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();
    private final ProfileSessionManager profiles = new ProfileSessionManager();
    private final Player player = player(uuid);
    private final Player other = player(otherId);
    private List<Player> online = List.of(player, other);
    private final List<Set<?>> presence = new ArrayList<>();
    private final List<PartyDeparture> departures = new ArrayList<>();
    private final List<CompletableFuture<PartyOutcome>> results = new ArrayList<>();
    private final List<Throwable> errors = new ArrayList<>();
    private boolean enabled = true, cancelled;
    private Instant now = Instant.parse("2030-01-01T00:00:00Z");
    private int schedules;
    private Runnable tick;
    private final PartyService service = proxy(PartyService.class, (method, args) -> {
        assertEquals("leaveDisconnected", method);
        presence.add((Set<?>) args[1]);
        departures.add((PartyDeparture) args[2]);
        var result = new CompletableFuture<PartyOutcome>();
        results.add(result);
        return result;
    });
    private final PartyDisconnects manager = new PartyDisconnects(service, Duration.ofSeconds(300),
            new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
                @Override public Instant instant() { return now; }
            }, LoggerFactory.getLogger(getClass()), errors::add);
    private final BukkitTask task = proxy(BukkitTask.class, (method, args) -> {
        assertEquals("cancel", method);
        cancelled = true;
        return null;
    });
    private final BukkitScheduler scheduler = proxy(BukkitScheduler.class, (method, args) -> {
        assertEquals("runTaskTimer", method);
        assertEquals(20L, args[2]);
        assertEquals(20L, args[3]);
        schedules++;
        tick = (Runnable) args[1];
        return task;
    });
    private final Server server = proxy(Server.class, (method, args) -> switch (method) {
        case "getOnlinePlayers" -> online;
        case "getScheduler" -> scheduler;
        default -> throw new AssertionError(method);
    });
    private final PartySessionListener listener = new PartySessionListener(server, profiles, manager, () -> enabled);

    @Test
    void startAdoptsOnlinePlayersAndOwnsExactlyOneSharedTaskUntilClose() {
        listener.start(null);
        assertTrue(listener.admitted(uuid));
        assertTrue(listener.admitted(otherId));
        assertEquals(1, schedules);
        assertThrows(IllegalStateException.class, () -> listener.start(null));
        listener.close();
        assertTrue(cancelled);
        assertFalse(listener.admitted(uuid));
        tick.run();
        assertTrue(results.isEmpty());
    }

    @Test
    void oldPlayerQuitCannotScheduleNewLoginRemovalAndJoinCancelsQueuedExpiry() {
        listener.begin(player);
        listener.onQuit(quit(player));
        now = now.plusSeconds(300);
        listener.sweep();
        assertEquals(1, departures.size());
        var replacement = player(uuid);
        listener.onJoin(new PlayerJoinEvent(replacement, Component.empty()));
        listener.onQuit(quit(player));
        assertTrue(listener.admitted(uuid));
        assertFalse(departures.getFirst().claim());
        results.getFirst().complete(PartyOutcome.of(PartyOutcome.Code.STALE_PARTY));
        assertTrue(listener.admitted(uuid));
        listener.close();
    }

    @Test
    void sweepSuppliesOnlyReadyAdmittedOnlineMembersForSuccession() {
        listener.start(null);
        profiles.markReady(otherId, profiles.begin(otherId).token(),
                PlayerProfile.newProfile(otherId, "Other", Instant.EPOCH));
        listener.onQuit(quit(player));
        online = List.of(other);
        now = now.plusSeconds(300);
        tick.run();
        assertEquals(List.of(Set.of(otherId)), presence);
        assertTrue(departures.getFirst().claim());
        results.getFirst().complete(PartyOutcome.of(PartyOutcome.Code.LEFT));
        listener.close();
    }

    @Test
    void disabledRuntimeSuppressesSweepAndAdmissionAndClosedListenerIgnoresEvents() {
        var invalidations = new java.util.concurrent.atomic.AtomicInteger();
        listener.onUnavailable(invalidations::incrementAndGet);
        listener.begin(player);
        enabled = false;
        assertFalse(listener.admitted(uuid));
        listener.onQuit(quit(player));
        listener.sweep();
        assertTrue(results.isEmpty());
        assertEquals(1, invalidations.get());
        listener.sweep();
        assertEquals(1, invalidations.get());
        listener.close();
        enabled = true;
        listener.onJoin(new PlayerJoinEvent(player, Component.empty()));
        listener.onQuit(quit(player));
        assertFalse(listener.admitted(uuid));
        assertTrue(results.isEmpty());
    }

    private static PlayerQuitEvent quit(Player player) {
        return new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED);
    }

    private static Player player(UUID uuid) {
        return proxy(Player.class, (method, args) -> {
            if (method.equals("getUniqueId")) { return uuid; }
            throw new AssertionError(method);
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
