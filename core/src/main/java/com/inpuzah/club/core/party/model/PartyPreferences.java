package com.inpuzah.club.core.party.model;

import java.util.Objects;
import java.util.UUID;

/** Separate from V3 display choices and V4 friend-request privacy. */
public record PartyPreferences(UUID playerId, boolean invitesEnabled) {
    public PartyPreferences {
        Objects.requireNonNull(playerId, "playerId");
    }
}
