package com.inpuzah.club.core.party;

import com.inpuzah.club.core.party.model.PartyOutcome;
import com.inpuzah.club.core.party.repository.JdbcPartyPreferencesRepository;
import com.inpuzah.club.core.party.repository.JdbcPartyRepository;
import com.inpuzah.club.core.party.repository.PartyEpochLease;
import com.inpuzah.club.core.party.repository.PartyException;
import com.inpuzah.club.core.party.service.DefaultPartyService;
import com.inpuzah.club.core.persistence.Migrations;
import com.inpuzah.club.core.repository.ProfileStorageException;
import com.inpuzah.club.core.social.SocialConfig;
import com.inpuzah.club.core.social.model.SocialAction;
import com.inpuzah.club.core.social.repository.JdbcSocialRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static com.inpuzah.club.core.party.model.PartyOutcome.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class PartyRepositoryIntegrationTest {
    private static EmbeddedPostgres postgres;
    private static PGSimpleDataSource source;
    private PartyEpochLease lease;
    private JdbcPartyRepository repository;
    private UUID a;
    private UUID b;
    private UUID c;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2030-01-01T12:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    };

    @BeforeAll
    static void start() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        source = new PGSimpleDataSource();
        source.setServerNames(new String[]{"127.0.0.1"});
        source.setPortNumbers(new int[]{postgres.getPort()});
        source.setUser("postgres");
        source.setDatabaseName("postgres");
        Migrations.apply(source, PartyRepositoryIntegrationTest.class.getClassLoader());
    }

    @AfterAll static void stop() throws Exception { postgres.close(); }
    @AfterEach void close() { lease.close(); }

    @BeforeEach
    void reset() throws Exception {
        sql("TRUNCATE party_invites,party_members,parties,party_owners,party_instances,party_preferences,"
                + "friendships,friend_requests,player_blocks,social_preferences,display_preferences,"
                + "experience_awards,currency_transactions,player_profiles");
        a = player("Alice");
        b = player("Bob");
        c = player("Carol");
        lease = PartyEpochLease.acquire(source, "lobby");
        repository = repo(PartyPolicy.DEFAULT);
    }

    @Test
    void authorizedDeliveryKeepsMembershipLockedUntilFinishedAndRejectsClosedParty() throws Exception {
        repository.accept(b, repository.invite(a, b).inviteId().orElseThrow());
        var delivering = new CountDownLatch(1);
        var releaseDelivery = new CountDownLatch(1);
        var removing = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var chat = threads.submit(() -> repository.withParty(a, party -> {
                assertTrue(party.isMember(b));
                delivering.countDown();
                try { assertTrue(releaseDelivery.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new AssertionError(error); }
            }));
            assertTrue(delivering.await(5, TimeUnit.SECONDS));
            var kick = threads.submit(() -> { removing.countDown(); return repository.kick(a, b); });
            try {
                assertTrue(removing.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> kick.get(100, TimeUnit.MILLISECONDS));
            } finally { releaseDelivery.countDown(); }
            chat.get(5, TimeUnit.SECONDS);
            assertEquals(KICKED, kick.get(5, TimeUnit.SECONDS).code());
            repository.withParty(a, party -> assertFalse(party.isMember(b)));
            repository.disband(a);
            assertEquals(PartyException.Code.NOT_IN_PARTY,
                    assertThrows(PartyException.class, () -> repository.withParty(a, party -> fail("Closed party delivered"))).code());
        } finally { releaseDelivery.countDown(); }
    }

    @Test
    void committedServiceChangesCoverAllMembershipPathsAndIgnoreRejections() throws Exception {
        var changes = new java.util.concurrent.CopyOnWriteArrayList<com.inpuzah.club.core.party.model.PartyChange>();
        var service = DefaultPartyService.create(repository, 1, 16, LoggerFactory.getLogger(getClass()));
        service.observe(changes::add);
        try {
            var invite = service.invite(a, b).get(5, TimeUnit.SECONDS);
            service.accept(b, invite.inviteId().orElseThrow()).get(5, TimeUnit.SECONDS);
            service.promote(a, b).get(5, TimeUnit.SECONDS);
            service.promote(a, b).get(5, TimeUnit.SECONDS);
            assertEquals(3, changes.size());
            service.leaveIfCurrent(b, Set.of(a), repository.getParty(b).orElseThrow().id(),
                    repository.getParty(b).orElseThrow().revision()).get(5, TimeUnit.SECONDS);
            var left = changes.getLast();
            assertTrue(left.before().orElseThrow().isMember(b));
            assertFalse(left.after().orElseThrow().isMember(b));
            assertTrue(left.after().orElseThrow().isLeader(a));
            now.updateAndGet(value -> value.plusSeconds(31));
            service.accept(b, service.invite(a, b).get(5, TimeUnit.SECONDS).inviteId().orElseThrow()).get(5, TimeUnit.SECONDS);
            service.kick(a, b).get(5, TimeUnit.SECONDS);
            service.accept(c, service.invite(a, c).get(5, TimeUnit.SECONDS).inviteId().orElseThrow()).get(5, TimeUnit.SECONDS);
            service.leaveDisconnected(a, Set.of(c), new PartyDeparture()).get(5, TimeUnit.SECONDS);
            var current = repository.getParty(c).orElseThrow();
            service.disbandIfCurrent(c, current.id(), current.revision()).get(5, TimeUnit.SECONDS);
            service.create(a).get(5, TimeUnit.SECONDS);
            service.disband(a).get(5, TimeUnit.SECONDS);
            assertTrue(changes.stream().map(change -> change.outcome().code()).toList()
                    .containsAll(List.of(CREATED, INVITED, ACCEPTED, PROMOTED, LEFT, KICKED, DISBANDED)));
            assertTrue(changes.getLast().after().isEmpty());
            assertTrue(changes.getLast().before().isPresent());
        } finally { service.shutdown(Duration.ofSeconds(5)); }
    }

    @Test
    void reconnectDuringClaimedDatabaseRemovalWaitsWithoutBlockingMainOrRemovingNewParty() throws Exception {
        repository.create(a);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        javax.sql.DataSource wrapped = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                javax.sql.DataSource.class.getClassLoader(), new Class<?>[]{javax.sql.DataSource.class},
                (proxy, method, args) -> {
                    try {
                        Object value = method.invoke(source, args);
                        if (!method.getName().equals("getConnection")) { return value; }
                        var connection = (java.sql.Connection) value;
                        return java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),
                                new Class<?>[]{java.sql.Connection.class}, (p, m, arguments) -> {
                                    if (m.getName().equals("prepareStatement") && arguments[0] instanceof String sql
                                            && sql.startsWith("UPDATE party_members SET left_at")) {
                                        entered.countDown();
                                        assertTrue(release.await(5, TimeUnit.SECONDS));
                                    }
                                    try { return m.invoke(connection, arguments); }
                                    catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                                });
                    } catch (java.lang.reflect.InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
        var service = DefaultPartyService.create(new JdbcPartyRepository(wrapped, lease, PartyPolicy.DEFAULT, clock),
                1, 10, LoggerFactory.getLogger(getClass()));
        var failure = new AtomicReference<Throwable>();
        var disconnects = new PartyDisconnects(service, Duration.ofSeconds(1), clock,
                LoggerFactory.getLogger(getClass()), failure::set);
        try {
            disconnects.joined(a, 1);
            disconnects.quit(a, 1);
            now.set(now.get().plusSeconds(1));
            disconnects.sweep(Set.of());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            disconnects.joined(a, 2);
            assertFalse(disconnects.admitted(a), "Claimed cleanup must fence party actions, not block the Paper thread");
            release.countDown();
            assertTrue(service.getParty(a).get(5, TimeUnit.SECONDS).isEmpty());
            assertTrue(disconnects.admitted(a));
            assertNull(failure.get());
            assertEquals(CREATED, service.create(a).get(5, TimeUnit.SECONDS).code());
            now.set(now.get().plusSeconds(600));
            disconnects.sweep(Set.of(a));
            assertTrue(service.getParty(a).get(5, TimeUnit.SECONDS).isPresent());
        } finally {
            release.countDown();
            disconnects.close();
            service.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void disconnectRejoinCancelsCleanupWaitingForTransactionLock() throws Exception {
        repository.create(a);
        var before = repository.getParty(a).orElseThrow();
        var departure = new PartyDeparture();
        try (var connection = source.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var query = connection.prepareStatement("SELECT instance_id FROM party_instances WHERE instance_id=? FOR UPDATE")) {
                query.setObject(1, lease.instanceId());
                query.executeQuery().close();
            }
            var entered = new CountDownLatch(1);
            var mutation = pool.submit(() -> {
                entered.countDown();
                return repository.leaveDisconnected(a, Set.of(), departure);
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                departure.cancel();
                connection.commit();
                assertEquals(STALE_PARTY, mutation.get(5, TimeUnit.SECONDS).code());
            } finally {
                connection.rollback();
            }
        }
        assertEquals(before, repository.getParty(a).orElseThrow());
    }

    @Test
    void expiredDisconnectTransfersLeaderAndClosesLastMemberUsingExistingRules() {
        var invitation = repository.invite(a, b);
        repository.accept(b, invitation.inviteId().orElseThrow());
        assertEquals(LEFT, repository.leaveDisconnected(a, Set.of(b), new PartyDeparture()).code());
        assertEquals(b, repository.getParty(b).orElseThrow().leaderId());
        assertEquals(LEFT, repository.leaveDisconnected(b, Set.of(), new PartyDeparture()).code());
        assertTrue(repository.getParty(b).isEmpty());
        assertEquals(NOT_IN_PARTY, repository.leaveDisconnected(UUID.randomUUID(), Set.of(), new PartyDeparture()).code());
    }

    @Test
    void menuMutationsRequireMatchingPartyIdentityAndRevisionInsideTransaction() {
        repository.create(a);
        var before = repository.getParty(a).orElseThrow();
        assertEquals(STALE_PARTY, repository.leaveIfCurrent(a, Set.of(), UUID.randomUUID(), before.revision()).code());
        assertEquals(STALE_PARTY, repository.disbandIfCurrent(a, before.id(), before.revision() + 1).code());
        assertEquals(before, repository.getParty(a).orElseThrow());
        var invitation = repository.invite(a, b);
        assertEquals(INVITED, invitation.code());
        assertEquals(STALE_PARTY, repository.leaveIfCurrent(a, Set.of(), before.id(), before.revision()).code());
        assertEquals(STALE_PARTY, repository.disbandIfCurrent(a, before.id(), before.revision()).code());
        var current = repository.getParty(a).orElseThrow();
        assertEquals(DISBANDED, repository.disbandIfCurrent(a, current.id(), current.revision()).code());
        assertTrue(repository.getParty(a).isEmpty());
    }

    @Test
    void oldMenuCannotLeaveOrDisbandReplacementPartyEvenAtSameRevision() {
        repository.create(a);
        var before = repository.getParty(a).orElseThrow();
        repository.disband(a);
        repository.create(a);
        var replacement = repository.getParty(a).orElseThrow();
        assertEquals(before.revision(), replacement.revision());
        assertNotEquals(before.id(), replacement.id());
        assertEquals(STALE_PARTY, repository.leaveIfCurrent(a, Set.of(), before.id(), before.revision()).code());
        assertEquals(STALE_PARTY, repository.disbandIfCurrent(a, before.id(), before.revision()).code());
        assertEquals(replacement, repository.getParty(a).orElseThrow());
        assertEquals(LEFT, repository.leaveIfCurrent(a, Set.of(), replacement.id(), replacement.revision()).code());
        assertTrue(repository.getParty(a).isEmpty());
    }

    @Test
    void versionGuardRejectsChangeCommittedWhileMenuWriteWaitsOnEpochLock() throws Exception {
        repository.create(a);
        var before = repository.getParty(a).orElseThrow();
        try (var connection = source.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var query = connection.prepareStatement("SELECT instance_id FROM party_instances WHERE instance_id=? FOR UPDATE")) {
                query.setObject(1, lease.instanceId());
                query.executeQuery().close();
            }
            var entered = new CountDownLatch(1);
            var mutation = pool.submit(() -> {
                entered.countDown();
                return repository.disbandIfCurrent(a, before.id(), before.revision());
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                try (var update = connection.prepareStatement("UPDATE parties SET revision=revision+1 WHERE id=?")) {
                    update.setObject(1, before.id());
                    update.executeUpdate();
                }
                connection.commit();
                assertEquals(STALE_PARTY, mutation.get(5, TimeUnit.SECONDS).code());
            } finally {
                connection.rollback();
            }
        }
        assertEquals(before.revision() + 1, repository.getParty(a).orElseThrow().revision());
    }

    @Test
    void versionGuardInputErrorsNeverMutateParty() {
        repository.create(a);
        var before = repository.getParty(a).orElseThrow();
        assertThrows(NullPointerException.class, () -> repository.leaveIfCurrent(a, Set.of(), null, 0));
        assertThrows(NullPointerException.class, () -> repository.disbandIfCurrent(a, null, 0));
        assertThrows(IllegalArgumentException.class, () -> repository.leaveIfCurrent(a, Set.of(), before.id(), -1));
        assertThrows(IllegalArgumentException.class, () -> repository.disbandIfCurrent(a, before.id(), -1));
        assertEquals(before, repository.getParty(a).orElseThrow());
    }

    @Test
    void createsImmutableUuidSnapshotAndDuplicateCreateIsExplicit() throws Exception {
        var result = repository.create(a);
        assertEquals(CREATED, result.code());
        var party = repository.getParty(a).orElseThrow();
        assertEquals(result.partyId().orElseThrow(), party.id());
        assertEquals(a, party.leaderId());
        assertEquals(lease.instanceId(), party.instanceId());
        assertEquals(0, party.revision());
        assertEquals(1, party.members().size());
        assertEquals(ALREADY_IN_PARTY, repository.create(a).code());
        assertThrows(UnsupportedOperationException.class, () -> party.members().clear());
        sql("UPDATE player_profiles SET username='Renamed' WHERE uuid='" + a + "'");
        assertEquals("Renamed", repository.getParty(a).orElseThrow().members().getFirst().username());
        assertTrue(repository.getParty(b).isEmpty());
    }

    @Test
    void autoCreationInviteAcceptListAndDuplicateActionsAreSafe() {
        var sent = repository.invite(a, b);
        assertEquals(INVITED, sent.code());
        UUID invitation = sent.inviteId().orElseThrow();
        assertEquals(1, repository.getParty(a).orElseThrow().members().size());
        assertEquals(sent.inviteId(), repository.invite(a, b).inviteId());
        assertEquals(ALREADY_INVITED, repository.invite(a, b).code());
        assertEquals(INVITE_NOT_FOUND, repository.accept(c, invitation).code());
        assertEquals(invitation, repository.getInvites(b, 0, 10).getFirst().id());
        assertThrows(UnsupportedOperationException.class, () -> repository.getInvites(b, 0, 10).clear());
        assertEquals(ACCEPTED, repository.accept(b, invitation).code());
        assertEquals(INVITE_NOT_FOUND, repository.accept(b, invitation).code());
        assertEquals(repository.getParty(a), repository.getParty(b));
        assertEquals(2, repository.getParty(b).orElseThrow().members().size());
        assertTrue(repository.getInvites(b, 0, 10).isEmpty());
        assertEquals(ALREADY_IN_PARTY, repository.invite(c, b).code());
        assertTrue(repository.getParty(c).isEmpty());
    }

    @Test
    void privacyAndEitherBlockDirectionRejectAutoCreationAndAreRecheckedOnAccept() {
        var privacy = new JdbcPartyPreferencesRepository(source);
        privacy.save(b, false);
        assertEquals(BLOCKED_OR_RESTRICTED, repository.invite(a, b).code());
        assertTrue(repository.getParty(a).isEmpty());
        privacy.save(b, true);
        var social = new JdbcSocialRepository(source, SocialConfig.DEFAULT);
        for (boolean reversed : List.of(false, true)) {
            UUID blocker = reversed ? b : a;
            UUID blocked = reversed ? a : b;
            social.mutate(SocialAction.BLOCK, blocker, blocked);
            assertEquals(BLOCKED_OR_RESTRICTED, repository.invite(a, b).code());
            assertTrue(repository.getParty(a).isEmpty());
            social.mutate(SocialAction.UNBLOCK, blocker, blocked);
        }
        UUID invitation = repository.invite(a, b).inviteId().orElseThrow();
        privacy.save(b, false);
        assertEquals(BLOCKED_OR_RESTRICTED, repository.accept(b, invitation).code());
        privacy.save(b, true);
        social.mutate(SocialAction.BLOCK, b, a);
        assertEquals(BLOCKED_OR_RESTRICTED, repository.accept(b, invitation).code());
        assertTrue(repository.getParty(b).isEmpty());
        social.mutate(SocialAction.UNBLOCK, b, a);
        assertEquals(ACCEPTED, repository.accept(b, invitation).code());
        // Existing membership is not partitioned by a subsequent block.
        social.mutate(SocialAction.BLOCK, b, a);
        assertEquals(2, repository.getParty(a).orElseThrow().members().size());
    }

    @Test
    void expiryAtExactBoundaryAndDenyCooldownArePersistent() {
        UUID first = repository.invite(a, b).inviteId().orElseThrow();
        now.updateAndGet(value -> value.plusSeconds(120));
        assertTrue(repository.getInvites(b, 0, 10).isEmpty());
        assertEquals(INVITE_EXPIRED, repository.accept(b, first).code());
        assertEquals(INVITE_EXPIRED, repository.deny(b, first).code());
        assertEquals(RATE_LIMITED, repository.invite(a, b).code());
        now.updateAndGet(value -> value.plusSeconds(30));
        UUID second = repository.invite(a, b).inviteId().orElseThrow();
        assertNotEquals(first, second);
        now.updateAndGet(value -> value.plusSeconds(40));
        assertEquals(DENIED, repository.deny(b, second).code());
        assertEquals(INVITE_NOT_FOUND, repository.deny(b, second).code());
        repository = repo(PartyPolicy.DEFAULT);
        assertEquals(RATE_LIMITED, repository.invite(a, b).code());
        now.updateAndGet(value -> value.plusSeconds(30));
        assertEquals(INVITED, repository.invite(a, b).code());
    }

    @Test
    void expiredUnresolvedInviteCanBeReplacedAndDoesNotUseInboxQuota() {
        repository = repo(new PartyPolicy(8, Duration.ofSeconds(1), 5, 1, Duration.ofSeconds(1)));
        var first = repository.invite(a, b);
        assertEquals(RATE_LIMITED, repository.invite(a, c).code());
        now.updateAndGet(value -> value.plusSeconds(1));
        assertEquals(INVITED, repository.invite(a, c).code());
        assertEquals(INVITE_EXPIRED, repository.accept(b, first.inviteId().orElseThrow()).code());
        now.updateAndGet(value -> value.plusSeconds(1));
        assertEquals(INVITED, repository.invite(a, b).code());
    }

    @Test
    void distinctTargetLimitSurvivesResolutionAndRepositoryRecreation() throws Exception {
        repository = repo(new PartyPolicy(8, Duration.ofSeconds(120), 2, 10, Duration.ofSeconds(30)));
        UUID one = repository.invite(a, b).inviteId().orElseThrow();
        UUID two = repository.invite(a, c).inviteId().orElseThrow();
        repository.deny(b, one);
        repository.deny(c, two);
        repository = repo(new PartyPolicy(8, Duration.ofSeconds(120), 2, 10, Duration.ofSeconds(30)));
        UUID third = player("Third");
        assertEquals(RATE_LIMITED, repository.invite(a, third).code());
        now.updateAndGet(value -> value.plusSeconds(30));
        assertEquals(INVITED, repository.invite(a, b).code());
        assertEquals(RATE_LIMITED, repository.invite(a, third).code());
        now.updateAndGet(value -> value.plusSeconds(30));
        assertEquals(INVITED, repository.invite(a, third).code());
    }

    @Test
    void invitationPaginationIsBoundedAndDeterministic() {
        repository.invite(a, c);
        repository.invite(b, c);
        var all = repository.getInvites(c, 0, 100);
        assertEquals(2, all.size());
        assertEquals(all.subList(1, 2), repository.getInvites(c, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> repository.getInvites(c, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> repository.getInvites(c, 0, 101));
    }

    @Test
    void simultaneousAcceptsRespectCapacityAndInvitesDoNotReserveSlots() throws Exception {
        repository = repo(new PartyPolicy(2, Duration.ofSeconds(120), 5, 10, Duration.ofSeconds(30)));
        UUID first = repository.invite(a, b).inviteId().orElseThrow();
        UUID second = repository.invite(a, c).inviteId().orElseThrow();
        var results = race(() -> repository.accept(b, first), () -> repository.accept(c, second));
        assertEquals(Set.of(ACCEPTED, PARTY_FULL), Set.of(results.getFirst().code(), results.getLast().code()));
        assertEquals(2, repository.getParty(a).orElseThrow().members().size());
        UUID loser = repository.getParty(b).isEmpty() ? b : c;
        UUID winner = loser.equals(b) ? c : b;
        UUID loserInvite = loser.equals(b) ? first : second;
        assertEquals(KICKED, repository.kick(a, winner).code());
        assertEquals(ACCEPTED, repository.accept(loser, loserInvite).code());
    }

    @Test
    void competingPartiesCannotAcceptSamePlayerAndOtherInviteRevisionChanges() throws Exception {
        var one = repository.invite(a, c);
        var two = repository.invite(b, c);
        var results = race(() -> repository.accept(c, one.inviteId().orElseThrow()),
                () -> repository.accept(c, two.inviteId().orElseThrow()));
        assertEquals(1, results.stream().filter(result -> result.code() == ACCEPTED).count());
        assertEquals(1, results.stream().filter(result -> result.code() == INVITE_NOT_FOUND).count());
        assertEquals(2, repository.getParty(c).orElseThrow().members().size());
        assertEquals(2, repository.getParty(a).orElseThrow().revision());
        assertEquals(2, repository.getParty(b).orElseThrow().revision());
        assertTrue(repository.getInvites(c, 0, 10).isEmpty());
    }

    @Test
    void concurrentDuplicateInviteCreatesOnePartyAndOneInvitation() throws Exception {
        var results = race(() -> repository.invite(a, b), () -> repository.invite(a, b));
        assertEquals(Set.of(INVITED, ALREADY_INVITED), Set.of(results.getFirst().code(), results.getLast().code()));
        assertEquals(results.getFirst().inviteId(), results.getLast().inviteId());
        assertEquals(1, scalar("SELECT count(*) FROM parties"));
        assertEquals(1, scalar("SELECT count(*) FROM party_invites"));
    }

    @Test
    void blockRaceSerializesWithInvitationWithoutDeadlockAndAcceptanceRechecks() throws Exception {
        var social = new JdbcSocialRepository(source, SocialConfig.DEFAULT);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var invitation = pool.submit(() -> { start.await(); return repository.invite(a, b); });
            var block = pool.submit(() -> { start.await(); return social.mutate(SocialAction.BLOCK, b, a); });
            start.countDown();
            var result = invitation.get(10, TimeUnit.SECONDS);
            block.get(10, TimeUnit.SECONDS);
            if (result.code() == INVITED) {
                assertEquals(BLOCKED_OR_RESTRICTED, repository.accept(b, result.inviteId().orElseThrow()).code());
            } else {
                assertEquals(BLOCKED_OR_RESTRICTED, result.code());
                assertTrue(repository.getParty(a).isEmpty());
            }
            assertTrue(repository.getParty(b).isEmpty());
        }
    }

    @Test
    void leaderOnlyActionsTransferCancelOldInvitesAndKickPreservesHistory() throws Exception {
        join(a, b);
        UUID pending = repository.invite(a, c).inviteId().orElseThrow();
        assertEquals(NOT_LEADER, repository.invite(b, c).code());
        assertEquals(NOT_LEADER, repository.promote(b, a).code());
        assertEquals(NOT_LEADER, repository.kick(b, a).code());
        assertEquals(NOT_LEADER, repository.disband(b).code());
        assertEquals(INVALID_TARGET, repository.kick(a, a).code());
        assertEquals(INVALID_TARGET, repository.promote(a, c).code());
        assertEquals(PROMOTED, repository.promote(a, b).code());
        assertEquals(b, repository.getParty(a).orElseThrow().leaderId());
        assertEquals(INVITE_NOT_FOUND, repository.accept(c, pending).code());
        assertEquals(KICKED, repository.kick(b, a).code());
        assertTrue(repository.getParty(a).isEmpty());
        now.updateAndGet(value -> value.plusSeconds(30));
        join(b, a);
        assertEquals(2, scalar("SELECT count(*) FROM party_members WHERE player_uuid='" + a + "'"));
    }

    @Test
    void leaderLeavePrefersOnlineThenEarliestRemainingAndLastLeaveCloses() {
        join(a, b);
        now.updateAndGet(value -> value.plusSeconds(1));
        join(a, c);
        assertEquals(LEFT, repository.leave(a, Set.of(c)).code());
        assertEquals(c, repository.getParty(b).orElseThrow().leaderId());
        assertEquals(LEFT, repository.leave(c, Set.of()).code());
        assertEquals(b, repository.getParty(b).orElseThrow().leaderId());
        assertEquals(LEFT, repository.leave(b, Set.of()).code());
        assertTrue(repository.getParty(b).isEmpty());
        assertEquals(NOT_IN_PARTY, repository.leave(b, Set.of()).code());
    }

    @Test
    void disbandResolvesPendingAndNoOldPartyCanAcceptOrReturnMembers() {
        join(a, b);
        UUID invitation = repository.invite(a, c).inviteId().orElseThrow();
        assertEquals(DISBANDED, repository.disband(a).code());
        assertTrue(repository.getParty(a).isEmpty());
        assertTrue(repository.getParty(b).isEmpty());
        assertEquals(INVITE_NOT_FOUND, repository.accept(c, invitation).code());
        assertEquals(NOT_IN_PARTY, repository.disband(a).code());
        assertEquals(CREATED, repository.create(b).code());
    }

    @Test
    void acceptRacingDisbandNeverLeavesGhostMembership() throws Exception {
        UUID invitation = repository.invite(a, b).inviteId().orElseThrow();
        var results = race(() -> repository.accept(b, invitation), () -> repository.disband(a));
        assertEquals(DISBANDED, results.getLast().code());
        assertTrue(results.getFirst().code() == ACCEPTED || results.getFirst().code() == INVITE_NOT_FOUND);
        assertTrue(repository.getParty(a).isEmpty());
        assertTrue(repository.getParty(b).isEmpty());
        assertEquals(0, scalar("SELECT count(*) FROM party_members WHERE left_at IS NULL"));
        assertEquals(0, scalar("SELECT count(*) FROM party_invites WHERE resolved_at IS NULL"));
    }

    @Test
    void acceptanceRacingPrivacySaveUsesLockedPolicyWithoutTouchingFriends() throws Exception {
        var privacy = new JdbcPartyPreferencesRepository(source);
        UUID invitation = repository.invite(a, b).inviteId().orElseThrow();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var accept = pool.submit(() -> { start.await(); return repository.accept(b, invitation); });
            var save = pool.submit(() -> { start.await(); return privacy.save(b, false); });
            start.countDown();
            var result = accept.get(10, TimeUnit.SECONDS);
            assertFalse(save.get(10, TimeUnit.SECONDS).invitesEnabled());
            if (result.code() == ACCEPTED) {
                assertEquals(2, repository.getParty(a).orElseThrow().members().size());
            } else {
                assertEquals(BLOCKED_OR_RESTRICTED, result.code());
                assertTrue(repository.getParty(b).isEmpty());
            }
            assertEquals(0, scalar("SELECT count(*) FROM social_preferences"));
            assertEquals(0, scalar("SELECT count(*) FROM friendships"));
        }
    }

    @Test
    void failedLeadershipHandoffRollsBackMemberDepartureAndRevision() throws Exception {
        join(a, b);
        var before = repository.getParty(a).orElseThrow();
        sql("""
                CREATE FUNCTION reject_leader_transfer() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected'; END $$;
                CREATE TRIGGER reject_leader_transfer BEFORE UPDATE ON parties
                FOR EACH ROW EXECUTE FUNCTION reject_leader_transfer();
                """);
        try {
            assertThrows(ProfileStorageException.class, () -> repository.leave(a, Set.of(b)));
            assertEquals(before, repository.getParty(a).orElseThrow());
            assertEquals(before, repository.getParty(b).orElseThrow());
        } finally {
            sql("DROP TRIGGER reject_leader_transfer ON parties; DROP FUNCTION reject_leader_transfer()");
        }
        assertEquals(LEFT, repository.leave(a, Set.of(b)).code());
        assertEquals(b, repository.getParty(b).orElseThrow().leaderId());
    }

    @Test
    void injectedInviteFailureRollsBackAutoCreationMembershipAndRateEvidence() throws Exception {
        sql("""
                CREATE FUNCTION reject_party_invite() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected'; END $$;
                CREATE TRIGGER reject_party_invite BEFORE INSERT ON party_invites
                FOR EACH ROW EXECUTE FUNCTION reject_party_invite();
                """);
        try {
            assertThrows(ProfileStorageException.class, () -> repository.invite(a, b));
            assertTrue(repository.getParty(a).isEmpty());
            assertEquals(0, scalar("SELECT count(*) FROM parties"));
            assertEquals(0, scalar("SELECT count(*) FROM party_members"));
        } finally {
            sql("DROP TRIGGER reject_party_invite ON party_invites; DROP FUNCTION reject_party_invite()");
        }
        assertEquals(INVITED, repository.invite(a, b).code());
    }

    @Test
    void injectedAcceptFailureRollsBackMembershipAndInviteResolution() throws Exception {
        UUID invitation = repository.invite(a, b).inviteId().orElseThrow();
        sql("""
                CREATE FUNCTION reject_party_resolution() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected'; END $$;
                CREATE TRIGGER reject_party_resolution BEFORE UPDATE ON party_invites
                FOR EACH ROW EXECUTE FUNCTION reject_party_resolution();
                """);
        try {
            assertThrows(ProfileStorageException.class, () -> repository.accept(b, invitation));
            assertTrue(repository.getParty(b).isEmpty());
            assertEquals(invitation, repository.getInvites(b, 0, 10).getFirst().id());
        } finally {
            sql("DROP TRIGGER reject_party_resolution ON party_invites; DROP FUNCTION reject_party_resolution()");
        }
        assertEquals(ACCEPTED, repository.accept(b, invitation).code());
    }

    @Test
    void missingSelfForeignEpochAndClosedLeaseNeverInventSuccess() {
        UUID missing = UUID.randomUUID();
        assertEquals(INVALID_TARGET, repository.invite(a, a).code());
        assertEquals(TARGET_UNAVAILABLE, repository.invite(a, missing).code());
        assertEquals(PartyException.Code.PLAYER_NOT_FOUND,
                assertThrows(PartyException.class, () -> repository.getParty(missing)).code());
        assertThrows(NullPointerException.class, () -> repository.create(null));
        assertEquals(NOT_IN_PARTY, repository.kick(a, b).code());
        assertEquals(INVITE_NOT_FOUND, repository.deny(a, UUID.randomUUID()).code());
        repository.create(a);
        try (var foreign = PartyEpochLease.acquire(source, "another")) {
            var otherRepo = new JdbcPartyRepository(source, foreign, PartyPolicy.DEFAULT, clock);
            assertEquals(PartyException.Code.STALE_EPOCH,
                    assertThrows(PartyException.class, () -> otherRepo.getParty(a)).code());
        }
        lease.close();
        assertThrows(IllegalStateException.class, () -> repository.create(a));
    }

    @Test
    void realAsyncTimeoutDoesNotCancelAcceptedCommitAndFollowingReadFindsIt() throws Exception {
        var service = DefaultPartyService.create(repository, 1, 10, LoggerFactory.getLogger(getClass()));
        try {
            try (var connection = source.getConnection(); var query = connection.createStatement()) {
                connection.setAutoCommit(false);
                query.executeQuery("SELECT uuid FROM player_profiles WHERE uuid='" + a + "' FOR UPDATE").close();
                var caller = service.invite(a, b).orTimeout(1, TimeUnit.MILLISECONDS);
                assertInstanceOf(TimeoutException.class,
                        assertThrows(ExecutionException.class, () -> caller.get(5, TimeUnit.SECONDS)).getCause());
                connection.commit();
            }
            var invites = service.getInvites(b, 0, 10).get(5, TimeUnit.SECONDS);
            assertEquals(1, invites.size());
            assertEquals(ACCEPTED, service.accept(b, invites.getFirst().id()).get(5, TimeUnit.SECONDS).code());
            assertEquals(2, service.getParty(a).get(5, TimeUnit.SECONDS).orElseThrow().members().size());
        } finally {
            assertEquals(0, service.shutdown(Duration.ofSeconds(5)));
        }
    }

    private JdbcPartyRepository repo(PartyPolicy policy) { return new JdbcPartyRepository(source, lease, policy, clock); }
    private void join(UUID owner, UUID member) {
        var invited = repository.invite(owner, member);
        assertEquals(INVITED, invited.code());
        assertEquals(ACCEPTED, repository.accept(member, invited.inviteId().orElseThrow()).code());
    }
    private List<PartyOutcome> race(java.util.concurrent.Callable<PartyOutcome> first,
                                    java.util.concurrent.Callable<PartyOutcome> second) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { start.await(); return first.call(); });
            var two = pool.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            return List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
        }
    }
    private UUID player(String name) throws SQLException {
        UUID player = UUID.randomUUID();
        sql("INSERT INTO player_profiles(uuid,username) VALUES ('" + player + "','" + name + "')");
        return player;
    }
    private static void sql(String sql) throws SQLException {
        try (var connection = source.getConnection(); var query = connection.createStatement()) {
            query.setQueryTimeout(10);
            query.execute(sql);
        }
    }
    private static long scalar(String sql) throws SQLException {
        try (var connection = source.getConnection(); var query = connection.createStatement(); var rows = query.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
