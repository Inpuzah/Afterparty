package com.inpuzah.club.core.progression;

public final class LevelCalculator {

    public static final int MAX_LEVEL = 100;

    private LevelCalculator() {
    }

    public static long thresholdFor(int level) {
        if (level < 1 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("level must be between 1 and " + MAX_LEVEL);
        }
        return 50L * level * (level - 1);
    }

    public static int levelFor(long totalXp) {
        if (totalXp < 0) {
            throw new IllegalArgumentException("totalXp must be nonnegative");
        }
        int low = 1;
        int high = MAX_LEVEL;
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (thresholdFor(middle) <= totalXp) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    public static long remainingToNextLevel(long totalXp) {
        int level = levelFor(totalXp);
        return level == MAX_LEVEL ? 0 : thresholdFor(level + 1) - totalXp;
    }
}
