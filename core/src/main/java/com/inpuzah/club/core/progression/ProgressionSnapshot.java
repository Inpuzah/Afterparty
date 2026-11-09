package com.inpuzah.club.core.progression;

import java.util.Objects;
import java.util.UUID;

public record ProgressionSnapshot(UUID playerId, long experience) {

    public ProgressionSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        if (experience < 0) {
            throw new IllegalArgumentException("experience must be nonnegative");
        }
    }

    public int level() {
        return LevelCalculator.levelFor(experience);
    }

    public long remainingToNextLevel() {
        return LevelCalculator.remainingToNextLevel(experience);
    }
}
