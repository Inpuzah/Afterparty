package com.inpuzah.club.core.display;

import java.util.UUID;

public interface DisplayPreferenceRepository {
    DisplayPreferences load(UUID uuid);
    DisplayPreferences save(UUID uuid, boolean bossbar, boolean enabled);
}
