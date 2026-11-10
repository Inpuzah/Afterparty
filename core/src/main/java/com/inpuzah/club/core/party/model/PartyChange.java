package com.inpuzah.club.core.party.model;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Published only after a committed membership mutation, never for a speculative UI update. */
public record PartyChange(UUID actor, PartyOutcome outcome, Optional<Party> before, Optional<Party> after) {
    public PartyChange {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(before);
        Objects.requireNonNull(after);
    }
}
