package com.inpuzah.club.core.progression;

import com.inpuzah.club.core.repository.ProfileStorageException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcProgressionRepository implements ProgressionRepository {

    private static final String FIND_AWARD = """
            SELECT player_uuid, amount, experience_after, source, reason, actor_uuid
            FROM experience_awards WHERE operation_id = ?
            """;

    private final DataSource dataSource;

    public JdbcProgressionRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    @Override
    public ProgressionSnapshot getProgress(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = dataSource.getConnection()) {
            return new ProgressionSnapshot(playerId, readExperience(connection, playerId, false));
        } catch (SQLException e) {
            throw new ProfileStorageException("Failed to load progression for " + playerId, e);
        }
    }

    @Override
    public XpAwardResult awardXp(XpAwardRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            return transact(request);
        } catch (SQLException e) {
            // Different-player requests with the same operation ID lock different rows. The primary key
            // decides the winner; the losing transaction is rolled back before inspecting that result.
            if ("23505".equals(e.getSQLState())) {
                try (Connection connection = dataSource.getConnection()) {
                    Optional<XpAwardResult> winner = findAward(connection, request);
                    if (winner.isPresent()) {
                        return winner.get();
                    }
                } catch (SQLException lookupError) {
                    e.addSuppressed(lookupError);
                }
            }
            throw new ProfileStorageException("Failed to award XP for operation " + request.operationId(), e);
        }
    }

    private XpAwardResult transact(XpAwardRequest request) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                Optional<XpAwardResult> replay = findAward(connection, request);
                if (replay.isPresent()) {
                    connection.commit();
                    return replay.get();
                }
                long previous = readExperience(connection, request.playerId(), true);
                replay = findAward(connection, request);
                if (replay.isPresent()) {
                    connection.commit();
                    return replay.get();
                }

                long total;
                try {
                    total = Math.addExact(previous, request.amount());
                } catch (ArithmeticException e) {
                    throw new ProgressionException(ProgressionException.Code.XP_OVERFLOW,
                            "XP award would exceed the supported lifetime XP range");
                }
                XpAwardResult result = new XpAwardResult(request, previous, total, false);
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE player_profiles SET experience = ?, level = ?, updated_at = NOW()
                        WHERE uuid = ?
                        """)) {
                    update.setLong(1, total);
                    update.setInt(2, result.level());
                    update.setObject(3, request.playerId());
                    if (update.executeUpdate() != 1) {
                        throw new SQLException("Locked profile disappeared during XP award");
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO experience_awards
                            (operation_id, player_uuid, amount, experience_after, level_after, source, reason, actor_uuid)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setObject(1, request.operationId());
                    insert.setObject(2, request.playerId());
                    insert.setLong(3, request.amount());
                    insert.setLong(4, total);
                    insert.setInt(5, result.level());
                    insert.setString(6, request.source());
                    insert.setString(7, request.reason());
                    insert.setObject(8, request.actorId());
                    insert.executeUpdate();
                }
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    failure.addSuppressed(rollbackError);
                }
                throw failure;
            }
        }
    }

    private long readExperience(Connection connection, UUID playerId, boolean lock) throws SQLException {
        String sql = "SELECT experience FROM player_profiles WHERE uuid = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, playerId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ProgressionException(ProgressionException.Code.PROFILE_NOT_FOUND,
                            "Profile does not exist for " + playerId);
                }
                return rs.getLong("experience");
            }
        }
    }

    private Optional<XpAwardResult> findAward(Connection connection, XpAwardRequest request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_AWARD)) {
            statement.setObject(1, request.operationId());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                if (!request.playerId().equals(rs.getObject("player_uuid", UUID.class))
                        || request.amount() != rs.getLong("amount")
                        || !request.source().equals(rs.getString("source"))
                        || !request.reason().equals(rs.getString("reason"))
                        || !Objects.equals(request.actorId(), rs.getObject("actor_uuid", UUID.class))) {
                    throw new ProgressionException(ProgressionException.Code.OPERATION_CONFLICT,
                            "XP operation ID was already used with a different payload: " + request.operationId());
                }
                long total = rs.getLong("experience_after");
                return Optional.of(new XpAwardResult(request, Math.subtractExact(total, request.amount()), total, true));
            }
        }
    }
}
