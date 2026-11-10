package com.inpuzah.club.core.social.model;

import java.util.Objects;
import java.util.UUID;

public record SocialPreferences(UUID playerId, boolean friendRequestsEnabled) {
    public SocialPreferences {
        Objects.requireNonNull(playerId, "playerId");
    }
}
