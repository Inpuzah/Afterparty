package com.inpuzah.club.core.social;

import com.inpuzah.club.core.config.ConfigException;
import org.bukkit.configuration.ConfigurationSection;

public record SocialConfig(int requestsPerMinute, int incomingLimit, int retryCooldownSeconds, boolean interactionsEnabled) {
    public SocialConfig(int requestsPerMinute, int incomingLimit, int retryCooldownSeconds) {
        this(requestsPerMinute, incomingLimit, retryCooldownSeconds, true);
    }

    public static final SocialConfig DEFAULT = new SocialConfig(5, 25, 30);

    public SocialConfig {
        if (requestsPerMinute < 1 || requestsPerMinute > 100 || incomingLimit < 1 || incomingLimit > 1000
                || retryCooldownSeconds < 1 || retryCooldownSeconds > 86400) {
            throw new ConfigException("Social limits require requests-per-minute 1-100, incoming-limit 1-1000, "
                    + "retry-cooldown-seconds 1-86400");
        }
    }

    public static SocialConfig load(ConfigurationSection root) {
        if (root.contains("social") && root.getConfigurationSection("social") == null) {
            throw new ConfigException("social must be a configuration section");
        }
        return new SocialConfig(integer(root, "requests-per-minute", DEFAULT.requestsPerMinute),
                integer(root, "incoming-limit", DEFAULT.incomingLimit),
                integer(root, "retry-cooldown-seconds", DEFAULT.retryCooldownSeconds),
                interactions(root));
    }

    private static boolean interactions(ConfigurationSection root) {
        Object value = root.get("social.interactions-enabled");
        if (value == null) {
            return true;
        }
        if (!(value instanceof Boolean enabled)) {
            throw new ConfigException("social.interactions-enabled must be true or false");
        }
        return enabled;
    }

    private static int integer(ConfigurationSection root, String key, int fallback) {
        Object value = root.get("social." + key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Integer number)) {
            throw new ConfigException("social." + key + " must be a whole number");
        }
        return number;
    }
}
