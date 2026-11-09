package com.inpuzah.club.core.display;

public final class AnimationFrames {
    private AnimationFrames() {}

    public static int index(long tick, int interval, int count) {
        if (tick < 0 || interval < 1 || count < 1) {
            throw new IllegalArgumentException("Animation requires nonnegative time, positive interval and frames");
        }
        return (int) ((tick / interval) % count);
    }
}
