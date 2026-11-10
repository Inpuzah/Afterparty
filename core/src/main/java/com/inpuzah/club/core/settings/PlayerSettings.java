package com.inpuzah.club.core.settings;

import java.util.Objects;
import java.util.UUID;

/** Persistent choices, independent of server-wide display switches and temporary ownership. */
public record PlayerSettings(UUID playerId, boolean bossbarVisible, boolean scoreboardVisible) {
    public PlayerSettings {
        Objects.requireNonNull(playerId, "playerId");
    }
}
