package com.inpuzah.club.core.economy;

import com.inpuzah.club.core.repository.ProfileStorageException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcEconomyRepository implements EconomyRepository {

    private static final String FIND_OPERATION = """
            SELECT player_uuid, amount_delta, balance_after, operation_type, source, reason, actor_uuid
            FROM currency_transactions WHERE operation_id = ?
            """;

    private final DataSource dataSource;

    public JdbcEconomyRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    @Override
    public BalanceSnapshot balance(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = dataSource.getConnection()) {
            return new BalanceSnapshot(playerId, readBalance(connection, playerId, false));
        } catch (SQLException e) {
            throw new ProfileStorageException("Failed to load balance for " + playerId, e);
        }
    }

    @Override
    public CurrencyOperationResult apply(CurrencyOperation operation) {
        Objects.requireNonNull(operation, "operation");
        try {
            return transact(operation);
        } catch (SQLException e) {
            // Same-ID requests for different players lock different rows. Roll back the loser before
            // reading the primary-key winner, so its balance change can never leak through.
            if ("23505".equals(e.getSQLState())) {
                try (Connection connection = dataSource.getConnection()) {
                    Optional<CurrencyOperationResult> winner = findOperation(connection, operation);
                    if (winner.isPresent()) {
                        return winner.get();
                    }
                } catch (SQLException lookupError) {
                    e.addSuppressed(lookupError);
                }
            }
            throw new ProfileStorageException("Failed coin operation " + operation.operationId(), e);
        }
    }

    private CurrencyOperationResult transact(CurrencyOperation operation) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                Optional<CurrencyOperationResult> replay = findOperation(connection, operation);
                if (replay.isPresent()) {
                    connection.commit();
                    return replay.get();
                }
                long previous = readBalance(connection, operation.playerId(), true);
                replay = findOperation(connection, operation);
                if (replay.isPresent()) {
                    connection.commit();
                    return replay.get();
                }
                if (operation.type() == CurrencyOperationType.DEBIT && previous < operation.amount()) {
                    connection.rollback();
                    return new CurrencyOperationResult(operation, CurrencyOperationResult.Status.INSUFFICIENT_FUNDS,
                            previous, previous, false);
                }

                long balance;
                if (operation.type() == CurrencyOperationType.CREDIT) {
                    // This guarded addition also prevents overflow, including on legacy BIGINT balances.
                    if (previous > BalanceSnapshot.MAX_BALANCE
                            || operation.amount() > BalanceSnapshot.MAX_BALANCE - previous) {
                        throw new EconomyException(EconomyException.Code.BALANCE_LIMIT_EXCEEDED,
                                "Credit would exceed the maximum Club Coins balance");
                    }
                    balance = Math.addExact(previous, operation.amount());
                } else {
                    balance = Math.subtractExact(previous, operation.amount());
                }
                CurrencyOperationResult result = new CurrencyOperationResult(operation,
                        CurrencyOperationResult.Status.SUCCESS, previous, balance, false);
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE player_profiles SET coins = ?, updated_at = NOW() WHERE uuid = ?
                        """)) {
                    update.setLong(1, balance);
                    update.setObject(2, operation.playerId());
                    if (update.executeUpdate() != 1) {
                        throw new SQLException("Locked profile disappeared during coin operation");
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO currency_transactions
                            (operation_id, player_uuid, amount_delta, balance_after, operation_type, source, reason, actor_uuid)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setObject(1, operation.operationId());
                    insert.setObject(2, operation.playerId());
                    insert.setLong(3, operation.delta());
                    insert.setLong(4, balance);
                    insert.setString(5, operation.type().name());
                    insert.setString(6, operation.source());
                    insert.setString(7, operation.reason());
                    insert.setObject(8, operation.actorId());
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

    private long readBalance(Connection connection, UUID playerId, boolean lock) throws SQLException {
        String sql = "SELECT coins FROM player_profiles WHERE uuid = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, playerId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new EconomyException(EconomyException.Code.PROFILE_NOT_FOUND,
                            "Profile does not exist for " + playerId);
                }
                return rs.getLong("coins");
            }
        }
    }

    private Optional<CurrencyOperationResult> findOperation(Connection connection, CurrencyOperation operation)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_OPERATION)) {
            statement.setObject(1, operation.operationId());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                if (!operation.playerId().equals(rs.getObject("player_uuid", UUID.class))
                        || operation.delta() != rs.getLong("amount_delta")
                        || !operation.type().name().equals(rs.getString("operation_type"))
                        || !operation.source().equals(rs.getString("source"))
                        || !operation.reason().equals(rs.getString("reason"))
                        || !Objects.equals(operation.actorId(), rs.getObject("actor_uuid", UUID.class))) {
                    throw new EconomyException(EconomyException.Code.OPERATION_CONFLICT,
                            "Coin operation ID was already used with a different payload: " + operation.operationId());
                }
                long balance = rs.getLong("balance_after");
                return Optional.of(new CurrencyOperationResult(operation, CurrencyOperationResult.Status.SUCCESS,
                        Math.subtractExact(balance, operation.delta()), balance, true));
            }
        }
    }
}
