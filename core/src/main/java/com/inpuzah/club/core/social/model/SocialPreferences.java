package com.inpuzah.club.core.social.model;

import java.util.Objects;
import java.util.UUID;

public record SocialPreferences(UUID playerId, boolean friendRequestsEnabled, boolean partyInvitesEnabled) {
    public SocialPreferences(UUID playerId, boolean friendRequestsEnabled) {
        this(playerId, friendRequestsEnabled, true);
    }

    public SocialPreferences {
        Objects.requireNonNull(playerId, "playerId");
    }
}
