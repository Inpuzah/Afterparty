package com.inpuzah.club.core.display;

import com.inpuzah.club.core.repository.ProfileStorageException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

public final class JdbcDisplayPreferenceRepository implements DisplayPreferenceRepository {
    private final DataSource dataSource;

    public JdbcDisplayPreferenceRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    @Override
    public DisplayPreferences load(UUID uuid) {
        Objects.requireNonNull(uuid);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT show_bossbar, show_scoreboard FROM display_preferences WHERE player_uuid = ?")) {
            statement.setObject(1, uuid);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? map(rs) : DisplayPreferences.DEFAULT;
            }
        } catch (SQLException e) {
            throw new ProfileStorageException("Failed to load display preferences for " + uuid, e);
        }
    }

    @Override
    public DisplayPreferences save(UUID uuid, boolean bossbar, boolean enabled) {
        Objects.requireNonNull(uuid);
        String column = bossbar ? "show_bossbar" : "show_scoreboard";
        String sql = """
                INSERT INTO display_preferences (player_uuid, %s) VALUES (?, ?)
                ON CONFLICT (player_uuid) DO UPDATE SET %s = EXCLUDED.%s, updated_at = NOW()
                RETURNING show_bossbar, show_scoreboard
                """.formatted(column, column, column);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, uuid);
            statement.setBoolean(2, enabled);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Preference save returned no row");
                }
                return map(rs);
            }
        } catch (SQLException e) {
            throw new ProfileStorageException("Failed to save display preferences for " + uuid, e);
        }
    }

    private static DisplayPreferences map(ResultSet rs) throws SQLException {
        return new DisplayPreferences(rs.getBoolean("show_bossbar"), rs.getBoolean("show_scoreboard"));
    }
}
