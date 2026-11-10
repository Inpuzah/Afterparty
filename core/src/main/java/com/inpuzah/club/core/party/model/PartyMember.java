package com.inpuzah.club.core.party.model;

import com.inpuzah.club.core.model.PlayerProfile;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Active membership; presence is resolved separately on the Paper thread. */
public record PartyMember(UUID playerId, String username, Instant joinedAt) {
    public PartyMember {
        Objects.requireNonNull(playerId, "playerId");
        PlayerProfile.validateUsername(username);
        Objects.requireNonNull(joinedAt, "joinedAt");
    }
}
