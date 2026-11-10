package com.inpuzah.club.core.social.repository;

import com.inpuzah.club.core.repository.ProfileStorageException;
import com.inpuzah.club.core.social.SocialConfig;
import com.inpuzah.club.core.social.model.FriendRequest;
import com.inpuzah.club.core.social.model.FriendSummary;
import com.inpuzah.club.core.social.model.Relationship;
import com.inpuzah.club.core.social.model.SocialAction;
import com.inpuzah.club.core.social.model.SocialPreferences;
import com.inpuzah.club.core.social.model.SocialResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.inpuzah.club.core.social.model.SocialResult.*;

/** All relationship writers lock profile rows in PostgreSQL UUID order before inspecting social state. */
public final class JdbcSocialRepository implements SocialRepository {
    private static final String PAIR = "player_low = LEAST(?::uuid, ?::uuid) AND player_high = GREATEST(?::uuid, ?::uuid)";
    private final DataSource source;
    private final SocialConfig config;
    private final Clock clock;

    public JdbcSocialRepository(DataSource source, SocialConfig config) {
        this(source, config, Clock.systemUTC());
    }

    public JdbcSocialRepository(DataSource source, SocialConfig config, Clock clock) {
        this.source = Objects.requireNonNull(source);
        this.config = Objects.requireNonNull(config);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Relationship relationship(UUID player, UUID other) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(other, "other");
        if (player.equals(other)) {
            throw new IllegalArgumentException("Relationship requires two distinct profiles");
        }
        return transaction(connection -> {
            if (count(connection, "SELECT count(*) FROM (SELECT uuid FROM player_profiles "
                    + "WHERE uuid IN (?, ?) ORDER BY uuid FOR UPDATE) locked", player, other) != 2) {
                throw new SocialException(SocialException.Code.PLAYER_NOT_FOUND, "Player profile does not exist");
            }
            if (count(connection, "SELECT count(*) FROM player_blocks WHERE blocker_uuid=? AND blocked_uuid=?",
                    player, other) > 0) {
                return Relationship.BLOCKED;
            }
            if (friends(connection, player, other)) {
                return Relationship.FRIENDS;
            }
            Instant now = clock.instant();
            if (pending(connection, other, player, now)) {
                return Relationship.INCOMING_REQUEST;
            }
            if (pending(connection, player, other, now)) {
                return Relationship.OUTGOING_REQUEST;
            }
            return Relationship.NONE;
        });
    }

    @Override
    public SocialResult mutate(SocialAction action, UUID actor, UUID other) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(other, "other");
        if (actor.equals(other)) {
            return SELF_TARGET;
        }
        return transaction(connection -> {
            if (count(connection, "SELECT count(*) FROM (SELECT uuid FROM player_profiles "
                    + "WHERE uuid IN (?, ?) ORDER BY uuid FOR UPDATE) locked", actor, other) != 2) {
                return PLAYER_NOT_FOUND;
            }
            Instant now = clock.instant();
            return switch (action) {
                case REQUEST -> request(connection, actor, other, now);
                case ACCEPT -> accept(connection, actor, other, now);
                case DENY -> resolve(connection, other, actor, now);
                case CANCEL -> {
                    SocialResult result = resolve(connection, actor, other, now);
                    yield result == DENIED ? CANCELLED : result;
                }
                case REMOVE -> update(connection, "DELETE FROM friendships WHERE " + PAIR,
                        actor, other, actor, other) == 0 ? NOT_FRIENDS : REMOVED;
                case BLOCK -> block(connection, actor, other, now);
                case UNBLOCK -> update(connection, "DELETE FROM player_blocks WHERE blocker_uuid = ? AND blocked_uuid = ?",
                        actor, other) == 0 ? NOT_BLOCKED : UNBLOCKED;
            };
        });
    }

    private SocialResult request(Connection connection, UUID sender, UUID recipient, Instant now) throws SQLException {
        if (friends(connection, sender, recipient)) {
            return ALREADY_FRIENDS;
        }
        if (!allowed(connection, sender, recipient)) {
            return CANNOT_REQUEST;
        }
        if (pending(connection, sender, recipient, now)) {
            return ALREADY_REQUESTED;
        }
        if (pending(connection, recipient, sender, now)) {
            return INCOMING_REQUEST_EXISTS;
        }
        if (count(connection, """
                SELECT count(*) FROM friend_requests WHERE sender_uuid = ? AND recipient_uuid = ?
                AND resolved_at > ?
                """, sender, recipient, now.minusSeconds(config.retryCooldownSeconds())) > 0) {
            return RATE_LIMITED;
        }
        if (count(connection, "SELECT count(*) FROM friend_requests WHERE sender_uuid = ? AND created_at > ? "
                        + "AND recipient_uuid <> ?",
                sender, now.minusSeconds(60), recipient) >= config.requestsPerMinute()) {
            return RATE_LIMITED;
        }
        if (count(connection, """
                SELECT count(*) FROM friend_requests WHERE recipient_uuid = ? AND resolved_at IS NULL AND expires_at > ?
                """, recipient, now) >= config.incomingLimit()) {
            return INBOX_FULL;
        }
        update(connection, """
                INSERT INTO friend_requests(sender_uuid, recipient_uuid, created_at, expires_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (sender_uuid, recipient_uuid) DO UPDATE
                SET created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at, resolved_at = NULL
                """, sender, recipient, now, now.plus(Duration.ofDays(7)));
        return REQUEST_SENT;
    }

    private SocialResult accept(Connection connection, UUID recipient, UUID sender, Instant now) throws SQLException {
        if (!allowed(connection, sender, recipient)) {
            return CANNOT_REQUEST;
        }
        if (friends(connection, sender, recipient)) {
            return ALREADY_FRIENDS;
        }
        if (!pending(connection, sender, recipient, now)) {
            return REQUEST_MISSING;
        }
        update(connection, "INSERT INTO friendships(player_low, player_high) VALUES "
                + "(LEAST(?::uuid, ?::uuid), GREATEST(?::uuid, ?::uuid))", sender, recipient, sender, recipient);
        resolvePair(connection, sender, recipient, now);
        return ACCEPTED;
    }

    private SocialResult block(Connection connection, UUID actor, UUID other, Instant now) throws SQLException {
        int inserted = update(connection, """
                INSERT INTO player_blocks(blocker_uuid, blocked_uuid) VALUES (?, ?) ON CONFLICT DO NOTHING
                """, actor, other);
        update(connection, "DELETE FROM friendships WHERE " + PAIR, actor, other, actor, other);
        resolvePair(connection, actor, other, now);
        return inserted == 0 ? ALREADY_BLOCKED : BLOCKED;
    }

    private SocialResult resolve(Connection connection, UUID sender, UUID recipient, Instant now) throws SQLException {
        return update(connection, """
                UPDATE friend_requests SET resolved_at = ? WHERE sender_uuid = ? AND recipient_uuid = ?
                AND resolved_at IS NULL AND expires_at > ?
                """, now, sender, recipient, now) == 0 ? REQUEST_MISSING : DENIED;
    }

    private void resolvePair(Connection connection, UUID actor, UUID other, Instant now) throws SQLException {
        update(connection, """
                UPDATE friend_requests SET resolved_at = ? WHERE resolved_at IS NULL
                AND ((sender_uuid = ? AND recipient_uuid = ?) OR (sender_uuid = ? AND recipient_uuid = ?))
                """, now, actor, other, other, actor);
    }

    private boolean friends(Connection connection, UUID actor, UUID other) throws SQLException {
        return count(connection, "SELECT count(*) FROM friendships WHERE " + PAIR, actor, other, actor, other) > 0;
    }

    private boolean pending(Connection connection, UUID sender, UUID recipient, Instant now) throws SQLException {
        return count(connection, """
                SELECT count(*) FROM friend_requests WHERE sender_uuid = ? AND recipient_uuid = ?
                AND resolved_at IS NULL AND expires_at > ?
                """, sender, recipient, now) > 0;
    }

    private boolean allowed(Connection connection, UUID sender, UUID recipient) throws SQLException {
        return count(connection, """
                SELECT count(*) FROM player_blocks WHERE (blocker_uuid = ? AND blocked_uuid = ?)
                OR (blocker_uuid = ? AND blocked_uuid = ?)
                """, sender, recipient, recipient, sender) == 0
                && enabled(connection, recipient);
    }

    private boolean enabled(Connection connection, UUID player) throws SQLException {
        return count(connection, "SELECT count(*) FROM social_preferences WHERE player_uuid = ? "
                + "AND NOT friend_requests_enabled", player) == 0;
    }

    @Override
    public List<FriendSummary> listFriends(UUID player, int offset, int limit) {
        return summaries(player, offset, limit, """
                SELECT p.uuid, p.username FROM friendships f
                JOIN player_profiles p ON p.uuid = CASE WHEN f.player_low = ? THEN f.player_high ELSE f.player_low END
                WHERE f.player_low = ? OR f.player_high = ? ORDER BY p.uuid LIMIT ? OFFSET ?
                """, player, player, player, limit, offset);
    }

    @Override
    public List<FriendSummary> listBlocked(UUID player, int offset, int limit) {
        return summaries(player, offset, limit, """
                SELECT p.uuid, p.username FROM player_blocks b JOIN player_profiles p ON p.uuid = b.blocked_uuid
                WHERE b.blocker_uuid = ? ORDER BY p.uuid LIMIT ? OFFSET ?
                """, player, limit, offset);
    }

    private List<FriendSummary> summaries(UUID player, int offset, int limit, String sql, Object... args) {
        page(player, offset, limit);
        try (var connection = source.getConnection(); var query = prepare(connection, sql, args);
             var rows = query.executeQuery()) {
            List<FriendSummary> result = new ArrayList<>();
            while (rows.next()) {
                result.add(new FriendSummary(rows.getObject(1, UUID.class), rows.getString(2)));
            }
            return List.copyOf(result);
        } catch (SQLException e) {
            throw new ProfileStorageException("Could not list social relationships", e);
        }
    }

    @Override
    public List<FriendRequest> listIncomingRequests(UUID player, int offset, int limit) {
        page(player, offset, limit);
        try (var connection = source.getConnection(); var query = prepare(connection, """
                SELECT r.sender_uuid, p.username, r.expires_at FROM friend_requests r
                JOIN player_profiles p ON p.uuid = r.sender_uuid WHERE r.recipient_uuid = ?
                AND r.resolved_at IS NULL AND r.expires_at > ? ORDER BY r.sender_uuid LIMIT ? OFFSET ?
                """, player, clock.instant(), limit, offset); var rows = query.executeQuery()) {
            List<FriendRequest> result = new ArrayList<>();
            while (rows.next()) {
                result.add(new FriendRequest(rows.getObject(1, UUID.class), rows.getString(2),
                        rows.getTimestamp(3).toInstant()));
            }
            return List.copyOf(result);
        } catch (SQLException e) {
            throw new ProfileStorageException("Could not list incoming friend requests", e);
        }
    }

    @Override
    public SocialPreferences preferences(UUID player) {
        Objects.requireNonNull(player, "player");
        return transaction(connection -> {
            requirePlayer(connection, player);
            return new SocialPreferences(player, enabled(connection, player));
        });
    }

    @Override
    public SocialPreferences setPreferences(UUID player, boolean requestsEnabled) {
        Objects.requireNonNull(player, "player");
        return transaction(connection -> {
            requirePlayer(connection, player);
            update(connection, """
                    INSERT INTO social_preferences(player_uuid, friend_requests_enabled) VALUES (?, ?)
                    ON CONFLICT (player_uuid) DO UPDATE SET friend_requests_enabled = EXCLUDED.friend_requests_enabled,
                    updated_at = NOW()
                    """, player, requestsEnabled);
            return new SocialPreferences(player, requestsEnabled);
        });
    }

    private void requirePlayer(Connection connection, UUID player) throws SQLException {
        if (count(connection, "SELECT count(*) FROM (SELECT uuid FROM player_profiles WHERE uuid = ? FOR UPDATE) locked",
                player) != 1) {
            throw new SocialException(SocialException.Code.PLAYER_NOT_FOUND, "Player profile does not exist");
        }
    }

    private static void page(UUID player, int offset, int limit) {
        Objects.requireNonNull(player, "player");
        if (offset < 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("List offset must be nonnegative and limit must be 1-100");
        }
    }

    private <T> T transaction(Transaction<T> work) {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new ProfileStorageException("Social database operation failed; outcome may be uncertain", e);
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... args) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < args.length; i++) {
                Object value = args[i] instanceof Instant instant ? OffsetDateTime.ofInstant(instant, ZoneOffset.UTC) : args[i];
                statement.setObject(i + 1, value);
            }
            return statement;
        } catch (SQLException e) {
            statement.close();
            throw e;
        }
    }

    private static int update(Connection connection, String sql, Object... args) throws SQLException {
        try (var statement = prepare(connection, sql, args)) {
            return statement.executeUpdate();
        }
    }

    private static long count(Connection connection, String sql, Object... args) throws SQLException {
        try (var statement = prepare(connection, sql, args); var rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("Count query returned no result");
            }
            return rows.getLong(1);
        }
    }

    @FunctionalInterface
    private interface Transaction<T> {
        T run(Connection connection) throws SQLException;
    }
}
