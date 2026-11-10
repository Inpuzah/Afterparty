package com.inpuzah.club.core.party.model;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable active-party snapshot, not an authorization cache. */
public record Party(UUID id, UUID instanceId, UUID leaderId, Instant createdAt, Instant updatedAt,
                    long revision, int maxMembers, List<PartyMember> members) {
    public static final int DEFAULT_MAX_MEMBERS = 8;
    public static final int MIN_MAX_MEMBERS = 2;
    public static final int MAX_MAX_MEMBERS = 32;

    public Party {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(leaderId, "leaderId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be nonnegative");
        }
        if (maxMembers < MIN_MAX_MEMBERS || maxMembers > MAX_MAX_MEMBERS) {
            throw new IllegalArgumentException("maxMembers must be 2-32");
        }
        members = List.copyOf(members);
        if (members.isEmpty() || members.size() > maxMembers) {
            throw new IllegalArgumentException("Active parties require 1-maxMembers members");
        }
        var players = new HashSet<UUID>();
        for (var member : members) {
            if (!players.add(member.playerId())) {
                throw new IllegalArgumentException("Party members must have distinct UUIDs");
            }
            if (member.joinedAt().isBefore(createdAt) || member.joinedAt().isAfter(updatedAt)) {
                throw new IllegalArgumentException("Membership must start within the party snapshot's lifetime");
            }
        }
        if (!players.contains(leaderId)) {
            throw new IllegalArgumentException("Leader must be an active member");
        }
        // Text UUID ordering matches PostgreSQL ordering, unlike Java's signed UUID.compareTo.
        members = members.stream().sorted(Comparator.comparing(PartyMember::joinedAt)
                .thenComparing(member -> member.playerId().toString())).toList();
    }

    public boolean isLeader(UUID playerId) {
        return leaderId.equals(Objects.requireNonNull(playerId, "playerId"));
    }

    public boolean isMember(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return members.stream().anyMatch(member -> member.playerId().equals(playerId));
    }

    public boolean isFull() {
        return members.size() == maxMembers;
    }
}
