package com.inpuzah.club.core.config;

/** Zero means no per-command limit; domain balance and overflow rules still apply. */
public record AdminCommandConfig(long maxXp, long maxCoins) {
    public AdminCommandConfig {
        if (maxXp < 0 || maxCoins < 0) {
            throw new ConfigException("Admin command limits must be nonnegative (0 means unlimited)");
        }
    }
}
