package com.inpuzah.club.core.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of a player's profile. The UUID is the identity; the username is display data only.
 *
 * <p>Level, experience and coins are read-only placeholders until their authoritative services exist.
 */
public record PlayerProfile(
        UUID uuid,
        String username,
        Instant firstJoinedAt,
        Instant lastSeenAt,
        int level,
        long experience,
        long coins
) {

    public static final int MAX_USERNAME_LENGTH = 16;
    public static final int STARTING_LEVEL = 1;

    public PlayerProfile {
        Objects.requireNonNull(uuid, "uuid");
        validateUsername(username);
        Objects.requireNonNull(firstJoinedAt, "firstJoinedAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        if (level < STARTING_LEVEL) {
            throw new IllegalArgumentException("level must be >= " + STARTING_LEVEL);
        }
        if (experience < 0) {
            throw new IllegalArgumentException("experience must be >= 0");
        }
        if (coins < 0) {
            throw new IllegalArgumentException("coins must be >= 0");
        }
    }

    public static PlayerProfile newProfile(UUID uuid, String username, Instant now) {
        return new PlayerProfile(uuid, username, now, now, STARTING_LEVEL, 0, 0);
    }

    public PlayerProfile withUsername(String newUsername) {
        return new PlayerProfile(uuid, newUsername, firstJoinedAt, lastSeenAt, level, experience, coins);
    }

    public PlayerProfile withLastSeenAt(Instant newLastSeenAt) {
        return new PlayerProfile(uuid, username, firstJoinedAt, newLastSeenAt, level, experience, coins);
    }

    /**
     * Accepts any name Paper can hand us: 1-16 characters with no whitespace or control characters.
     * Stricter Mojang rules ([A-Za-z0-9_], 3-16) are not enforced so legacy and offline names still load.
     */
    public static void validateUsername(String username) {
        Objects.requireNonNull(username, "username");
        int length = username.codePointCount(0, username.length());
        if (length < 1 || length > MAX_USERNAME_LENGTH) {
            throw new IllegalArgumentException("username must be 1-" + MAX_USERNAME_LENGTH + " characters");
        }
        if (username.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IllegalArgumentException("username must not contain whitespace or control characters");
        }
    }
}
