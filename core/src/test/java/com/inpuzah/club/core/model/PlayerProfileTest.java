package com.inpuzah.club.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerProfileTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

    @Test
    void newProfileStartsAtLevelOneWithNoExperienceOrCoins() {
        PlayerProfile profile = PlayerProfile.newProfile(ID, "Inpuzah", NOW);

        assertEquals(1, profile.level());
        assertEquals(0, profile.experience());
        assertEquals(0, profile.coins());
        assertEquals(NOW, profile.firstJoinedAt());
        assertEquals(NOW, profile.lastSeenAt());
    }

    @Test
    void rejectsInvalidProgressionValues() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerProfile(ID, "a", NOW, NOW, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlayerProfile(ID, "a", NOW, NOW, 1, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlayerProfile(ID, "a", NOW, NOW, 1, 0, -1));
    }

    @Test
    void usernameChangeKeepsIdentityAndProgress() {
        PlayerProfile original = new PlayerProfile(ID, "OldName", NOW, NOW, 5, 120, 40);
        PlayerProfile renamed = original.withUsername("NewName");

        assertEquals(ID, renamed.uuid());
        assertEquals("NewName", renamed.username());
        assertEquals(original.firstJoinedAt(), renamed.firstJoinedAt());
        assertEquals(5, renamed.level());
        assertEquals(120, renamed.experience());
        assertEquals(40, renamed.coins());
    }

    @Test
    void usernameRules() {
        assertDoesNotThrow(() -> PlayerProfile.validateUsername("a"));
        assertDoesNotThrow(() -> PlayerProfile.validateUsername("Sixteen_Chars_12"));
        assertDoesNotThrow(() -> PlayerProfile.validateUsername("Ünïcödé"));
        assertThrows(IllegalArgumentException.class, () -> PlayerProfile.validateUsername(""));
        assertThrows(IllegalArgumentException.class, () -> PlayerProfile.validateUsername("Seventeen_Chars_1"));
        assertThrows(IllegalArgumentException.class, () -> PlayerProfile.validateUsername("has space"));
        assertThrows(IllegalArgumentException.class, () -> PlayerProfile.validateUsername("tab\t"));
        assertThrows(NullPointerException.class, () -> PlayerProfile.validateUsername(null));
    }
}
