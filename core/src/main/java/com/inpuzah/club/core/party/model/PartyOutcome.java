package com.inpuzah.club.core.party.model;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record PartyOutcome(Code code, Optional<UUID> partyId, Optional<UUID> inviteId) {
    public enum Code {
        CREATED, INVITED, ACCEPTED, DENIED, LEFT, KICKED, PROMOTED, DISBANDED,
        ALREADY_IN_PARTY, NOT_IN_PARTY, NOT_LEADER, PARTY_FULL, INVITE_EXPIRED,
        INVITE_NOT_FOUND, ALREADY_INVITED, TARGET_UNAVAILABLE, BLOCKED_OR_RESTRICTED,
        INVALID_TARGET, RATE_LIMITED, STALE_PARTY
    }

    public PartyOutcome {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(inviteId, "inviteId");
    }

    public static PartyOutcome of(Code code) {
        return new PartyOutcome(code, Optional.empty(), Optional.empty());
    }

    public static PartyOutcome party(Code code, UUID partyId) {
        return new PartyOutcome(code, Optional.of(partyId), Optional.empty());
    }
}
