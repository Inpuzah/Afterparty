package com.inpuzah.club.core.party;

import com.inpuzah.club.core.party.repository.PartyEpochLease;
import com.inpuzah.club.core.party.repository.PartyException;
import com.inpuzah.club.core.party.repository.JdbcPartyPreferencesRepository;
import com.inpuzah.club.core.party.model.PartyPreferences;
import com.inpuzah.club.core.persistence.Migrations;
import com.inpuzah.club.core.repository.ProfileStorageException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PartyPersistenceIntegrationTest {
    private static EmbeddedPostgres postgres;
    private PGSimpleDataSource source;
    private final UUID leader = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @BeforeAll
    static void start() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stop() throws Exception {
        postgres.close();
    }

    @BeforeEach
    void migrate() throws Exception {
        source = direct();
        String schema = "party_" + UUID.randomUUID().toString().replace("-", "");
        sql(source, "CREATE SCHEMA " + schema);
        source.setCurrentSchema(schema);
        assertEquals("5", Migrations.apply(source, getClass().getClassLoader()).targetSchemaVersion);
        sql(source, "INSERT INTO player_profiles(uuid,username) VALUES ('" + leader + "','Leader'),('" + target + "','Target')");
    }

    @Test
    void migrationIsRepeatableAndPopulatedV4UpgradePreservesAllPriorSystems() throws Exception {
        assertEquals(0, Migrations.apply(source, getClass().getClassLoader()).migrationsExecuted);
        var upgrade = direct();
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        sql(upgrade, "CREATE SCHEMA " + schema);
        upgrade.setCurrentSchema(schema);
        Flyway.configure().dataSource(upgrade).locations(Migrations.LOCATION).target("4").load().migrate();
        sql(upgrade, "INSERT INTO player_profiles(uuid,username,coins,experience) VALUES ('" + leader + "','Leader',321,123),"
                + "('" + target + "','Target',0,0)");
        sql(upgrade, "INSERT INTO display_preferences(player_uuid,show_bossbar,show_scoreboard) VALUES ('" + leader + "',false,true)");
        sql(upgrade, "INSERT INTO social_preferences(player_uuid,friend_requests_enabled) VALUES ('" + leader + "',false)");
        sql(upgrade, "INSERT INTO player_blocks(blocker_uuid,blocked_uuid) VALUES ('" + leader + "','" + target + "')");
        sql(upgrade, "INSERT INTO friendships(player_low,player_high) VALUES (LEAST('" + leader + "'::uuid,'" + target
                + "'::uuid),GREATEST('" + leader + "'::uuid,'" + target + "'::uuid))");
        var migrated = Migrations.apply(upgrade, getClass().getClassLoader());
        assertEquals(1, migrated.migrationsExecuted);
        assertEquals("5", migrated.targetSchemaVersion);
        assertEquals(321, scalar(upgrade, "SELECT coins FROM player_profiles WHERE uuid='" + leader + "'"));
        assertEquals(123, scalar(upgrade, "SELECT experience FROM player_profiles WHERE uuid='" + leader + "'"));
        assertEquals(1, scalar(upgrade, "SELECT count(*) FROM display_preferences WHERE NOT show_bossbar AND show_scoreboard"));
        assertEquals(1, scalar(upgrade, "SELECT count(*) FROM social_preferences WHERE NOT friend_requests_enabled"));
        assertEquals(1, scalar(upgrade, "SELECT count(*) FROM player_blocks"));
        assertEquals(1, scalar(upgrade, "SELECT count(*) FROM friendships"));
        assertEquals(0, scalar(upgrade, "SELECT count(*) FROM party_preferences"));
        assertEquals(0, Migrations.apply(upgrade, getClass().getClassLoader()).migrationsExecuted);
    }

    @Test
    void freshOwnerHasNoPredecessorAndDedicatedLeaseRejectsCompetingProcess() throws Exception {
        try (var first = PartyEpochLease.acquire(source, "lobby")) {
            first.verify();
            assertEquals("lobby", first.ownerId());
            assertThrows(IllegalStateException.class, () -> PartyEpochLease.acquire(source, "lobby"));
            first.verify();
            assertEquals(1, scalar(source, "SELECT count(*) FROM party_instances"));
            assertEquals(0, scalar(source, "SELECT count(*) FROM parties"));
        }
        assertEquals(1, scalar(source, "SELECT count(*) FROM party_instances WHERE ended_at IS NOT NULL"));
        try (var next = PartyEpochLease.acquire(source, "lobby")) {
            next.verify();
            assertEquals(2, scalar(source, "SELECT count(*) FROM party_instances"));
        }
    }

    @Test
    void gracefulCloseResolvesInvitesClosesMembershipAndIncrementsRevisionOnce() throws Exception {
        var lease = PartyEpochLease.acquire(source, "lobby");
        UUID party = createParty(lease.instanceId(), leader, 8);
        UUID invite = invite(party, leader, target);
        sql(source, "INSERT INTO party_preferences(player_uuid,invites_enabled) VALUES ('" + target + "',false)");
        lease.close();
        lease.close();
        assertThrows(IllegalStateException.class, lease::verify);
        assertEquals(1, scalar(source, "SELECT revision FROM parties WHERE id='" + party + "'"));
        assertEquals(0, scalar(source, "SELECT count(*) FROM party_members WHERE left_at IS NULL"));
        assertEquals(1, scalar(source, "SELECT count(*) FROM party_invites WHERE id='" + invite + "' AND resolution='PARTY_CLOSED'"));
        assertEquals(1, scalar(source, "SELECT count(*) FROM party_preferences WHERE NOT invites_enabled"));
        try (var next = PartyEpochLease.acquire(source, "lobby")) {
            next.verify();
            assertEquals(1, scalar(source, "SELECT revision FROM parties WHERE id='" + party + "'"));
        }
    }

    @Test
    void crashReconciliationClosesOnlyRecordedPredecessorAndPreservesOtherOwners() throws Exception {
        var crashed = PartyEpochLease.acquire(source, "lobby");
        UUID oldParty = createParty(crashed.instanceId(), leader, 8);
        invite(oldParty, leader, target);
        try (var foreign = PartyEpochLease.acquire(source, "separate_owner")) {
            UUID foreignParty = createParty(foreign.instanceId(), target, 8);
            terminateLeaseBackend();
            assertThrows(ProfileStorageException.class, crashed::verify);
            try (var replacement = PartyEpochLease.acquire(source, "lobby")) {
                assertNotEquals(crashed.instanceId(), replacement.instanceId());
                replacement.verify();
                assertEquals(1, scalar(source, "SELECT count(*) FROM parties WHERE id='" + oldParty + "' AND closed_at IS NOT NULL"));
                assertEquals(1, scalar(source, "SELECT count(*) FROM parties WHERE id='" + foreignParty + "' AND closed_at IS NULL"));
                assertEquals(1, scalar(source, "SELECT count(*) FROM party_members WHERE party_id='" + foreignParty + "' AND left_at IS NULL"));
                assertEquals(1, scalar(source, "SELECT count(*) FROM party_invites WHERE resolution='PARTY_CLOSED'"));
                foreign.verify();
            }
        } finally {
            assertThrows(ProfileStorageException.class, crashed::close);
        }
    }

    @Test
    void reconciliationFailureRollsBackAllChangesAndDoesNotPublishNewEpoch() throws Exception {
        UUID previous;
        UUID party;
        var lease = PartyEpochLease.acquire(source, "lobby");
        previous = lease.instanceId();
        party = createParty(previous, leader, 8);
        invite(party, leader, target);
        terminateLeaseBackend();
        assertThrows(ProfileStorageException.class, lease::close);
        sql(source, """
                CREATE FUNCTION reject_party_close() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected close failure'; END $$;
                CREATE TRIGGER reject_party_close BEFORE UPDATE ON parties
                FOR EACH ROW EXECUTE FUNCTION reject_party_close();
                """);
        assertThrows(ProfileStorageException.class, () -> PartyEpochLease.acquire(source, "lobby"));
        assertEquals(1, scalar(source, "SELECT count(*) FROM party_owners WHERE instance_id='" + previous + "'"));
        assertEquals(1, scalar(source, "SELECT count(*) FROM party_instances"));
        assertEquals(1, scalar(source, "SELECT count(*) FROM party_members WHERE left_at IS NULL"));
        assertEquals(1, scalar(source, "SELECT count(*) FROM party_invites WHERE resolved_at IS NULL"));
        sql(source, "DROP TRIGGER reject_party_close ON parties; DROP FUNCTION reject_party_close()");
        try (var recovered = PartyEpochLease.acquire(source, "lobby")) {
            recovered.verify();
            assertEquals(0, scalar(source, "SELECT count(*) FROM party_members WHERE left_at IS NULL"));
        }
    }

    @Test
    void invalidOwnerAndUnreachableDatabaseFailExplicitly() {
        assertThrows(NullPointerException.class, () -> PartyEpochLease.acquire(null, "lobby"));
        assertThrows(NullPointerException.class, () -> PartyEpochLease.acquire(source, null));
        for (String invalid : new String[]{"", "white space", "bad:owner", "a".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> PartyEpochLease.acquire(source, invalid));
        }
        var pooled = direct();
        pooled.setServerNames(new String[]{"example-pooler.region.neon.tech"});
        assertThrows(IllegalArgumentException.class, () -> PartyEpochLease.acquire(pooled, "lobby"));
        var unavailable = direct();
        unavailable.setPortNumbers(new int[]{1});
        unavailable.setConnectTimeout(1);
        assertThrows(ProfileStorageException.class, () -> PartyEpochLease.acquire(unavailable, "lobby"));
    }

    @Test
    void ownershipMismatchFailsInsteadOfClosingForeignEpoch() throws Exception {
        UUID foreignId = UUID.randomUUID();
        sql(source, "INSERT INTO party_instances(instance_id,owner_id) VALUES ('" + foreignId + "','foreign')");
        UUID foreignParty = createParty(foreignId, leader, 8);
        assertThrows(SQLException.class, () -> sql(source,
                "INSERT INTO party_owners(owner_id,instance_id) VALUES ('lobby','" + foreignId + "')"));
        assertEquals(1, scalar(source, "SELECT count(*) FROM parties WHERE id='" + foreignParty + "' AND closed_at IS NULL"));
        assertEquals(1, scalar(source, "SELECT count(*) FROM party_instances"));
    }

    @Test
    void restartClosesOldPartyButKeepsPersonalPrivacy(@TempDir Path directory) throws Exception {
        UUID party;
        PartyEpochLease crashed;
        try (var first = EmbeddedPostgres.builder().setDataDirectory(directory).setCleanDataDirectory(false).start()) {
            var data = direct(first.getPort());
            Migrations.apply(data, getClass().getClassLoader());
            sql(data, "INSERT INTO player_profiles(uuid,username) VALUES ('" + leader + "','Leader')");
            new JdbcPartyPreferencesRepository(data).save(leader, false);
            crashed = PartyEpochLease.acquire(data, "lobby");
            party = createParty(data, crashed.instanceId(), leader, 8);
        }
        assertThrows(ProfileStorageException.class, crashed::close);
        try (var second = EmbeddedPostgres.builder().setDataDirectory(directory).setCleanDataDirectory(false).start()) {
            var data = direct(second.getPort());
            assertEquals(0, Migrations.apply(data, getClass().getClassLoader()).migrationsExecuted);
            try (var recovered = PartyEpochLease.acquire(data, "lobby")) {
                recovered.verify();
                assertEquals(1, scalar(data, "SELECT count(*) FROM parties WHERE id='" + party + "' AND closed_at IS NOT NULL"));
                assertEquals(0, scalar(data, "SELECT count(*) FROM party_members WHERE left_at IS NULL"));
                assertFalse(new JdbcPartyPreferencesRepository(data).load(leader).invitesEnabled());
            }
        }
    }

    @Test
    void schemaOwningNonSuperuserCanMigrateAndValidateDeferredLeaderConstraint() throws Exception {
        String role = "partyrole_" + UUID.randomUUID().toString().replace("-", "");
        String schema = "owned_" + UUID.randomUUID().toString().replace("-", "");
        sql(source, "CREATE ROLE " + role + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE");
        sql(source, "CREATE SCHEMA " + schema + " AUTHORIZATION " + role);
        var limited = direct();
        limited.setUser(role);
        limited.setCurrentSchema(schema);
        assertEquals("5", Migrations.apply(limited, getClass().getClassLoader()).targetSchemaVersion);
        sql(limited, "INSERT INTO player_profiles(uuid,username) VALUES ('" + leader + "','Leader')");
        try (var lease = PartyEpochLease.acquire(limited, "lobby")) {
            createParty(limited, lease.instanceId(), leader, 8);
        }
        assertEquals(0, scalar(limited, "SELECT count(*) FROM parties WHERE closed_at IS NULL"));
    }

    @Test
    void activeLeaderAndCapacityAreDeferredCommitConstraints() throws Exception {
        try (var lease = PartyEpochLease.acquire(source, "lobby")) {
            UUID party = createParty(lease.instanceId(), leader, 2);
            sql(source, "INSERT INTO party_members(membership_id,party_id,player_uuid) VALUES ('"
                    + UUID.randomUUID() + "','" + party + "','" + target + "')");
            UUID third = UUID.randomUUID();
            sql(source, "INSERT INTO player_profiles(uuid,username) VALUES ('" + third + "','Third')");
            assertThrows(SQLException.class, () -> sql(source,
                    "INSERT INTO party_members(membership_id,party_id,player_uuid) VALUES ('"
                            + UUID.randomUUID() + "','" + party + "','" + third + "')"));
            assertThrows(SQLException.class, () -> sql(source,
                    "UPDATE party_members SET left_at=clock_timestamp() WHERE player_uuid='" + leader + "'"));
            assertThrows(SQLException.class, () -> sql(source,
                    "UPDATE parties SET leader_uuid='" + third + "' WHERE id='" + party + "'"));
            try (var connection = source.getConnection()) {
                connection.setAutoCommit(false);
                execute(connection, "UPDATE parties SET leader_uuid='" + target + "',updated_at=clock_timestamp(),revision=revision+1 WHERE id='" + party + "'");
                execute(connection, "UPDATE party_members SET left_at=clock_timestamp() WHERE player_uuid='" + leader + "'");
                connection.commit();
            }
            assertEquals(1, scalar(source, "SELECT count(*) FROM parties WHERE leader_uuid='" + target + "'"));
        }
    }

    @Test
    void partiesCannotCommitWithoutLeaderMembershipOrCloseWithMembers() throws Exception {
        try (var lease = PartyEpochLease.acquire(source, "lobby")) {
            assertThrows(SQLException.class, () -> sql(source,
                    "INSERT INTO parties(id,leader_uuid,instance_id) VALUES ('" + UUID.randomUUID()
                            + "','" + leader + "','" + lease.instanceId() + "')"));
            UUID party = createParty(lease.instanceId(), leader, 8);
            assertThrows(SQLException.class, () -> sql(source,
                    "UPDATE parties SET closed_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id='" + party + "'"));
            assertEquals(1, scalar(source, "SELECT count(*) FROM parties WHERE closed_at IS NULL"));
        }
    }

    @Test
    void leavingAndRejoiningUsesNewMembershipIdentityAndPreservesHistory() throws Exception {
        try (var lease = PartyEpochLease.acquire(source, "lobby")) {
            UUID party = createParty(lease.instanceId(), leader, 8);
            UUID original = UUID.randomUUID();
            sql(source, "INSERT INTO party_members(membership_id,party_id,player_uuid) VALUES ('" + original + "','" + party + "','" + target + "')");
            sql(source, "UPDATE party_members SET left_at=clock_timestamp() WHERE membership_id='" + original + "'");
            sql(source, "INSERT INTO party_members(membership_id,party_id,player_uuid) VALUES ('" + UUID.randomUUID() + "','" + party + "','" + target + "')");
            assertEquals(2, scalar(source, "SELECT count(*) FROM party_members WHERE player_uuid='" + target + "'"));
            assertEquals(1, scalar(source, "SELECT count(*) FROM party_members WHERE player_uuid='" + target + "' AND left_at IS NULL"));
            assertThrows(SQLException.class, () -> sql(source,
                    "UPDATE party_members SET left_at=NULL WHERE membership_id='" + original + "'"));
        }
    }

    @Test
    void concurrentMembershipInTwoPartiesCanOnlyCommitOnce() throws Exception {
        try (var lease = PartyEpochLease.acquire(source, "lobby")) {
            UUID otherLeader = UUID.randomUUID();
            sql(source, "INSERT INTO player_profiles(uuid,username) VALUES ('" + otherLeader + "','OtherLeader')");
            UUID first = createParty(lease.instanceId(), leader, 8);
            UUID second = createParty(lease.instanceId(), otherLeader, 8);
            var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var a = pool.submit(() -> addMemberAfter(start, first));
                var b = pool.submit(() -> addMemberAfter(start, second));
                start.countDown();
                assertEquals(1, a.get(10, TimeUnit.SECONDS) + b.get(10, TimeUnit.SECONDS));
            }
            assertEquals(1, scalar(source, "SELECT count(*) FROM party_members WHERE player_uuid='" + target + "' AND left_at IS NULL"));
        }
    }

    @Test
    void competingMembersCannotOverfillPartyAtCommit() throws Exception {
        try (var lease = PartyEpochLease.acquire(source, "lobby")) {
            UUID third = UUID.randomUUID();
            sql(source, "INSERT INTO player_profiles(uuid,username) VALUES ('" + third + "','Third')");
            UUID party = createParty(lease.instanceId(), leader, 2);
            var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> addCapacityCandidate(start, party, target));
                var second = pool.submit(() -> addCapacityCandidate(start, party, third));
                start.countDown();
                assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
            }
            assertEquals(2, scalar(source, "SELECT count(*) FROM party_members WHERE party_id='" + party + "' AND left_at IS NULL"));
        }
    }

    @Test
    void invitationsEnforceIdentityTtlResolutionAndUnresolvedUniqueness() throws Exception {
        try (var lease = PartyEpochLease.acquire(source, "lobby")) {
            UUID party = createParty(lease.instanceId(), leader, 8);
            UUID invitation = invite(party, leader, target);
            assertThrows(SQLException.class, () -> invite(party, leader, target));
            assertThrows(SQLException.class, () -> invite(party, leader, leader));
            assertThrows(SQLException.class, () -> invite(party, leader, UUID.randomUUID()));
            assertThrows(SQLException.class, () -> sql(source, "UPDATE party_invites SET expires_at=created_at WHERE id='" + invitation + "'"));
            assertThrows(SQLException.class, () -> sql(source, "UPDATE party_invites SET resolution='DENIED' WHERE id='" + invitation + "'"));
            assertThrows(SQLException.class, () -> sql(source, "UPDATE party_invites SET resolved_at=clock_timestamp() WHERE id='" + invitation + "'"));
            assertThrows(SQLException.class, () -> sql(source, "UPDATE party_invites SET resolved_at=clock_timestamp(),resolution='WRONG' WHERE id='" + invitation + "'"));
            sql(source, "UPDATE party_invites SET resolved_at=clock_timestamp(),resolution='DENIED' WHERE id='" + invitation + "'");
            invite(party, leader, target);
            assertEquals(2, scalar(source, "SELECT count(*) FROM party_invites"));
            assertEquals(1, scalar(source, "SELECT count(*) FROM party_members WHERE left_at IS NULL"));
        }
    }

    @Test
    void invalidCapacityRevisionTimestampsAndForeignKeysRollback() throws Exception {
        try (var lease = PartyEpochLease.acquire(source, "lobby")) {
            UUID party = createParty(lease.instanceId(), leader, 8);
            for (String assignment : new String[]{"max_members=1", "max_members=33", "revision=-1",
                    "updated_at=created_at-INTERVAL '1 second'", "leader_uuid='" + UUID.randomUUID() + "'",
                    "instance_id='" + UUID.randomUUID() + "'"}) {
                assertThrows(SQLException.class, () -> sql(source, "UPDATE parties SET " + assignment + " WHERE id='" + party + "'"));
            }
            assertThrows(SQLException.class, () -> sql(source,
                    "UPDATE party_members SET left_at=joined_at-INTERVAL '1 second' WHERE party_id='" + party + "'"));
            assertEquals(8, scalar(source, "SELECT max_members FROM parties WHERE id='" + party + "'"));
        }
    }

    @Test
    void partyPrivacyIsIndependentAndRequiresExistingProfile() throws Exception {
        assertThrows(SQLException.class, () -> sql(source,
                "INSERT INTO party_preferences(player_uuid) VALUES ('" + UUID.randomUUID() + "')"));
        sql(source, "INSERT INTO party_preferences(player_uuid) VALUES ('" + target + "')");
        assertEquals(1, scalar(source, "SELECT count(*) FROM party_preferences WHERE invites_enabled"));
        sql(source, "UPDATE party_preferences SET invites_enabled=false WHERE player_uuid='" + target + "'");
        assertEquals(1, scalar(source, "SELECT count(*) FROM party_preferences WHERE NOT invites_enabled"));
        assertEquals(0, scalar(source, "SELECT count(*) FROM social_preferences"));
        assertEquals(0, scalar(source, "SELECT count(*) FROM display_preferences"));
        sql(source, "DELETE FROM player_profiles WHERE uuid='" + target + "'");
        assertEquals(0, scalar(source, "SELECT count(*) FROM party_preferences"));
    }

    @Test
    void privacyRepositoryDefaultsWithoutWritesPersistsAndDoesNotTouchOtherSettings() throws Exception {
        var repository = new JdbcPartyPreferencesRepository(source);
        assertEquals(new PartyPreferences(target, true), repository.load(target));
        assertEquals(0, scalar(source, "SELECT count(*) FROM party_preferences"));
        sql(source, "INSERT INTO social_preferences(player_uuid,friend_requests_enabled) VALUES ('" + target + "',false)");
        sql(source, "INSERT INTO display_preferences(player_uuid,show_bossbar,show_scoreboard) VALUES ('" + target + "',false,true)");
        assertEquals(new PartyPreferences(target, false), repository.save(target, false));
        var recreated = new JdbcPartyPreferencesRepository(source);
        assertEquals(new PartyPreferences(target, false), recreated.load(target));
        assertEquals(1, scalar(source, "SELECT count(*) FROM social_preferences WHERE NOT friend_requests_enabled"));
        assertEquals(1, scalar(source, "SELECT count(*) FROM display_preferences WHERE NOT show_bossbar AND show_scoreboard"));
        assertEquals(new PartyPreferences(target, true), recreated.save(target, true));
        assertEquals(new PartyPreferences(target, true), repository.load(target));
        assertEquals(1, scalar(source, "SELECT count(*) FROM party_preferences"));
    }

    @Test
    void privacyRepositoryMissingPlayersAndStorageFailuresNeverProduceDefaultsOrSuccess() throws Exception {
        var repository = new JdbcPartyPreferencesRepository(source);
        UUID missing = UUID.randomUUID();
        assertEquals(PartyException.Code.PLAYER_NOT_FOUND,
                assertThrows(PartyException.class, () -> repository.load(missing)).code());
        assertEquals(PartyException.Code.PLAYER_NOT_FOUND,
                assertThrows(PartyException.class, () -> repository.save(missing, false)).code());
        assertThrows(NullPointerException.class, () -> repository.load(null));
        assertThrows(NullPointerException.class, () -> repository.save(null, true));
        assertEquals(0, scalar(source, "SELECT count(*) FROM party_preferences"));
        repository.save(target, false);
        sql(source, """
                CREATE FUNCTION reject_party_privacy() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected privacy failure'; END $$;
                CREATE TRIGGER reject_party_privacy BEFORE UPDATE ON party_preferences
                FOR EACH ROW EXECUTE FUNCTION reject_party_privacy();
                """);
        assertThrows(ProfileStorageException.class, () -> repository.save(target, true));
        assertFalse(repository.load(target).invitesEnabled());
        var unavailable = direct();
        unavailable.setPortNumbers(new int[]{1});
        unavailable.setConnectTimeout(1);
        assertThrows(ProfileStorageException.class, () -> new JdbcPartyPreferencesRepository(unavailable).load(target));
    }

    private int addMemberAfter(CountDownLatch start, UUID party) throws Exception {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try {
            sql(source, "INSERT INTO party_members(membership_id,party_id,player_uuid) VALUES ('"
                    + UUID.randomUUID() + "','" + party + "','" + target + "')");
            return 1;
        } catch (SQLException expectedConflict) {
            assertEquals("23505", expectedConflict.getSQLState());
            return 0;
        }
    }

    private int addCapacityCandidate(CountDownLatch start, UUID party, UUID player) throws Exception {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try {
            sql(source, "INSERT INTO party_members(membership_id,party_id,player_uuid) VALUES ('"
                    + UUID.randomUUID() + "','" + party + "','" + player + "')");
            return 1;
        } catch (SQLException expectedConflict) {
            assertEquals("23514", expectedConflict.getSQLState());
            return 0;
        }
    }

    private void terminateLeaseBackend() throws Exception {
        sql(source, """
                WITH lease_key AS (
                    SELECT hashtextextended('ClubCore:parties:' || current_database() || ':' || current_schema() || ':lobby', 0) AS key
                )
                SELECT pg_terminate_backend(pid) FROM pg_locks, lease_key
                WHERE locktype='advisory' AND granted AND objsubid=1
                    AND classid=((key >> 32) & 4294967295)::oid AND objid=(key & 4294967295)::oid
                """);
    }

    private UUID createParty(UUID epoch, UUID owner, int capacity) throws SQLException {
        return createParty(source, epoch, owner, capacity);
    }

    private UUID createParty(PGSimpleDataSource data, UUID epoch, UUID owner, int capacity) throws SQLException {
        UUID party = UUID.randomUUID();
        try (var connection = data.getConnection()) {
            connection.setAutoCommit(false);
            execute(connection, "INSERT INTO parties(id,leader_uuid,instance_id,max_members) VALUES ('"
                    + party + "','" + owner + "','" + epoch + "'," + capacity + ")");
            execute(connection, "INSERT INTO party_members(membership_id,party_id,player_uuid) VALUES ('"
                    + UUID.randomUUID() + "','" + party + "','" + owner + "')");
            connection.commit();
        }
        return party;
    }

    private UUID invite(UUID party, UUID sender, UUID recipient) throws SQLException {
        UUID id = UUID.randomUUID();
        sql(source, "INSERT INTO party_invites(id,party_id,inviter_uuid,invitee_uuid,expires_at) VALUES ('"
                + id + "','" + party + "','" + sender + "','" + recipient + "',NOW()+INTERVAL '120 seconds')");
        return id;
    }

    private static PGSimpleDataSource direct() {
        return direct(postgres.getPort());
    }

    private static PGSimpleDataSource direct(int port) {
        var data = new PGSimpleDataSource();
        data.setServerNames(new String[]{"127.0.0.1"});
        data.setPortNumbers(new int[]{port});
        data.setDatabaseName("postgres");
        data.setUser("postgres");
        data.setSocketTimeout(10);
        return data;
    }

    private static void sql(DataSource data, String sql) throws SQLException {
        try (var connection = data.getConnection()) {
            execute(connection, sql);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var query = connection.createStatement()) {
            query.setQueryTimeout(10);
            query.execute(sql);
        }
    }

    private static long scalar(DataSource data, String sql) throws SQLException {
        try (var connection = data.getConnection(); var query = connection.createStatement();
             var rows = query.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
