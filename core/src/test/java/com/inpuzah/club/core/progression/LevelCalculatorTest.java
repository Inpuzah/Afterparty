package com.inpuzah.club.core.progression;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LevelCalculatorTest {

    @ParameterizedTest
    @CsvSource({"0,1,100", "99,1,1", "100,2,200", "299,2,1", "300,3,300",
            "600,4,400", "494999,99,1", "495000,100,0", "495001,100,0",
            "9223372036854775807,100,0"})
    void levelsAndRemainingXp(long xp, int level, long remaining) {
        assertEquals(level, LevelCalculator.levelFor(xp));
        assertEquals(remaining, LevelCalculator.remainingToNextLevel(xp));
        ProgressionSnapshot snapshot = new ProgressionSnapshot(UUID.randomUUID(), xp);
        assertEquals(level, snapshot.level());
        assertEquals(remaining, snapshot.remainingToNextLevel());
    }

    @Test
    void everySupportedLevelBoundaryIsExact() {
        assertEquals(0, LevelCalculator.thresholdFor(1));
        for (int level = 2; level <= LevelCalculator.MAX_LEVEL; level++) {
            long threshold = LevelCalculator.thresholdFor(level);
            assertEquals(100L * level * (level - 1) / 2, threshold);
            assertEquals(level - 1, LevelCalculator.levelFor(threshold - 1));
            assertEquals(level, LevelCalculator.levelFor(threshold));
        }
    }

    @Test
    void rejectsNegativeXpAndUnsupportedLevelsWithoutOverflow() {
        assertThrows(IllegalArgumentException.class, () -> LevelCalculator.levelFor(-1));
        assertThrows(IllegalArgumentException.class, () -> LevelCalculator.remainingToNextLevel(-1));
        assertThrows(IllegalArgumentException.class, () -> LevelCalculator.thresholdFor(0));
        assertThrows(IllegalArgumentException.class, () -> LevelCalculator.thresholdFor(101));
        assertThrows(IllegalArgumentException.class, () -> LevelCalculator.thresholdFor(Integer.MAX_VALUE));
    }

    @Test
    void validatesRequestAndResultInvariants() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new XpAwardRequest(id, id, 0, "test", "reason", null));
        assertThrows(IllegalArgumentException.class, () -> new XpAwardRequest(id, id, -1, "test", "reason", null));
        assertThrows(IllegalArgumentException.class, () -> new XpAwardRequest(id, id, 1, " ", "reason", null));
        assertThrows(IllegalArgumentException.class, () -> new XpAwardRequest(id, id, 1, "a".repeat(81), "reason", null));
        assertThrows(IllegalArgumentException.class, () -> new XpAwardRequest(id, id, 1, "test", "a".repeat(256), null));
        assertThrows(IllegalArgumentException.class, () -> new XpAwardRequest(id, id, 1, "test", "bad\nreason", null));
        assertThrows(NullPointerException.class, () -> new XpAwardRequest(null, id, 1, "test", "reason", null));
        XpAwardRequest request = new XpAwardRequest(id, id, 100, "test", "reason", null);
        assertThrows(IllegalArgumentException.class, () -> new XpAwardResult(request, 0, 99, false));
        assertThrows(IllegalArgumentException.class, () -> new XpAwardResult(request, -1, 100, false));
        XpAwardResult result = new XpAwardResult(request, 99, 199, false);
        assertEquals(1, result.previousLevel());
        assertEquals(2, result.level());
        assertEquals(new ProgressionSnapshot(id, 199), result.snapshot());
    }
}
