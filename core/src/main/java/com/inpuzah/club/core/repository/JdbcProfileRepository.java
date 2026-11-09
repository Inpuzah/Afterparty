package com.inpuzah.club.core.repository;

import com.inpuzah.club.core.model.PlayerProfile;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcProfileRepository implements ProfileRepository {

    private static final String COLUMNS = "uuid, username, first_joined_at, last_seen_at, level, experience, coins";

    private static final String UPSERT = """
            INSERT INTO player_profiles (uuid, username)
            VALUES (?, ?)
            ON CONFLICT (uuid) DO UPDATE
                SET username     = EXCLUDED.username,
                    last_seen_at = NOW(),
                    updated_at   = NOW()
            RETURNING %s
            """.formatted(COLUMNS);

    private static final String FIND = "SELECT %s FROM player_profiles WHERE uuid = ?".formatted(COLUMNS);

    private static final String TOUCH = """
            UPDATE player_profiles
               SET last_seen_at = NOW(),
                   updated_at   = NOW()
             WHERE uuid = ?
            """;

    private final DataSource dataSource;

    public JdbcProfileRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    @Override
    public PlayerProfile upsertOnJoin(UUID uuid, String username) {
        Objects.requireNonNull(uuid, "uuid");
        PlayerProfile.validateUsername(username);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            statement.setObject(1, uuid);
            statement.setString(2, username);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ProfileStorageException("Upsert returned no row for " + uuid, null);
                }
                return map(rs);
            }
        } catch (SQLException e) {
            throw new ProfileStorageException("Failed to upsert profile " + uuid, e);
        }
    }

    @Override
    public Optional<PlayerProfile> findById(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND)) {
            statement.setObject(1, uuid);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new ProfileStorageException("Failed to load profile " + uuid, e);
        }
    }

    @Override
    public boolean touchLastSeen(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(TOUCH)) {
            statement.setObject(1, uuid);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new ProfileStorageException("Failed to update last seen for " + uuid, e);
        }
    }

    private static PlayerProfile map(ResultSet rs) throws SQLException {
        return new PlayerProfile(
                rs.getObject("uuid", UUID.class),
                rs.getString("username"),
                rs.getObject("first_joined_at", OffsetDateTime.class).toInstant(),
                rs.getObject("last_seen_at", OffsetDateTime.class).toInstant(),
                rs.getInt("level"),
                rs.getLong("experience"),
                rs.getLong("coins"));
    }
}
