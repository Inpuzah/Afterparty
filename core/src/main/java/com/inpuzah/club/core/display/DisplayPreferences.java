package com.inpuzah.club.core.display;

public record DisplayPreferences(boolean bossbar, boolean scoreboard) {
    public static final DisplayPreferences DEFAULT = new DisplayPreferences(true, true);
}
