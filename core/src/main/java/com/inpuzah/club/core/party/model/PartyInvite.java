package com.inpuzah.club.core.party.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Unresolved invitation snapshot; expiry and authorization still require a transaction. */
public record PartyInvite(UUID id, UUID partyId, UUID inviterId, UUID inviteeId,
                          Instant createdAt, Instant expiresAt) {
    public PartyInvite {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(inviterId, "inviterId");
        Objects.requireNonNull(inviteeId, "inviteeId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (inviterId.equals(inviteeId)) {
            throw new IllegalArgumentException("Players cannot invite themselves");
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }

    public boolean isExpired(Instant now) {
        return !Objects.requireNonNull(now, "now").isBefore(expiresAt);
    }
}
