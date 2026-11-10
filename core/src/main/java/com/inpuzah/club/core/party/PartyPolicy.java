package com.inpuzah.club.core.party;

import java.time.Duration;
import java.util.Objects;

public record PartyPolicy(int maxMembers, Duration inviteTtl, int invitesPerMinute,
                          int maxPendingInvites, Duration sameTargetCooldown) {
    public static final PartyPolicy DEFAULT = new PartyPolicy(8, Duration.ofSeconds(120), 5, 10, Duration.ofSeconds(30));

    public PartyPolicy {
        Objects.requireNonNull(inviteTtl, "inviteTtl");
        Objects.requireNonNull(sameTargetCooldown, "sameTargetCooldown");
        if (maxMembers < 2 || maxMembers > 32 || invitesPerMinute < 1 || invitesPerMinute > 100
                || maxPendingInvites < 1 || maxPendingInvites > 100
                || inviteTtl.compareTo(Duration.ofSeconds(1)) < 0 || inviteTtl.compareTo(Duration.ofHours(1)) > 0
                || sameTargetCooldown.compareTo(Duration.ofSeconds(1)) < 0
                || sameTargetCooldown.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Invalid party capacity, invite TTL or abuse limits");
        }
    }
}
