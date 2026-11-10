package com.inpuzah.club.core.social;

import com.inpuzah.club.core.config.ConfigException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SocialConfigTest {
    @Test
    void existingConfigsInheritSafeDefaults() {
        assertEquals(SocialConfig.DEFAULT, SocialConfig.load(new YamlConfiguration()));
    }

    @Test
    void interactionsCanBeDisabledButWrongTypesFailExplicitly() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("social:\n  interactions-enabled: false\n");
        assertFalse(SocialConfig.load(yaml).interactionsEnabled());
        yaml.set("social.interactions-enabled", "false");
        assertThrows(ConfigException.class, () -> SocialConfig.load(yaml));
        yaml.set("social.interactions-enabled", 1);
        assertThrows(ConfigException.class, () -> SocialConfig.load(yaml));
    }

    @Test
    void readsExplicitLimits() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("social:\n  requests-per-minute: 3\n  incoming-limit: 10\n  retry-cooldown-seconds: 60\n");
        assertEquals(new SocialConfig(3, 10, 60), SocialConfig.load(yaml));
    }

    @Test
    void invalidTypesAndRangesRejectInsteadOfSilentlyDefaulting() {
        var yaml = new YamlConfiguration();
        yaml.set("social", false);
        assertThrows(ConfigException.class, () -> SocialConfig.load(yaml));
        yaml.set("social", null);
        yaml.set("social.requests-per-minute", "five");
        assertThrows(ConfigException.class, () -> SocialConfig.load(yaml));
        yaml.set("social.requests-per-minute", 0);
        assertThrows(ConfigException.class, () -> SocialConfig.load(yaml));
        assertThrows(ConfigException.class, () -> new SocialConfig(101, 25, 30));
        assertThrows(ConfigException.class, () -> new SocialConfig(5, 0, 30));
        assertThrows(ConfigException.class, () -> new SocialConfig(5, 25, -1));
    }
}
