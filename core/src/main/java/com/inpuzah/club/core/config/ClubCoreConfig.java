package com.inpuzah.club.core.config;

import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.function.Function;

/**
 * Reads and validates {@code config.yml}. Environment credentials take precedence.
 */
public record ClubCoreConfig(DatabaseConfig database, ProfileConfig profile, AdminCommandConfig adminCommands) {

    public static final String PASSWORD_ENV = "CLUB_DB_PASSWORD";

    public static ClubCoreConfig load(ConfigurationSection root, Function<String, String> environment) {
        ConfigurationSection db = section(root, "database");
        ConfigurationSection profile = section(root, "profile");

        String password = environment.apply(PASSWORD_ENV);
        if (password == null || password.isEmpty()) {
            Object configuredPassword = db.get("password");
            if (configuredPassword != null && !(configuredPassword instanceof String)) {
                throw new ConfigException("database.password must be a string");
            }
            password = configuredPassword instanceof String value ? value : null;
        }
        if (password == null || password.isEmpty()) {
            throw new ConfigException("Set database.password in the plugin config.yml or the "
                    + PASSWORD_ENV + " environment variable");
        }

        DatabaseConfig database = new DatabaseConfig(
                string(db, "host"),
                integer(db, "port"),
                string(db, "name"),
                string(db, "username"),
                password,
                string(db, "ssl-mode"),
                integer(db, "pool-size"),
                integer(db, "connection-timeout-ms"));

        ProfileConfig profileConfig = new ProfileConfig(
                Duration.ofMillis(integer(profile, "load-timeout-ms")),
                bool(profile, "show-ready-message"));

        return new ClubCoreConfig(database, profileConfig, new AdminCommandConfig(
                optionalLimit(root, "admin-commands.max-xp"),
                optionalLimit(root, "admin-commands.max-coins")));
    }

    private static long optionalLimit(ConfigurationSection root, String path) {
        Object value = root.get(path);
        if (value == null) {
            return 0;
        }
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new ConfigException(path + " must be a nonnegative whole number (0 means unlimited)");
        }
        return ((Number) value).longValue();
    }

    private static ConfigurationSection section(ConfigurationSection root, String path) {
        ConfigurationSection section = root.getConfigurationSection(path);
        if (section == null) {
            throw new ConfigException("Missing '" + path + "' section in config.yml");
        }
        return section;
    }

    private static String string(ConfigurationSection section, String key) {
        Object value = section.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new ConfigException(path(section, key) + " must be a non-empty string");
        }
        return s.trim();
    }

    private static int integer(ConfigurationSection section, String key) {
        Object value = section.get(key);
        if (!(value instanceof Integer i)) {
            throw new ConfigException(path(section, key) + " must be a whole number");
        }
        return i;
    }

    private static boolean bool(ConfigurationSection section, String key) {
        Object value = section.get(key);
        if (!(value instanceof Boolean b)) {
            throw new ConfigException(path(section, key) + " must be true or false");
        }
        return b;
    }

    private static String path(ConfigurationSection section, String key) {
        return section.getCurrentPath() + "." + key;
    }
}
