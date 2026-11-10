package com.inpuzah.club.core.party;

import com.inpuzah.club.core.party.model.PartyOutcome;
import com.inpuzah.club.core.party.service.PartyService;
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

class PartyDisconnectsTest {
    private final UUID player = UUID.randomUUID();
    private Instant now = Instant.parse("2030-01-01T00:00:00Z");
    private final List<Throwable> errors = new ArrayList<>();
    private final List<PartyDeparture> departures = new ArrayList<>();
    private final List<CompletableFuture<PartyOutcome>> results = new ArrayList<>();
    private final List<Set<?>> presence = new ArrayList<>();
    private final PartyService service = (PartyService) Proxy.newProxyInstance(PartyService.class.getClassLoader(),
            new Class<?>[]{PartyService.class}, (proxy, method, args) -> {
                assertEquals("leaveDisconnected", method.getName());
                presence.add((Set<?>) args[1]);
                departures.add((PartyDeparture) args[2]);
                var result = new CompletableFuture<PartyOutcome>();
                results.add(result);
                return result;
            });
    private final PartyDisconnects manager = new PartyDisconnects(service, Duration.ofMinutes(5), new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now; }
    }, LoggerFactory.getLogger(getClass()), errors::add);

    @Test
    void expiryOccursAtExactConfiguredDeadlineNotEarlier() {
        manager.joined(player, 1);
        assertTrue(manager.admitted(player));
        manager.quit(player, 1);
        assertFalse(manager.admitted(player));
        now = now.plusSeconds(299);
        manager.sweep(Set.of());
        assertTrue(results.isEmpty());
        now = now.plusSeconds(1);
        manager.sweep(Set.of());
        assertEquals(1, results.size());
        assertTrue(departures.getFirst().claim());
        results.getFirst().complete(PartyOutcome.of(PartyOutcome.Code.LEFT));
        assertFalse(manager.admitted(player));
        manager.sweep(Set.of());
        assertEquals(1, results.size());
    }

    @Test
    void rejoinBeforeExpiryCancelsOldDepartureAndDuplicateReadySignalsDoNotResetIdentity() {
        manager.joined(player, 1);
        manager.quit(player, 1);
        now = now.plusSeconds(299);
        manager.joined(player, 2);
        manager.joined(player, 2);
        manager.quit(player, 1);
        now = now.plusSeconds(100);
        manager.sweep(Set.of(player));
        assertTrue(manager.admitted(player));
        assertTrue(results.isEmpty());
    }

    @Test
    void queuedCleanupCannotClaimAfterReconnectAndCannotRemoveNewPresenceRecord() {
        expired();
        manager.joined(player, 2);
        assertTrue(manager.admitted(player));
        assertFalse(departures.getFirst().claim());
        results.getFirst().complete(PartyOutcome.of(PartyOutcome.Code.STALE_PARTY));
        assertTrue(manager.admitted(player));
        manager.quit(player, 2);
        now = now.plusSeconds(300);
        manager.sweep(Set.of());
        assertEquals(2, results.size());
    }

    @Test
    void cleanupAlreadyClaimedFencesNewLoginUntilItsActualResultArrives() {
        expired();
        assertTrue(departures.getFirst().claim());
        manager.joined(player, 2);
        assertFalse(manager.admitted(player));
        manager.joined(player, 2);
        assertFalse(manager.admitted(player));
        results.getFirst().complete(PartyOutcome.of(PartyOutcome.Code.LEFT));
        assertTrue(manager.admitted(player));
    }

    @Test
    void rapidRejoinQuitRejoinKeepsOlderClaimedBarrierUntilCompletion() {
        expired();
        assertTrue(departures.getFirst().claim());
        manager.joined(player, 2);
        manager.quit(player, 2);
        manager.joined(player, 3);
        assertFalse(manager.admitted(player));
        results.getFirst().complete(PartyOutcome.of(PartyOutcome.Code.LEFT));
        assertTrue(manager.admitted(player));
        manager.quit(player, 2);
        assertTrue(manager.admitted(player));
    }

    @Test
    void newDepartureCannotOvertakeOlderClaimedCleanupAcrossReconnect() {
        expired();
        assertTrue(departures.getFirst().claim());
        manager.joined(player, 2);
        manager.quit(player, 2);
        now = now.plusSeconds(301);
        manager.sweep(Set.of());
        assertEquals(1, results.size());
        results.getFirst().complete(PartyOutcome.of(PartyOutcome.Code.LEFT));
        manager.sweep(Set.of());
        assertEquals(2, results.size());
    }

    @Test
    void boundedSweepsRetainOneInFlightPerPlayerAndSnapshotPresence() {
        for (int i = 0; i < 40; i++) {
            UUID id = UUID.randomUUID();
            manager.joined(id, i);
            manager.quit(id, i);
        }
        now = now.plusSeconds(300);
        var online = new java.util.HashSet<>(Set.of(player));
        manager.sweep(online);
        online.clear();
        assertEquals(16, results.size());
        assertEquals(Set.of(player), presence.getFirst());
        manager.sweep(Set.of());
        assertEquals(16, results.size());
        List.copyOf(results).forEach(result -> result.complete(PartyOutcome.of(PartyOutcome.Code.NOT_IN_PARTY)));
        manager.sweep(Set.of());
        assertEquals(32, results.size());
    }

    @Test
    void failureIsReportedAndNeverBlindlyRetriedAgainstAnotherMembership() {
        expired();
        var cause = new IllegalStateException("uncertain commit");
        results.getFirst().completeExceptionally(cause);
        assertEquals(List.of(cause), errors);
        now = now.plusSeconds(1000);
        manager.sweep(Set.of());
        assertEquals(1, results.size());
    }

    @Test
    void closeCancelsQueuedClaimsAndInvalidatesOnlineAdmission() {
        expired();
        UUID online = UUID.randomUUID();
        manager.joined(online, 1);
        manager.close();
        assertFalse(manager.admitted(online));
        assertFalse(departures.getFirst().claim());
        results.getFirst().complete(PartyOutcome.of(PartyOutcome.Code.STALE_PARTY));
        manager.joined(player, 2);
        manager.quit(player, 2);
        manager.sweep(Set.of());
        assertFalse(manager.admitted(player));
        assertEquals(1, results.size());
    }

    @Test
    void unexpectedOutcomeFailsExplicitlyAndFutureCopiesCannotUnlockClaimedDeparture() {
        expired();
        var departure = departures.getFirst();
        assertTrue(departure.claim());
        departure.finished().cancel(false);
        manager.joined(player, 2);
        assertFalse(manager.admitted(player));
        results.getFirst().complete(PartyOutcome.of(PartyOutcome.Code.CREATED));
        assertEquals(1, errors.size());
        assertFalse(manager.admitted(player));
    }

    private void expired() {
        manager.joined(player, 1);
        manager.quit(player, 1);
        now = now.plusSeconds(300);
        manager.sweep(Set.of());
    }
}
