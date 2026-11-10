package com.inpuzah.club.core.party;

import com.inpuzah.club.core.config.ConfigException;
import com.inpuzah.club.core.config.DatabaseConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.postgresql.ds.PGSimpleDataSource;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Startup-only configuration; no party-only value is changed by display reload. */
public record PartyConfig(boolean enabled, String ownerId, String directHost, int directPort,
                          PartyPolicy policy, Duration disconnectGrace, boolean menuEnabled, boolean chatEnabled) {
    public PartyConfig {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(directHost, "directHost");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(disconnectGrace, "disconnectGrace");
        if (!ownerId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new ConfigException("party.owner-id must be 1-64 letters, digits, underscores or hyphens");
        }
        if (!directHost.matches("[A-Za-z0-9.\\-]+|\\[[0-9A-Fa-f:]+]")
                || enabled && directHost.toLowerCase(Locale.ROOT).contains("-pooler")) {
            throw new ConfigException("party.direct-host must be a direct PostgreSQL hostname or IP, not a transaction pooler");
        }
        if (directPort < 1 || directPort > 65535) {
            throw new ConfigException("party.direct-port must be 1-65535");
        }
        if (disconnectGrace.compareTo(Duration.ofSeconds(1)) < 0
                || disconnectGrace.compareTo(Duration.ofHours(1)) > 0) {
            throw new ConfigException("party.disconnect-grace-seconds must be 1-3600");
        }
    }

    public static PartyConfig load(ConfigurationSection root, DatabaseConfig database) {
        if (root.contains("party") && root.getConfigurationSection("party") == null) {
            throw new ConfigException("party must be a configuration section");
        }
        PartyPolicy policy;
        try {
            policy = new PartyPolicy(integer(root, "max-members", 8),
                    Duration.ofSeconds(integer(root, "invite-expiry-seconds", 120)),
                    integer(root, "invites-per-minute", 5), integer(root, "max-pending-invites", 10),
                    Duration.ofSeconds(integer(root, "same-target-cooldown-seconds", 30)));
        } catch (IllegalArgumentException error) {
            throw new ConfigException("party limits require max-members 2-32, invite-expiry-seconds 1-3600, "
                    + "invites-per-minute/max-pending-invites 1-100, same-target-cooldown-seconds 1-86400");
        }
        boolean enabled = bool(root, "enabled", false);
        String host = text(root, "direct-host", database.host());
        return new PartyConfig(enabled, text(root, "owner-id", "lobby-1"), host,
                integer(root, "direct-port", database.port()), policy,
                Duration.ofSeconds(integer(root, "disconnect-grace-seconds", 300)),
                bool(root, "menu-enabled", true), bool(root, "chat-enabled", true));
    }

    public PGSimpleDataSource leaseDataSource(DatabaseConfig database) {
        if (!enabled) {
            throw new IllegalStateException("Cannot create a lease datasource while parties are disabled");
        }
        var source = new PGSimpleDataSource();
        source.setServerNames(new String[]{directHost});
        source.setPortNumbers(new int[]{directPort});
        source.setDatabaseName(database.name());
        source.setUser(database.username());
        source.setPassword(database.password());
        source.setSslMode(database.sslMode());
        source.setConnectTimeout((int) Math.max(1, (database.connectionTimeoutMs() + 999) / 1000));
        source.setSocketTimeout(10);
        source.setApplicationName("ClubCore-Party-Epoch");
        return source;
    }

    private static int integer(ConfigurationSection root, String key, int fallback) {
        Object value = root.get("party." + key);
        if (value == null) { return fallback; }
        if (value instanceof Integer number) { return number; }
        throw new ConfigException("party." + key + " must be an integer");
    }

    private static String text(ConfigurationSection root, String key, String fallback) {
        Object value = root.get("party." + key);
        if (value == null) { return fallback; }
        if (value instanceof String text) { return text; }
        throw new ConfigException("party." + key + " must be a string");
    }

    private static boolean bool(ConfigurationSection root, String key, boolean fallback) {
        Object value = root.get("party." + key);
        if (value == null) { return fallback; }
        if (value instanceof Boolean enabled) { return enabled; }
        throw new ConfigException("party." + key + " must be true or false");
    }
}
