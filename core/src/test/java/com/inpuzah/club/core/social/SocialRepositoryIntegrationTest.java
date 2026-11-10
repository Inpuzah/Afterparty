package com.inpuzah.club.core.social;

import com.inpuzah.club.core.persistence.Migrations;
import com.inpuzah.club.core.repository.ProfileStorageException;
import com.inpuzah.club.core.social.model.SocialPreferences;
import com.inpuzah.club.core.social.model.Relationship;
import com.inpuzah.club.core.social.repository.JdbcSocialRepository;
import com.inpuzah.club.core.social.repository.SocialException;
import com.inpuzah.club.core.social.service.DefaultSocialService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.LoggerFactory;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static com.inpuzah.club.core.social.model.SocialAction.*;
import static com.inpuzah.club.core.social.model.SocialResult.*;
import static org.junit.jupiter.api.Assertions.*;

class SocialRepositoryIntegrationTest {
    private static EmbeddedPostgres postgres;
    private static DataSource source;
    private UUID a;
    private UUID b;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-09T12:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    };
    private JdbcSocialRepository repository;

    @BeforeAll
    static void start() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        source = postgres.getPostgresDatabase();
        assertEquals("5", Migrations.apply(source, SocialRepositoryIntegrationTest.class.getClassLoader()).targetSchemaVersion);
    }

    @AfterAll
    static void stop() throws Exception { postgres.close(); }

    @BeforeEach
    void reset() throws Exception {
        sql(source, "TRUNCATE party_invites, party_members, parties, party_owners, party_instances, party_preferences, "
                + "friendships, friend_requests, player_blocks, social_preferences, "
                + "display_preferences, experience_awards, currency_transactions, player_profiles");
        a = player("Alice");
        b = player("Bob");
        repository = new JdbcSocialRepository(source, SocialConfig.DEFAULT, clock);
    }

    @Test
    void combinedPrivacySnapshotHasIndependentSavePathsAndNoWritesOnDefaultRead() throws Exception {
        assertEquals(new SocialPreferences(a, true, true), repository.preferences(a));
        assertEquals(0, scalar("SELECT count(*) FROM party_preferences"));
        assertEquals(new SocialPreferences(a, true, false), repository.setPartyPreferences(a, false));
        assertEquals(new SocialPreferences(a, false, false), repository.setPreferences(a, false));
        assertEquals(new SocialPreferences(a, false, true), repository.setPartyPreferences(a, true));
        assertEquals(new SocialPreferences(a, false, true),
                new JdbcSocialRepository(source, SocialConfig.DEFAULT, clock).preferences(a));
        assertEquals(0, scalar("SELECT count(*) FROM display_preferences"));
        assertEquals(SocialException.Code.PLAYER_NOT_FOUND,
                assertThrows(SocialException.class, () -> repository.setPartyPreferences(UUID.randomUUID(), false)).code());
        assertThrows(NullPointerException.class, () -> repository.setPartyPreferences(null, false));
    }

    @Test
    void combinedPrivacyWritesSerializeWithoutOverwritingIndependentChoice() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var friends = pool.submit(() -> { start.await(); return repository.setPreferences(a, false); });
            var parties = pool.submit(() -> { start.await(); return repository.setPartyPreferences(a, false); });
            start.countDown();
            friends.get(5, TimeUnit.SECONDS);
            parties.get(5, TimeUnit.SECONDS);
        }
        assertEquals(new SocialPreferences(a, false, false), repository.preferences(a));
    }

    @Test
    void socialPartyPrivacyFailureRollsBackAndNeverChangesFriendChoice() throws Exception {
        repository.setPreferences(a, false);
        repository.setPartyPreferences(a, false);
        sql(source, """
                CREATE FUNCTION reject_social_party_privacy() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected'; END $$;
                CREATE TRIGGER reject_social_party_privacy BEFORE UPDATE ON party_preferences
                FOR EACH ROW EXECUTE FUNCTION reject_social_party_privacy();
                """);
        try {
            assertThrows(ProfileStorageException.class, () -> repository.setPartyPreferences(a, true));
            assertEquals(new SocialPreferences(a, false, false), repository.preferences(a));
        } finally {
            sql(source, "DROP TRIGGER reject_social_party_privacy ON party_preferences; DROP FUNCTION reject_social_party_privacy()");
        }
    }

    @Test
    void relationshipSnapshotsFollowRequestFriendAndOwnBlockTransitions() {
        assertEquals(Relationship.NONE, repository.relationship(a, b));
        assertEquals(REQUEST_SENT, repository.mutate(REQUEST, a, b));
        assertEquals(Relationship.OUTGOING_REQUEST, repository.relationship(a, b));
        assertEquals(Relationship.INCOMING_REQUEST, repository.relationship(b, a));
        assertEquals(ACCEPTED, repository.mutate(ACCEPT, b, a));
        assertEquals(Relationship.FRIENDS, repository.relationship(a, b));
        assertEquals(Relationship.FRIENDS, repository.relationship(b, a));
        assertEquals(BLOCKED, repository.mutate(BLOCK, a, b));
        assertEquals(Relationship.BLOCKED, repository.relationship(a, b));
        assertEquals(Relationship.NONE, repository.relationship(b, a));
        assertEquals(UNBLOCKED, repository.mutate(UNBLOCK, a, b));
        assertEquals(Relationship.NONE, repository.relationship(a, b));
        assertEquals(Relationship.NONE, repository.relationship(b, a));
    }

    @Test
    void relationshipDoesNotDiscloseAnotherPlayersBlockOrPrivacyAndExpiresAtBoundary() {
        repository.setPreferences(b, false);
        assertEquals(Relationship.NONE, repository.relationship(a, b));
        repository.setPreferences(b, true);
        repository.mutate(REQUEST, a, b);
        now.updateAndGet(value -> value.plus(Duration.ofDays(7)));
        assertEquals(Relationship.NONE, repository.relationship(a, b));
        assertEquals(Relationship.NONE, repository.relationship(b, a));
        repository.mutate(BLOCK, b, a);
        assertEquals(Relationship.NONE, repository.relationship(a, b));
        assertEquals(Relationship.BLOCKED, repository.relationship(b, a));
    }

    @Test
    void relationshipRejectsMissingNullAndSelfInsteadOfInventingNone() {
        assertThrows(NullPointerException.class, () -> repository.relationship(null, b));
        assertThrows(NullPointerException.class, () -> repository.relationship(a, null));
        assertThrows(IllegalArgumentException.class, () -> repository.relationship(a, a));
        assertEquals(SocialException.Code.PLAYER_NOT_FOUND,
                assertThrows(SocialException.class, () -> repository.relationship(a, UUID.randomUUID())).code());
    }

    @Test
    void requestAcceptAndRemovalAreIdempotentAndUseCurrentStoredNames() throws Exception {
        assertEquals(SELF_TARGET, repository.mutate(REQUEST, a, a));
        assertEquals(PLAYER_NOT_FOUND, repository.mutate(REQUEST, a, UUID.randomUUID()));
        assertEquals(REQUEST_SENT, repository.mutate(REQUEST, a, b));
        assertEquals(ALREADY_REQUESTED, repository.mutate(REQUEST, a, b));
        assertEquals(INCOMING_REQUEST_EXISTS, repository.mutate(REQUEST, b, a));
        assertEquals(now.get().plus(Duration.ofDays(7)), repository.listIncomingRequests(b, 0, 10).getFirst().expiresAt());
        assertEquals(REQUEST_MISSING, repository.mutate(ACCEPT, a, b));
        assertEquals(ACCEPTED, repository.mutate(ACCEPT, b, a));
        assertEquals(ALREADY_FRIENDS, repository.mutate(ACCEPT, b, a));
        assertEquals(ALREADY_FRIENDS, repository.mutate(REQUEST, a, b));
        sql(source, "UPDATE player_profiles SET username='Renamed' WHERE uuid='" + a + "'");
        assertEquals("Renamed", repository.listFriends(b, 0, 10).getFirst().username());
        assertEquals(b, repository.listFriends(a, 0, 10).getFirst().playerId());
        assertTrue(repository.listIncomingRequests(b, 0, 10).isEmpty());
        assertEquals(REMOVED, repository.mutate(REMOVE, a, b));
        assertEquals(NOT_FRIENDS, repository.mutate(REMOVE, b, a));
    }

    @Test
    void resolvedRowsEnforceCooldownAcrossRepositoryRecreation() {
        assertEquals(REQUEST_SENT, repository.mutate(REQUEST, a, b));
        assertEquals(DENIED, repository.mutate(DENY, b, a));
        repository = new JdbcSocialRepository(source, SocialConfig.DEFAULT, clock);
        assertEquals(RATE_LIMITED, repository.mutate(REQUEST, a, b));
        now.updateAndGet(value -> value.plusSeconds(30));
        assertEquals(REQUEST_SENT, repository.mutate(REQUEST, a, b));
        assertEquals(CANCELLED, repository.mutate(CANCEL, a, b));
        assertEquals(REQUEST_MISSING, repository.mutate(ACCEPT, b, a));
        assertEquals(REQUEST_MISSING, repository.mutate(CANCEL, a, b));
        assertEquals(RATE_LIMITED, repository.mutate(REQUEST, a, b));
        now.updateAndGet(value -> value.plusSeconds(30));
        assertEquals(REQUEST_SENT, repository.mutate(REQUEST, a, b));
    }

    @Test
    void expiredRequestsNeverAcceptOrOccupyInbox() {
        var limited = new JdbcSocialRepository(source, new SocialConfig(5, 1, 30), clock);
        assertEquals(REQUEST_SENT, limited.mutate(REQUEST, a, b));
        now.updateAndGet(value -> value.plus(Duration.ofDays(7)));
        assertTrue(limited.listIncomingRequests(b, 0, 10).isEmpty());
        assertEquals(REQUEST_MISSING, limited.mutate(ACCEPT, b, a));
        assertEquals(REQUEST_MISSING, limited.mutate(DENY, b, a));
        assertEquals(REQUEST_SENT, limited.mutate(REQUEST, a, b));
    }

    @Test
    void databaseRateLimitCountsDistinctTargetsEvenAfterAcceptedOrCancelled() throws Exception {
        UUID cancelled = null;
        for (int i = 0; i < 5; i++) {
            UUID target = player("Target" + i);
            if (i == 1) { cancelled = target; }
            assertEquals(REQUEST_SENT, repository.mutate(REQUEST, a, target));
            assertEquals(i % 2 == 0 ? ACCEPTED : CANCELLED,
                    repository.mutate(i % 2 == 0 ? ACCEPT : CANCEL, i % 2 == 0 ? target : a, i % 2 == 0 ? a : target));
        }
        UUID sixth = player("Sixth");
        assertEquals(RATE_LIMITED, repository.mutate(REQUEST, a, sixth));
        now.updateAndGet(value -> value.plusSeconds(30));
        assertEquals(REQUEST_SENT, repository.mutate(REQUEST, a, cancelled));
        assertEquals(RATE_LIMITED, repository.mutate(REQUEST, a, sixth));
        now.updateAndGet(value -> value.plusSeconds(30));
        assertEquals(REQUEST_SENT, repository.mutate(REQUEST, a, sixth));
    }

    @Test
    void privacyIsCheckedAgainAtAcceptanceAndUnknownProfilesDoNotGetDefaults() {
        assertEquals(new SocialPreferences(b, true), repository.preferences(b));
        repository.mutate(REQUEST, a, b);
        repository.setPreferences(b, false);
        assertEquals(CANNOT_REQUEST, repository.mutate(ACCEPT, b, a));
        assertEquals(CANNOT_REQUEST, repository.mutate(REQUEST, a, b));
        repository.setPreferences(b, true);
        assertEquals(ACCEPTED, repository.mutate(ACCEPT, b, a));
        assertEquals(SocialException.Code.PLAYER_NOT_FOUND,
                assertThrows(SocialException.class, () -> repository.preferences(UUID.randomUUID())).code());
    }

    @Test
    void blockIsDirectionalButPreventsBothFriendshipDirectionsAndResolvesPending() {
        repository.mutate(REQUEST, a, b);
        assertEquals(BLOCKED, repository.mutate(BLOCK, b, a));
        assertEquals(ALREADY_BLOCKED, repository.mutate(BLOCK, b, a));
        assertEquals(a, repository.listBlocked(b, 0, 10).getFirst().playerId());
        assertTrue(repository.listBlocked(a, 0, 10).isEmpty());
        assertEquals(CANNOT_REQUEST, repository.mutate(REQUEST, a, b));
        assertEquals(CANNOT_REQUEST, repository.mutate(REQUEST, b, a));
        assertEquals(CANNOT_REQUEST, repository.mutate(ACCEPT, b, a));
        assertTrue(repository.listIncomingRequests(b, 0, 10).isEmpty());
        assertEquals(UNBLOCKED, repository.mutate(UNBLOCK, b, a));
        assertEquals(NOT_BLOCKED, repository.mutate(UNBLOCK, b, a));
        now.updateAndGet(value -> value.plusSeconds(30));
        repository.mutate(REQUEST, a, b);
        repository.mutate(ACCEPT, b, a);
        assertEquals(BLOCKED, repository.mutate(BLOCK, a, b));
        assertTrue(repository.listFriends(a, 0, 10).isEmpty());
    }

    @Test
    void concurrentReciprocalRequestsAndAcceptsProduceOneCanonicalFriendship() throws Exception {
        var requests = race(() -> repository.mutate(REQUEST, a, b), () -> repository.mutate(REQUEST, b, a));
        assertEquals(1, requests.stream().filter(value -> value == REQUEST_SENT).count());
        assertEquals(1, requests.stream().filter(value -> value == INCOMING_REQUEST_EXISTS).count());
        boolean toB = !repository.listIncomingRequests(b, 0, 10).isEmpty();
        UUID recipient = toB ? b : a;
        UUID sender = toB ? a : b;
        var accepts = race(() -> repository.mutate(ACCEPT, recipient, sender),
                () -> repository.mutate(ACCEPT, recipient, sender));
        assertTrue(accepts.contains(ACCEPTED));
        assertTrue(accepts.contains(ALREADY_FRIENDS));
        assertEquals(1, scalar("SELECT count(*) FROM friendships"));
        assertEquals(1, scalar("SELECT count(*) FROM friendships WHERE player_low < player_high"));
    }

    @Test
    void simultaneousAcceptBlockCannotLeaveFriendshipAlongsideBlock() throws Exception {
        for (int i = 0; i < 10; i++) {
            repository.mutate(UNBLOCK, a, b);
            now.updateAndGet(value -> value.plusSeconds(61));
            assertEquals(REQUEST_SENT, repository.mutate(REQUEST, a, b));
            race(() -> repository.mutate(ACCEPT, b, a), () -> repository.mutate(BLOCK, a, b));
            assertTrue(repository.listFriends(a, 0, 10).isEmpty());
            assertTrue(repository.listIncomingRequests(b, 0, 10).isEmpty());
            assertEquals(1, repository.listBlocked(a, 0, 10).size());
        }
    }

    @Test
    void concurrentSendersCannotOverflowRecipientQuota() throws Exception {
        UUID c = player("Carol");
        var limited = new JdbcSocialRepository(source, new SocialConfig(5, 1, 30), clock);
        var results = race(() -> limited.mutate(REQUEST, a, b), () -> limited.mutate(REQUEST, c, b));
        assertTrue(results.contains(REQUEST_SENT));
        assertTrue(results.contains(INBOX_FULL));
        assertEquals(1, repository.listIncomingRequests(b, 0, 10).size());
    }

    @Test
    void failedBlockRollsBackFriendRemovalAndBlockInsert() throws Exception {
        repository.mutate(REQUEST, a, b);
        repository.mutate(ACCEPT, b, a);
        // Make the final block step touch a request after inserting the block and deleting the friendship.
        sql(source, "UPDATE friend_requests SET resolved_at = NULL");
        sql(source, """
                CREATE FUNCTION reject_block() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected block failure'; END $$;
                CREATE TRIGGER reject_block BEFORE UPDATE ON friend_requests FOR EACH ROW EXECUTE FUNCTION reject_block();
                """);
        try {
            assertThrows(ProfileStorageException.class, () -> repository.mutate(BLOCK, a, b));
            assertEquals(1, repository.listFriends(a, 0, 10).size());
            assertTrue(repository.listBlocked(a, 0, 10).isEmpty());
        } finally {
            sql(source, "DROP TRIGGER reject_block ON friend_requests; DROP FUNCTION reject_block()");
        }
    }

    @Test
    void populatedV3UpgradePreservesDisplayChoicesAndProfileData() throws Exception {
        var isolated = new PGSimpleDataSource();
        isolated.setServerNames(new String[]{"127.0.0.1"});
        isolated.setPortNumbers(new int[]{postgres.getPort()});
        isolated.setDatabaseName("postgres");
        isolated.setUser("postgres");
        String schema = "social_" + UUID.randomUUID().toString().replace("-", "");
        sql(source, "CREATE SCHEMA " + schema);
        isolated.setCurrentSchema(schema);
        Flyway.configure().dataSource(isolated).locations(Migrations.LOCATION).target("3").load().migrate();
        sql(isolated, "INSERT INTO player_profiles(uuid,username,coins,experience) VALUES ('" + a + "','Alice',321,123)");
        sql(isolated, "INSERT INTO display_preferences(player_uuid,show_bossbar,show_scoreboard) VALUES ('" + a + "',false,true)");
        var result = Migrations.apply(isolated, getClass().getClassLoader());
        assertEquals(2, result.migrationsExecuted);
        assertEquals("5", result.targetSchemaVersion);
        assertEquals(0, Migrations.apply(isolated, getClass().getClassLoader()).migrationsExecuted);
        try (var connection = isolated.getConnection(); var query = connection.createStatement();
             var rows = query.executeQuery("SELECT coins,experience,show_bossbar,show_scoreboard FROM player_profiles "
                     + "JOIN display_preferences ON uuid=player_uuid")) {
            assertTrue(rows.next());
            assertEquals(321, rows.getLong(1));
            assertEquals(123, rows.getLong(2));
            assertFalse(rows.getBoolean(3));
            assertTrue(rows.getBoolean(4));
        }
    }

    @Test
    void schemaRejectsNonCanonicalSelfMissingParentAndInvalidExpiry() throws Exception {
        String low = "00000000-0000-0000-0000-000000000001";
        String high = "80000000-0000-0000-0000-000000000000";
        sql(source, "INSERT INTO player_profiles(uuid,username) VALUES ('" + low + "','Low'),('" + high + "','High')");
        assertThrows(SQLException.class, () -> sql(source,
                "INSERT INTO friendships(player_low,player_high) VALUES ('" + high + "','" + low + "')"));
        assertEquals(REQUEST_SENT, repository.mutate(REQUEST, UUID.fromString(high), UUID.fromString(low)));
        assertEquals(ACCEPTED, repository.mutate(ACCEPT, UUID.fromString(low), UUID.fromString(high)));
        assertEquals(1, scalar("SELECT count(*) FROM friendships WHERE player_low='" + low + "' AND player_high='" + high + "'"));
        assertThrows(SQLException.class, () -> sql(source,
                "INSERT INTO player_blocks(blocker_uuid,blocked_uuid) VALUES ('" + a + "','" + a + "')"));
        assertThrows(SQLException.class, () -> sql(source,
                "INSERT INTO social_preferences(player_uuid) VALUES ('" + UUID.randomUUID() + "')"));
        assertThrows(SQLException.class, () -> sql(source,
                "INSERT INTO friend_requests(sender_uuid,recipient_uuid,expires_at) VALUES ('" + a + "','" + b + "',NOW())"));
        assertThrows(IllegalArgumentException.class, () -> repository.listFriends(a, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> repository.listFriends(a, 0, 101));
    }

    @Test
    void listPaginationIsBoundedStableAndImmutable() throws Exception {
        for (int i = 0; i < 3; i++) {
            UUID target = player("Friend" + i);
            repository.mutate(REQUEST, a, target);
            repository.mutate(ACCEPT, target, a);
        }
        var all = repository.listFriends(a, 0, 10);
        assertEquals(3, all.size());
        assertEquals(all.subList(1, 3), repository.listFriends(a, 1, 2));
        assertThrows(UnsupportedOperationException.class, () -> all.clear());
    }

    @Test
    void friendshipsRequestsBlocksAndPrivacySurviveDatabaseRestart(@TempDir Path directory) throws Exception {
        UUID c = UUID.randomUUID();
        try (var first = EmbeddedPostgres.builder().setDataDirectory(directory).setCleanDataDirectory(false).start()) {
            var data = first.getPostgresDatabase();
            Migrations.apply(data, getClass().getClassLoader());
            sql(data, "INSERT INTO player_profiles(uuid,username) VALUES ('" + a + "','Alice'),('" + b + "','Bob'),('" + c + "','Carol')");
            var repo = new JdbcSocialRepository(data, SocialConfig.DEFAULT, clock);
            repo.mutate(REQUEST, a, b);
            repo.mutate(ACCEPT, b, a);
            repo.mutate(REQUEST, c, b);
            repo.mutate(BLOCK, a, c);
            repo.setPreferences(a, false);
        }
        try (var second = EmbeddedPostgres.builder().setDataDirectory(directory).setCleanDataDirectory(false).start()) {
            var repo = new JdbcSocialRepository(second.getPostgresDatabase(), SocialConfig.DEFAULT, clock);
            assertEquals(b, repo.listFriends(a, 0, 10).getFirst().playerId());
            assertEquals(c, repo.listIncomingRequests(b, 0, 10).getFirst().senderId());
            assertEquals(c, repo.listBlocked(a, 0, 10).getFirst().playerId());
            assertFalse(repo.preferences(a).friendRequestsEnabled());
        }
    }

    @Test
    void realAsyncServiceTimeoutDoesNotCancelRequestAndRetryCannotDuplicateIt() throws Exception {
        var service = DefaultSocialService.create(repository, 1, 10, LoggerFactory.getLogger(getClass()));
        try {
            try (var connection = source.getConnection(); var query = connection.createStatement()) {
                connection.setAutoCommit(false);
                query.executeQuery("SELECT uuid FROM player_profiles WHERE uuid='" + a + "' FOR UPDATE").close();
                var caller = service.mutate(REQUEST, a, b).orTimeout(1, TimeUnit.MILLISECONDS);
                assertInstanceOf(TimeoutException.class,
                        assertThrows(ExecutionException.class, () -> caller.get(5, TimeUnit.SECONDS)).getCause());
                assertEquals(0, scalar("SELECT count(*) FROM friend_requests"));
                connection.commit();
            }
            // The one-worker service's following read is a barrier after accepted persistence finishes.
            assertEquals(1, service.listIncomingRequests(b, 0, 10).get(5, TimeUnit.SECONDS).size());
            assertEquals(ALREADY_REQUESTED, service.mutate(REQUEST, a, b).get(5, TimeUnit.SECONDS));
            assertEquals(ACCEPTED, service.mutate(ACCEPT, b, a).get(5, TimeUnit.SECONDS));
            assertEquals(b, service.listFriends(a, 0, 10).get(5, TimeUnit.SECONDS).getFirst().playerId());
            assertEquals(ALREADY_FRIENDS, service.mutate(ACCEPT, b, a).get(5, TimeUnit.SECONDS));
            assertEquals(1, scalar("SELECT count(*) FROM friendships"));
        } finally {
            service.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void poolExhaustionIsExplicitAndRecoveryDoesNotProduceFalseDefaults() throws Exception {
        var config = new HikariConfig();
        config.setDataSource(source);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(250);
        try (var pool = new HikariDataSource(config)) {
            var service = DefaultSocialService.create(new JdbcSocialRepository(pool, SocialConfig.DEFAULT, clock),
                    1, 10, LoggerFactory.getLogger(getClass()));
            try {
                try (var occupied = pool.getConnection()) {
                    assertFalse(occupied.isClosed());
                    assertInstanceOf(ProfileStorageException.class, assertThrows(ExecutionException.class,
                            () -> service.preferences(a).get(5, TimeUnit.SECONDS)).getCause());
                }
                assertEquals(new SocialPreferences(a, true), service.preferences(a).get(5, TimeUnit.SECONDS));
            } finally {
                service.shutdown(Duration.ofSeconds(5));
            }
        }
    }

    private UUID player(String name) throws Exception {
        UUID uuid = UUID.randomUUID();
        sql(source, "INSERT INTO player_profiles(uuid,username) VALUES ('" + uuid + "','" + name + "')");
        return uuid;
    }

    private long scalar(String sql) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static void sql(DataSource data, String sql) throws Exception {
        try (var connection = data.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private <T> List<T> race(java.util.concurrent.Callable<T> first, java.util.concurrent.Callable<T> second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            var results = new ArrayList<java.util.concurrent.Future<T>>();
            for (var call : List.of(first, second)) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!go.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Race gate timed out"); }
                    return call.call();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            return List.of(results.get(0).get(5, TimeUnit.SECONDS), results.get(1).get(5, TimeUnit.SECONDS));
        }
    }
}
