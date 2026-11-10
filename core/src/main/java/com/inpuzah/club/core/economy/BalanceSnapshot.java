package com.inpuzah.club.core.economy;

import java.util.Objects;
import java.util.UUID;

/** Authoritative persisted balance. Reads allow legacy balances above the cap so they can be reduced. */
public record BalanceSnapshot(UUID playerId, long coins) {

    public static final long MAX_BALANCE = 1_000_000_000L;

    public BalanceSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        if (coins < 0) {
            throw new IllegalArgumentException("coins must be nonnegative");
        }
    }
}
