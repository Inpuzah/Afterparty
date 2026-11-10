package com.inpuzah.club.core.party.repository;

import com.inpuzah.club.core.repository.ProfileStorageException;

import org.postgresql.ds.PGSimpleDataSource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Locale;
import java.util.UUID;

/**
 * Owns a dedicated physical PostgreSQL session, never a transaction-pooled connection.
 * Acquire off-thread before party workers start; close only after they drain.
 */
public final class PartyEpochLease implements AutoCloseable {
    private static final String LOCK_KEY =
            "hashtextextended('ClubCore:parties:' || current_database() || ':' || current_schema() || ':' || ?, 0)";
    private final Connection connection;
    private final String ownerId;
    private final UUID instanceId;
    private boolean closed;

    private PartyEpochLease(Connection connection, String ownerId, UUID instanceId) {
        this.connection = connection;
        this.ownerId = ownerId;
        this.instanceId = instanceId;
    }

    public static PartyEpochLease acquire(PGSimpleDataSource directSource, String ownerId) {
        return acquire(directSource, ownerId, null, null, null);
    }

    public static PartyEpochLease acquireMatching(PGSimpleDataSource directSource, String ownerId, DataSource applicationSource) {
        Objects.requireNonNull(applicationSource, "applicationSource");
        String database;
        String schema;
        UUID previous;
        try (var connection = applicationSource.getConnection();
             var query = prepare(connection, """
                     SELECT current_database(),current_schema(),
                         (SELECT instance_id FROM party_owners WHERE owner_id=?)
                     """, ownerId);
             var rows = query.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("Application database namespace is unavailable");
            }
            database = rows.getString(1);
            schema = rows.getString(2);
            previous = rows.getObject(3, UUID.class);
        } catch (SQLException error) {
            throw new ProfileStorageException("Could not verify party application database namespace", error);
        }
        var acquired = acquire(directSource, ownerId, database, schema, previous);
        try (var connection = applicationSource.getConnection();
             var query = prepare(connection, """
                     SELECT 1 FROM party_owners WHERE owner_id=? AND instance_id=?
                     """, ownerId, acquired.instanceId()); var rows = query.executeQuery()) {
            if (!rows.next()) {
                throw new IllegalStateException("Party direct and application endpoints do not share the acquired epoch");
            }
            return acquired;
        } catch (SQLException | RuntimeException error) {
            try {
                acquired.close();
            } catch (RuntimeException close) {
                error.addSuppressed(close);
            }
            throw new ProfileStorageException("Party direct and application database verification failed", error);
        }
    }

    private static PartyEpochLease acquire(PGSimpleDataSource directSource, String ownerId,
                                          String expectedDatabase, String expectedSchema, UUID expectedPrevious) {
        Objects.requireNonNull(directSource, "directSource");
        Objects.requireNonNull(ownerId, "ownerId");
        if (!ownerId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Party owner identifier must be 1-64 letters, digits, underscores or hyphens");
        }
        for (String host : directSource.getServerNames()) {
            if (host.toLowerCase(Locale.ROOT).contains("-pooler")) {
                throw new IllegalArgumentException("Party epoch lease requires a direct PostgreSQL endpoint, not a transaction pooler");
            }
        }
        Connection connection = null;
        try {
            connection = directSource.getConnection();
            connection.setAutoCommit(true);
            if (expectedDatabase != null) {
                try (var query = prepare(connection, "SELECT current_database(),current_schema()");
                     var rows = query.executeQuery()) {
                    if (!rows.next() || !expectedDatabase.equals(rows.getString(1))
                            || !Objects.equals(expectedSchema, rows.getString(2))) {
                        throw new IllegalStateException("Party direct and application database/schema must match");
                    }
                }
            }
            try (var query = prepare(connection, "SELECT pg_try_advisory_lock(" + LOCK_KEY + ")", ownerId);
                 var rows = query.executeQuery()) {
                if (!rows.next() || !rows.getBoolean(1)) {
                    throw new IllegalStateException("Another process owns the party epoch for " + ownerId);
                }
            }
            UUID next = UUID.randomUUID();
            var lease = new PartyEpochLease(connection, ownerId, next);
            connection.setAutoCommit(false);
            try {
                UUID previous = null;
                try (var query = prepare(connection, "SELECT instance_id FROM party_owners WHERE owner_id=? FOR UPDATE", ownerId);
                     var rows = query.executeQuery()) {
                    if (rows.next()) {
                        previous = rows.getObject(1, UUID.class);
                    }
                }
                if (expectedDatabase != null && !Objects.equals(previous, expectedPrevious)) {
                    throw new IllegalStateException("Party endpoints disagree on the predecessor epoch; refusing reconciliation");
                }
                if (previous != null) {
                    lease.endEpoch(previous);
                }
                update(connection, "INSERT INTO party_instances(instance_id,owner_id) VALUES (?,?)", next, ownerId);
                update(connection, """
                        INSERT INTO party_owners(owner_id,instance_id) VALUES (?,?)
                        ON CONFLICT (owner_id) DO UPDATE SET instance_id=EXCLUDED.instance_id
                        """, ownerId, next);
                connection.commit();
                connection.setAutoCommit(true);
                return lease;
            } catch (SQLException | RuntimeException error) {
                rollback(connection, error);
                throw error;
            }
        } catch (SQLException error) {
            closeFailedAcquire(connection, error);
            throw new ProfileStorageException("Could not acquire or reconcile party epoch; startup must stop", error);
        } catch (RuntimeException error) {
            closeFailedAcquire(connection, error);
            throw error;
        }
    }

    public UUID instanceId() {
        return instanceId;
    }

    public String ownerId() {
        return ownerId;
    }

    /** A lost lease must disable party work, not silently establish a new epoch. */
    public synchronized void verify() {
        if (closed) {
            throw new IllegalStateException("Party epoch lease is closed");
        }
        try (var query = prepare(connection, """
                SELECT 1 FROM party_owners o JOIN party_instances i USING(instance_id)
                WHERE o.owner_id=? AND o.instance_id=? AND i.owner_id=o.owner_id AND i.ended_at IS NULL
                """, ownerId, instanceId); var rows = query.executeQuery()) {
            if (!rows.next()) {
                throw new IllegalStateException("Party epoch ownership no longer matches");
            }
        } catch (SQLException error) {
            throw new ProfileStorageException("Party epoch lease lost; stop party work", error);
        }
    }

    private void endEpoch(UUID epoch) throws SQLException {
        // Scope every change to the recorded predecessor and its owner, never all non-current epochs.
        try (var query = prepare(connection,
                "SELECT instance_id FROM party_instances WHERE instance_id=? AND owner_id=? FOR UPDATE", epoch, ownerId);
             var rows = query.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("Recorded party epoch has a different owner or is missing");
            }
        }
        update(connection, """
                UPDATE party_invites SET resolved_at=GREATEST(clock_timestamp(),created_at),resolution='PARTY_CLOSED'
                WHERE resolved_at IS NULL AND party_id IN
                    (SELECT id FROM parties WHERE instance_id=?)
                """, epoch);
        update(connection, """
                UPDATE party_members SET left_at=GREATEST(clock_timestamp(),joined_at)
                WHERE left_at IS NULL AND party_id IN
                    (SELECT id FROM parties WHERE instance_id=?)
                """, epoch);
        update(connection, """
                UPDATE parties SET closed_at=GREATEST(statement_timestamp(),updated_at),
                    updated_at=GREATEST(statement_timestamp(),updated_at),revision=revision+1
                WHERE instance_id=? AND closed_at IS NULL
                """, epoch);
        update(connection, """
                UPDATE party_instances SET ended_at=GREATEST(clock_timestamp(),started_at)
                WHERE instance_id=? AND ended_at IS NULL
                """, epoch);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try (connection) {
            connection.setAutoCommit(false);
            try {
                endEpoch(instanceId);
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                rollback(connection, error);
                throw error;
            }
        } catch (SQLException error) {
            throw new ProfileStorageException("Could not close party epoch; next startup must reconcile it", error);
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException error) {
            original.addSuppressed(error);
        }
    }

    private static void closeFailedAcquire(Connection connection, Exception original) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException error) {
                original.addSuppressed(error);
            }
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... args) throws SQLException {
        var query = connection.prepareStatement(sql);
        try {
            query.setQueryTimeout(10);
            for (int i = 0; i < args.length; i++) {
                query.setObject(i + 1, args[i]);
            }
            return query;
        } catch (SQLException error) {
            query.close();
            throw error;
        }
    }

    private static void update(Connection connection, String sql, Object... args) throws SQLException {
        try (var query = prepare(connection, sql, args)) {
            query.executeUpdate();
        }
    }
}
