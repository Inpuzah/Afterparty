package com.inpuzah.club.core.config;

import java.time.Duration;

public record ProfileConfig(Duration loadTimeout, boolean showReadyMessage) {

    public ProfileConfig {
        if (loadTimeout == null
                || loadTimeout.compareTo(Duration.ofSeconds(1)) < 0
                || loadTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new ConfigException("profile.load-timeout-ms must be between 1000 and 60000");
        }
    }
}
