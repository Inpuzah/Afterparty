package com.inpuzah.club.core.party.repository;

import com.inpuzah.club.core.party.PartyPolicy;
import com.inpuzah.club.core.party.model.Party;
import com.inpuzah.club.core.party.model.PartyInvite;
import com.inpuzah.club.core.party.model.PartyMember;
import com.inpuzah.club.core.party.model.PartyOutcome;
import com.inpuzah.club.core.party.PartyDeparture;
import com.inpuzah.club.core.repository.ProfileStorageException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.inpuzah.club.core.party.model.PartyOutcome.Code.*;

/** Lock order: current epoch, profile UUIDs in SQL order, then party/membership/invite rows. */
public final class JdbcPartyRepository implements PartyRepository {
    private final DataSource source;
    private final PartyEpochLease lease;
    private final PartyPolicy policy;
    private final Clock clock;

    public JdbcPartyRepository(DataSource source, PartyEpochLease lease, PartyPolicy policy) {
        this(source, lease, policy, Clock.systemUTC());
    }

    public JdbcPartyRepository(DataSource source, PartyEpochLease lease, PartyPolicy policy, Clock clock) {
        this.source = Objects.requireNonNull(source);
        this.lease = Objects.requireNonNull(lease);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public PartyOutcome create(UUID actor) {
        Objects.requireNonNull(actor);
        return transaction(connection -> {
            requireProfiles(connection, actor, actor);
            var existing = partyFor(connection, actor);
            return existing == null ? PartyOutcome.party(CREATED, create(connection, actor, clock.instant()))
                    : PartyOutcome.party(ALREADY_IN_PARTY, existing);
        });
    }

    @Override
    public PartyOutcome invite(UUID actor, UUID target) {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(target);
        return transaction(connection -> {
            if (actor.equals(target)) {
                return PartyOutcome.of(INVALID_TARGET);
            }
            if (!lockProfiles(connection, actor, target)) {
                return PartyOutcome.of(TARGET_UNAVAILABLE);
            }
            UUID party = partyFor(connection, actor);
            if (party != null && !snapshot(connection, party).isLeader(actor)) {
                return PartyOutcome.of(NOT_LEADER);
            }
            if (partyFor(connection, target) != null) {
                return PartyOutcome.of(ALREADY_IN_PARTY);
            }
            if (!allowed(connection, actor, target)) {
                return PartyOutcome.of(BLOCKED_OR_RESTRICTED);
            }
            Instant now = clock.instant();
            if (party != null) {
                expire(connection, party, now);
                try (var query = prepare(connection, """
                        SELECT id FROM party_invites WHERE party_id=? AND invitee_uuid=? AND resolved_at IS NULL
                        """, party, target); var rows = query.executeQuery()) {
                    if (rows.next()) {
                        return new PartyOutcome(ALREADY_INVITED, Optional.of(party),
                                Optional.of(rows.getObject(1, UUID.class)));
                    }
                }
                if (snapshot(connection, party).isFull()) {
                    return PartyOutcome.of(PARTY_FULL);
                }
                if (count(connection, "SELECT count(*) FROM party_invites WHERE party_id=? AND resolved_at IS NULL", party)
                        >= policy.maxPendingInvites()) {
                    return PartyOutcome.of(RATE_LIMITED);
                }
            }
            if (count(connection, """
                    SELECT count(*) FROM party_invites WHERE inviter_uuid=? AND invitee_uuid=?
                    AND GREATEST(created_at,resolved_at)>?
                    """, actor, target, now.minus(policy.sameTargetCooldown())) > 0
                    || count(connection, """
                    SELECT count(DISTINCT invitee_uuid) FROM party_invites
                    WHERE inviter_uuid=? AND created_at>? AND invitee_uuid<>?
                    """, actor, now.minusSeconds(60), target) >= policy.invitesPerMinute()) {
                return PartyOutcome.of(RATE_LIMITED);
            }
            if (party == null) {
                party = create(connection, actor, now);
            }
            UUID invitation = UUID.randomUUID();
            update(connection, """
                    INSERT INTO party_invites(id,party_id,inviter_uuid,invitee_uuid,created_at,expires_at)
                    VALUES (?,?,?,?,?,?)
                    """, invitation, party, actor, target, now, now.plus(policy.inviteTtl()));
            touch(connection, party, now);
            return new PartyOutcome(INVITED, Optional.of(party), Optional.of(invitation));
        });
    }

    @Override public PartyOutcome accept(UUID actor, UUID inviteId) { return resolve(actor, inviteId, true); }
    @Override public PartyOutcome deny(UUID actor, UUID inviteId) { return resolve(actor, inviteId, false); }

    private PartyOutcome resolve(UUID actor, UUID inviteId, boolean accept) {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(inviteId);
        return transaction(connection -> {
            PartyInvite invitation;
            String resolution;
            try (var query = prepare(connection, """
                    SELECT i.*,p.closed_at FROM party_invites i JOIN parties p ON p.id=i.party_id
                    WHERE i.id=? AND i.invitee_uuid=? AND p.instance_id=?
                    """, inviteId, actor, lease.instanceId()); var rows = query.executeQuery()) {
                if (!rows.next() || rows.getObject("closed_at") != null) {
                    return PartyOutcome.of(INVITE_NOT_FOUND);
                }
                invitation = new PartyInvite(inviteId, rows.getObject("party_id", UUID.class),
                        rows.getObject("inviter_uuid", UUID.class), actor,
                        rows.getTimestamp("created_at").toInstant(), rows.getTimestamp("expires_at").toInstant());
                resolution = rows.getString("resolution");
            }
            requireProfiles(connection, actor, invitation.inviterId());
            Instant now = clock.instant();
            if (resolution != null) {
                return PartyOutcome.of("EXPIRED".equals(resolution) ? INVITE_EXPIRED : INVITE_NOT_FOUND);
            }
            if (invitation.isExpired(now)) {
                resolveInvite(connection, inviteId, "EXPIRED", now);
                touch(connection, invitation.partyId(), now);
                return PartyOutcome.of(INVITE_EXPIRED);
            }
            if (!accept) {
                resolveInvite(connection, inviteId, "DENIED", now);
                touch(connection, invitation.partyId(), now);
                return PartyOutcome.party(DENIED, invitation.partyId());
            }
            var party = snapshot(connection, invitation.partyId());
            // Transfer cancels old invitations; only the current leader can authorize entry.
            if (!party.isLeader(invitation.inviterId())) {
                return PartyOutcome.of(INVITE_NOT_FOUND);
            }
            if (partyFor(connection, actor) != null) {
                return PartyOutcome.of(ALREADY_IN_PARTY);
            }
            if (!allowed(connection, invitation.inviterId(), actor)) {
                return PartyOutcome.of(BLOCKED_OR_RESTRICTED);
            }
            if (party.isFull()) {
                return PartyOutcome.of(PARTY_FULL);
            }
            update(connection, "INSERT INTO party_members(membership_id,party_id,player_uuid,joined_at) VALUES (?,?,?,?)",
                    UUID.randomUUID(), party.id(), actor, now);
            resolveInvite(connection, inviteId, "ACCEPTED", now);
            // Joining one party invalidates all other invitations to this player in this epoch.
            var affected = new ArrayList<UUID>();
            try (var query = prepare(connection, """
                    UPDATE party_invites SET resolved_at=GREATEST(?,created_at),resolution='CANCELLED'
                    WHERE invitee_uuid=? AND resolved_at IS NULL AND party_id IN
                        (SELECT id FROM parties WHERE instance_id=?)
                    RETURNING party_id
                    """, now, actor, lease.instanceId()); var rows = query.executeQuery()) {
                while (rows.next()) {
                    affected.add(rows.getObject(1, UUID.class));
                }
            }
            for (UUID otherParty : Set.copyOf(affected)) {
                if (!otherParty.equals(party.id())) {
                    touch(connection, otherParty, now);
                }
            }
            touch(connection, party.id(), now);
            return PartyOutcome.party(ACCEPTED, party.id());
        });
    }

    @Override
    public PartyOutcome leave(UUID actor, Set<UUID> onlineMembers) {
        return leave(actor, onlineMembers, null, 0, null);
    }

    @Override
    public PartyOutcome leaveIfCurrent(UUID actor, Set<UUID> onlineMembers, UUID partyId, long revision) {
        Objects.requireNonNull(partyId);
        if (revision < 0) { throw new IllegalArgumentException("Party revision must be nonnegative"); }
        return leave(actor, onlineMembers, partyId, revision, null);
    }

    @Override
    public PartyOutcome leaveDisconnected(UUID actor, Set<UUID> onlineMembers, PartyDeparture departure) {
        return leave(actor, onlineMembers, null, 0, Objects.requireNonNull(departure));
    }

    private PartyOutcome leave(UUID actor, Set<UUID> onlineMembers, UUID expectedParty, long expectedRevision,
                               PartyDeparture departure) {
        Objects.requireNonNull(actor);
        Set<UUID> online = Set.copyOf(onlineMembers);
        return transaction(connection -> {
            if (departure != null) {
                if (!lockProfiles(connection, actor, actor)) {
                    return PartyOutcome.of(NOT_IN_PARTY);
                }
            } else {
                requireProfiles(connection, actor, actor);
            }
            UUID partyId = partyFor(connection, actor);
            if (partyId == null) {
                return PartyOutcome.of(NOT_IN_PARTY);
            }
            var party = snapshot(connection, partyId);
            if (expectedParty != null && (!expectedParty.equals(party.id()) || expectedRevision != party.revision())) {
                return PartyOutcome.of(STALE_PARTY);
            }
            if (departure != null && !departure.claim()) {
                return PartyOutcome.of(STALE_PARTY);
            }
            Instant now = clock.instant();
            removeMember(connection, partyId, actor, now);
            var remaining = party.members().stream().filter(member -> !member.playerId().equals(actor)).toList();
            if (remaining.isEmpty()) {
                closeParty(connection, partyId, now);
            } else if (party.isLeader(actor)) {
                UUID successor = remaining.stream().filter(member -> online.contains(member.playerId())).findFirst()
                        .orElse(remaining.getFirst()).playerId();
                transfer(connection, partyId, successor, now);
            } else {
                touch(connection, partyId, now);
            }
            return PartyOutcome.party(LEFT, partyId);
        });
    }

    @Override public PartyOutcome kick(UUID actor, UUID target) { return manage(actor, target, false); }
    @Override public PartyOutcome promote(UUID actor, UUID target) { return manage(actor, target, true); }

    private PartyOutcome manage(UUID actor, UUID target, boolean promote) {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(target);
        return transaction(connection -> {
            if (actor.equals(target)) {
                return PartyOutcome.of(INVALID_TARGET);
            }
            requireProfiles(connection, actor, target);
            UUID partyId = partyFor(connection, actor);
            if (partyId == null) {
                return PartyOutcome.of(NOT_IN_PARTY);
            }
            var party = snapshot(connection, partyId);
            if (!party.isLeader(actor)) {
                return PartyOutcome.of(NOT_LEADER);
            }
            if (!party.isMember(target)) {
                return PartyOutcome.of(INVALID_TARGET);
            }
            Instant now = clock.instant();
            if (promote) {
                transfer(connection, partyId, target, now);
            } else {
                removeMember(connection, partyId, target, now);
                touch(connection, partyId, now);
            }
            return PartyOutcome.party(promote ? PROMOTED : KICKED, partyId);
        });
    }

    @Override
    public PartyOutcome disband(UUID actor) {
        return disband(actor, null, 0);
    }

    @Override
    public PartyOutcome disbandIfCurrent(UUID actor, UUID partyId, long revision) {
        Objects.requireNonNull(partyId);
        if (revision < 0) { throw new IllegalArgumentException("Party revision must be nonnegative"); }
        return disband(actor, partyId, revision);
    }

    private PartyOutcome disband(UUID actor, UUID expectedParty, long expectedRevision) {
        Objects.requireNonNull(actor);
        return transaction(connection -> {
            requireProfiles(connection, actor, actor);
            UUID partyId = partyFor(connection, actor);
            if (partyId == null) {
                return PartyOutcome.of(NOT_IN_PARTY);
            }
            var party = snapshot(connection, partyId);
            if (expectedParty != null && (!expectedParty.equals(party.id()) || expectedRevision != party.revision())) {
                return PartyOutcome.of(STALE_PARTY);
            }
            if (!party.isLeader(actor)) {
                return PartyOutcome.of(NOT_LEADER);
            }
            closeParty(connection, partyId, clock.instant());
            return PartyOutcome.party(DISBANDED, partyId);
        });
    }

    @Override
    public void withParty(UUID player, java.util.function.Consumer<Party> delivery) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(delivery);
        transaction(connection -> {
            requireProfiles(connection, player, player);
            UUID id = partyFor(connection, player);
            if (id == null) {
                throw new PartyException(PartyException.Code.NOT_IN_PARTY, "Player is not in a party");
            }
            // Hold the epoch lock through delivery so a committed removal cannot overtake the message.
            delivery.accept(snapshot(connection, id));
            return null;
        });
    }

    @Override
    public Optional<Party> getParty(UUID player) {
        Objects.requireNonNull(player);
        return transaction(connection -> {
            requireProfiles(connection, player, player);
            UUID id = partyFor(connection, player);
            return id == null ? Optional.empty() : Optional.of(snapshot(connection, id));
        });
    }

    @Override
    public List<PartyInvite> getInvites(UUID player, int offset, int limit) {
        Objects.requireNonNull(player);
        if (offset < 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Invite offset must be nonnegative and limit 1-100");
        }
        return transaction(connection -> {
            requireProfiles(connection, player, player);
            var invitations = new ArrayList<PartyInvite>();
            try (var query = prepare(connection, """
                    SELECT i.* FROM party_invites i JOIN parties p ON p.id=i.party_id
                    WHERE i.invitee_uuid=? AND i.resolved_at IS NULL AND i.expires_at>?
                        AND p.closed_at IS NULL AND p.instance_id=? AND p.leader_uuid=i.inviter_uuid
                    ORDER BY i.created_at,i.id LIMIT ? OFFSET ?
                    """, player, clock.instant(), lease.instanceId(), limit, offset); var rows = query.executeQuery()) {
                while (rows.next()) {
                    invitations.add(new PartyInvite(rows.getObject("id", UUID.class), rows.getObject("party_id", UUID.class),
                            rows.getObject("inviter_uuid", UUID.class), player, rows.getTimestamp("created_at").toInstant(),
                            rows.getTimestamp("expires_at").toInstant()));
                }
            }
            return List.copyOf(invitations);
        });
    }

    private UUID create(Connection connection, UUID actor, Instant now) throws SQLException {
        UUID party = UUID.randomUUID();
        update(connection, """
                INSERT INTO parties(id,leader_uuid,instance_id,max_members,created_at,updated_at) VALUES (?,?,?,?,?,?)
                """, party, actor, lease.instanceId(), policy.maxMembers(), now, now);
        update(connection, "INSERT INTO party_members(membership_id,party_id,player_uuid,joined_at) VALUES (?,?,?,?)",
                UUID.randomUUID(), party, actor, now);
        return party;
    }

    private UUID partyFor(Connection connection, UUID player) throws SQLException {
        try (var query = prepare(connection, """
                SELECT p.id,p.instance_id FROM parties p JOIN party_members m ON m.party_id=p.id
                WHERE m.player_uuid=? AND m.left_at IS NULL AND p.closed_at IS NULL
                """, player); var rows = query.executeQuery()) {
            if (!rows.next()) {
                return null;
            }
            if (!lease.instanceId().equals(rows.getObject(2, UUID.class))) {
                throw new PartyException(PartyException.Code.STALE_EPOCH, "Membership belongs to another party epoch");
            }
            return rows.getObject(1, UUID.class);
        }
    }

    private Party snapshot(Connection connection, UUID id) throws SQLException {
        var members = new ArrayList<PartyMember>();
        try (var query = prepare(connection, """
                SELECT m.player_uuid,p.username,m.joined_at FROM party_members m
                JOIN player_profiles p ON p.uuid=m.player_uuid WHERE m.party_id=? AND m.left_at IS NULL
                """, id); var rows = query.executeQuery()) {
            while (rows.next()) {
                members.add(new PartyMember(rows.getObject(1, UUID.class), rows.getString(2), rows.getTimestamp(3).toInstant()));
            }
        }
        try (var query = prepare(connection, "SELECT * FROM parties WHERE id=? AND instance_id=? AND closed_at IS NULL",
                id, lease.instanceId()); var rows = query.executeQuery()) {
            if (!rows.next()) {
                throw new PartyException(PartyException.Code.STALE_EPOCH, "Party is closed or belongs to another epoch");
            }
            return new Party(id, lease.instanceId(), rows.getObject("leader_uuid", UUID.class),
                    rows.getTimestamp("created_at").toInstant(), rows.getTimestamp("updated_at").toInstant(),
                    rows.getLong("revision"), rows.getInt("max_members"), members);
        }
    }

    private boolean allowed(Connection connection, UUID sender, UUID target) throws SQLException {
        return count(connection, """
                SELECT count(*) FROM player_blocks WHERE (blocker_uuid=? AND blocked_uuid=?)
                    OR (blocker_uuid=? AND blocked_uuid=?)
                """, sender, target, target, sender) == 0
                && count(connection, "SELECT count(*) FROM party_preferences WHERE player_uuid=? AND NOT invites_enabled", target) == 0;
    }

    private void transfer(Connection connection, UUID party, UUID successor, Instant now) throws SQLException {
        update(connection, "UPDATE parties SET leader_uuid=? WHERE id=?", successor, party);
        update(connection, """
                UPDATE party_invites SET resolved_at=GREATEST(?,created_at),resolution='CANCELLED'
                WHERE party_id=? AND resolved_at IS NULL
                """, now, party);
        touch(connection, party, now);
    }

    private void closeParty(Connection connection, UUID party, Instant now) throws SQLException {
        update(connection, "UPDATE party_members SET left_at=GREATEST(?,joined_at) WHERE party_id=? AND left_at IS NULL", now, party);
        update(connection, """
                UPDATE party_invites SET resolved_at=GREATEST(?,created_at),resolution='PARTY_CLOSED'
                WHERE party_id=? AND resolved_at IS NULL
                """, now, party);
        update(connection, """
                UPDATE parties SET closed_at=GREATEST(?,updated_at),updated_at=GREATEST(?,updated_at),revision=revision+1 WHERE id=?
                """, now, now, party);
    }

    private static void removeMember(Connection connection, UUID party, UUID player, Instant now) throws SQLException {
        update(connection, "UPDATE party_members SET left_at=GREATEST(?,joined_at) WHERE party_id=? AND player_uuid=? AND left_at IS NULL",
                now, party, player);
    }

    private static void touch(Connection connection, UUID party, Instant now) throws SQLException {
        update(connection, "UPDATE parties SET revision=revision+1,updated_at=GREATEST(?,updated_at) WHERE id=?", now, party);
    }

    private static void expire(Connection connection, UUID party, Instant now) throws SQLException {
        if (update(connection, """
                UPDATE party_invites SET resolved_at=GREATEST(?,created_at),resolution='EXPIRED'
                WHERE party_id=? AND resolved_at IS NULL AND expires_at<=?
                """, now, party, now) > 0) {
            touch(connection, party, now);
        }
    }

    private static void resolveInvite(Connection connection, UUID invite, String resolution, Instant now) throws SQLException {
        update(connection, "UPDATE party_invites SET resolved_at=GREATEST(?,created_at),resolution=? WHERE id=?", now, resolution, invite);
    }

    private static boolean lockProfiles(Connection connection, UUID first, UUID second) throws SQLException {
        return count(connection, """
                SELECT count(*) FROM (SELECT uuid FROM player_profiles WHERE uuid IN (?,?) ORDER BY uuid FOR UPDATE) locked
                """, first, second) == (first.equals(second) ? 1 : 2);
    }

    private static void requireProfiles(Connection connection, UUID first, UUID second) throws SQLException {
        if (!lockProfiles(connection, first, second)) {
            throw new PartyException(PartyException.Code.PLAYER_NOT_FOUND, "Player profile does not exist");
        }
    }

    private <T> T transaction(Operation<T> operation) {
        lease.verify();
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // Conservative single-server serialization also fences graceful close/reconciliation.
                try (var query = prepare(connection, """
                        SELECT i.instance_id FROM party_instances i JOIN party_owners o USING(instance_id)
                        WHERE i.instance_id=? AND i.owner_id=o.owner_id AND i.ended_at IS NULL FOR UPDATE OF i
                        """, lease.instanceId()); var rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new PartyException(PartyException.Code.STALE_EPOCH, "Party epoch is no longer active");
                    }
                }
                T result = operation.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    error.addSuppressed(rollback);
                }
                throw error;
            }
        } catch (SQLException error) {
            throw new ProfileStorageException("Party operation failed; a mutation may have committed", error);
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... args) throws SQLException {
        var query = connection.prepareStatement(sql);
        try {
            query.setQueryTimeout(10);
            for (int i = 0; i < args.length; i++) {
                query.setObject(i + 1, args[i] instanceof Instant instant ? OffsetDateTime.ofInstant(instant, ZoneOffset.UTC) : args[i]);
            }
            return query;
        } catch (SQLException error) {
            query.close();
            throw error;
        }
    }

    private static long count(Connection connection, String sql, Object... args) throws SQLException {
        try (var query = prepare(connection, sql, args); var rows = query.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("Count query returned no result");
            }
            return rows.getLong(1);
        }
    }

    private static int update(Connection connection, String sql, Object... args) throws SQLException {
        try (var query = prepare(connection, sql, args)) {
            return query.executeUpdate();
        }
    }

    @FunctionalInterface
    private interface Operation<T> { T run(Connection connection) throws SQLException; }
}
