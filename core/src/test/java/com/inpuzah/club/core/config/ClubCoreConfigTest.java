package com.inpuzah.club.core.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClubCoreConfigTest {

    private static final Map<String, String> ENV = Map.of(ClubCoreConfig.PASSWORD_ENV, "s3cret-value");

    @Test
    void bundledDefaultsAreValid() throws Exception {
        ClubCoreConfig config = ClubCoreConfig.load(bundledConfig(), ENV::get);

        assertEquals("jdbc:postgresql://127.0.0.1:5432/club_dev?sslmode=prefer", config.database().jdbcUrl());
        assertEquals("club_app", config.database().username());
        assertEquals("s3cret-value", config.database().password());
        assertEquals(4, config.database().poolSize());
        assertEquals(5000, config.database().connectionTimeoutMs());
        assertEquals(Duration.ofSeconds(10), config.profile().loadTimeout());
        assertFalse(config.profile().showReadyMessage());
        assertEquals(new AdminCommandConfig(0, 0), config.adminCommands());
    }

    @Test
    void missingPasswordFromBothSourcesFails() throws Exception {
        ConfigException error = assertThrows(ConfigException.class,
                () -> ClubCoreConfig.load(bundledConfig(), name -> null));
        assertTrue(error.getMessage().contains(ClubCoreConfig.PASSWORD_ENV));
        assertTrue(error.getMessage().contains("database.password"));
    }

    @Test
    void configPasswordWorksWhenEnvironmentIsUnsetOrEmpty() throws Exception {
        YamlConfiguration yaml = bundledConfig();
        String password = "  local:#'password  ";
        yaml.set("database.password", password);

        assertEquals(password, ClubCoreConfig.load(yaml, name -> null).database().password());
        assertEquals(password, ClubCoreConfig.load(yaml, name -> "").database().password());
        assertFalse(ClubCoreConfig.load(yaml, name -> null).toString().contains(password));
    }

    @Test
    void environmentPasswordOverridesConfig() throws Exception {
        YamlConfiguration yaml = bundledConfig();
        yaml.set("database.password", "local-password");

        assertEquals("s3cret-value", ClubCoreConfig.load(yaml, ENV::get).database().password());
    }

    @Test
    void legacyConfigWithoutPasswordStillWorksWithEnvironment() throws Exception {
        YamlConfiguration yaml = bundledConfig();
        yaml.set("database.password", null);

        assertEquals("s3cret-value", ClubCoreConfig.load(yaml, ENV::get).database().password());
    }

    @Test
    void rejectsNonStringConfigPasswordWithoutEnvironment() throws Exception {
        YamlConfiguration yaml = bundledConfig();
        yaml.set("database.password", 12345);

        ConfigException error = assertThrows(ConfigException.class,
                () -> ClubCoreConfig.load(yaml, name -> null));
        assertEquals("database.password must be a string", error.getMessage());
    }

    @Test
    void toStringNeverContainsPassword() throws Exception {
        ClubCoreConfig config = ClubCoreConfig.load(bundledConfig(), ENV::get);

        assertFalse(config.database().toString().contains("s3cret-value"));
        assertFalse(config.toString().contains("s3cret-value"));
    }

    @Test
    void rejectsOutOfRangeValues() throws Exception {
        assertInvalid("database.port", 70000);
        assertInvalid("database.pool-size", 0);
        assertInvalid("database.pool-size", 100);
        assertInvalid("database.connection-timeout-ms", 10);
        assertInvalid("database.ssl-mode", "sometimes");
        assertInvalid("database.host", "evil/host?x=");
        assertInvalid("database.name", "club;drop");
        assertInvalid("profile.load-timeout-ms", 10);
    }

    @Test
    void rejectsWrongTypes() throws Exception {
        assertInvalid("database.port", "5432");
        assertInvalid("profile.show-ready-message", "yes please");
        assertInvalid("database.host", "");
        assertInvalid("admin-commands.max-xp", "unlimited");
        assertInvalid("admin-commands.max-coins", 1.5);
        assertInvalid("admin-commands.max-xp", -1);
    }

    @Test
    void supportsLongAdminLimitsAndLegacyConfigWithoutLimits() throws Exception {
        YamlConfiguration yaml = bundledConfig();
        yaml.set("admin-commands.max-xp", Long.MAX_VALUE);
        yaml.set("admin-commands.max-coins", 500L);
        assertEquals(new AdminCommandConfig(Long.MAX_VALUE, 500), ClubCoreConfig.load(yaml, ENV::get).adminCommands());
        yaml.set("admin-commands", null);
        assertEquals(new AdminCommandConfig(0, 0), ClubCoreConfig.load(yaml, ENV::get).adminCommands());
    }

    @Test
    void missingSectionFails() {
        assertThrows(ConfigException.class, () -> ClubCoreConfig.load(new YamlConfiguration(), ENV::get));
    }

    private static void assertInvalid(String path, Object value) throws Exception {
        YamlConfiguration yaml = bundledConfig();
        yaml.set(path, value);
        assertThrows(ConfigException.class, () -> ClubCoreConfig.load(yaml, ENV::get), path + "=" + value);
    }

    private static YamlConfiguration bundledConfig() throws Exception {
        try (Reader reader = new InputStreamReader(
                Objects.requireNonNull(ClubCoreConfigTest.class.getResourceAsStream("/config.yml")),
                StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }
}
