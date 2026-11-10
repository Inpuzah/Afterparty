package com.inpuzah.club.core.party.repository;

import com.inpuzah.club.core.party.model.PartyPreferences;
import com.inpuzah.club.core.repository.ProfileStorageException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Blocking persistence, also reused inside the social facade's privacy transactions. */
public final class JdbcPartyPreferencesRepository {
    private final DataSource source;

    public JdbcPartyPreferencesRepository(DataSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    public PartyPreferences load(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return transaction(connection -> readLocked(connection, playerId));
    }

    public PartyPreferences save(UUID playerId, boolean invitesEnabled) {
        Objects.requireNonNull(playerId, "playerId");
        return transaction(connection -> saveLocked(connection, playerId, invitesEnabled));
    }

    /** Participates in a caller-owned transaction without committing or closing it. */
    public static PartyPreferences saveLocked(Connection connection, UUID playerId, boolean invitesEnabled) throws SQLException {
        readLocked(connection, playerId);
        try (var query = connection.prepareStatement("""
                INSERT INTO party_preferences(player_uuid,invites_enabled) VALUES (?,?)
                ON CONFLICT (player_uuid) DO UPDATE SET invites_enabled=EXCLUDED.invites_enabled,
                    updated_at=clock_timestamp()
                """)) {
            query.setObject(1, playerId);
            query.setBoolean(2, invitesEnabled);
            query.executeUpdate();
        }
        return new PartyPreferences(playerId, invitesEnabled);
    }

    /** Shares the profile lock with the social facade's coherent privacy snapshot. */
    public static PartyPreferences readLocked(Connection connection, UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        // Share the profile-row lock with block writers and pair invitation checks.
        try (var query = connection.prepareStatement("""
                SELECT COALESCE(s.invites_enabled,true) FROM player_profiles p
                LEFT JOIN party_preferences s ON s.player_uuid=p.uuid
                WHERE p.uuid=? FOR UPDATE OF p
                """)) {
            query.setObject(1, playerId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new PartyException(PartyException.Code.PLAYER_NOT_FOUND, "Player profile does not exist");
                }
                return new PartyPreferences(playerId, rows.getBoolean(1));
            }
        }
    }

    private PartyPreferences transaction(Operation work) {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var result = work.run(connection);
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
            throw new ProfileStorageException("Party privacy storage failed; a save may have committed", error);
        }
    }

    @FunctionalInterface
    private interface Operation {
        PartyPreferences run(Connection connection) throws SQLException;
    }
}
