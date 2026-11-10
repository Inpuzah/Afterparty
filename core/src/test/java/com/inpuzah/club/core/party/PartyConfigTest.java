package com.inpuzah.club.core.party;

import com.inpuzah.club.core.config.ConfigException;
import com.inpuzah.club.core.config.DatabaseConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PartyConfigTest {
    private final DatabaseConfig database = database("127.0.0.1");

    @Test
    void bundledPluginDeclaresPartyAndChatCommandsAndSafePermissionDefaults() throws Exception {
        try (var stream = getClass().getResourceAsStream("/plugin.yml")) {
            assertNotNull(stream);
            var yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertEquals("club.party.use", yaml.getString("commands.party.permission"));
            assertEquals("/party help", yaml.getString("commands.party.usage"));
            assertTrue(yaml.getBoolean("permissions.club.party.use.default"));
            assertEquals("club.party.chat", yaml.getString("commands.pc.permission"));
            assertTrue(yaml.getBoolean("permissions.club.party.chat.default"));
            assertFalse(yaml.getBoolean("permissions.club.party.muted.default"));
        }
    }

    @Test
    void bundledConfigurationIsValidAndLeavesPrivacyAvailableWithoutRuntimeParties() {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            var yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            var config = PartyConfig.load(yaml, database);
            assertFalse(config.enabled());
            assertEquals(PartyPolicy.DEFAULT, config.policy());
            assertEquals(Duration.ofSeconds(300), config.disconnectGrace());
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    @Test
    void defaultsKeepUnfinishedSubsystemOffAndUseConfirmedPolicy() {
        var config = PartyConfig.load(new YamlConfiguration(), database);
        assertFalse(config.enabled());
        assertEquals("lobby-1", config.ownerId());
        assertEquals(database.host(), config.directHost());
        assertEquals(database.port(), config.directPort());
        assertEquals(PartyPolicy.DEFAULT, config.policy());
        assertEquals(Duration.ofSeconds(300), config.disconnectGrace());
        assertTrue(config.menuEnabled());
        assertTrue(config.chatEnabled());
        assertThrows(IllegalStateException.class, () -> config.leaseDataSource(database));
    }

    @Test
    void explicitValuesAndPhysicalDatasourceDoNotExposePasswordInConfiguration() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
                party:
                  enabled: true
                  owner-id: stable_lobby-2
                  direct-host: db.example.test
                  direct-port: 5433
                  max-members: 32
                  invite-expiry-seconds: 3600
                  disconnect-grace-seconds: 1
                  invites-per-minute: 100
                  max-pending-invites: 100
                  same-target-cooldown-seconds: 86400
                  menu-enabled: false
                  chat-enabled: false
                """);
        var config = PartyConfig.load(yaml, database);
        assertTrue(config.enabled());
        assertEquals("stable_lobby-2", config.ownerId());
        assertEquals(32, config.policy().maxMembers());
        assertEquals(Duration.ofHours(1), config.policy().inviteTtl());
        assertEquals(Duration.ofSeconds(1), config.disconnectGrace());
        assertEquals(100, config.policy().invitesPerMinute());
        assertEquals(100, config.policy().maxPendingInvites());
        assertEquals(Duration.ofDays(1), config.policy().sameTargetCooldown());
        assertFalse(config.menuEnabled());
        assertFalse(config.chatEnabled());
        var source = config.leaseDataSource(database);
        assertArrayEquals(new String[]{"db.example.test"}, source.getServerNames());
        assertArrayEquals(new int[]{5433}, source.getPortNumbers());
        assertEquals(database.name(), source.getDatabaseName());
        assertEquals(database.username(), source.getUser());
        assertEquals(database.password(), source.getPassword());
        assertEquals(database.sslMode(), source.getSslMode());
        assertEquals(5, source.getConnectTimeout());
        assertEquals(10, source.getSocketTimeout());
        assertEquals("ClubCore-Party-Epoch", source.getApplicationName());
        assertFalse(config.toString().contains(database.password()));
    }

    @Test
    void enabledPartiesRequireDirectHostWithoutBreakingDisabledPooledInstallations() {
        var yaml = new YamlConfiguration();
        var pooledDatabase = database("ep-example-pooler.region.neon.tech");
        assertFalse(PartyConfig.load(yaml, pooledDatabase).enabled());
        yaml.set("party.enabled", true);
        assertThrows(ConfigException.class, () -> PartyConfig.load(yaml, pooledDatabase));
        yaml.set("party.direct-host", "ep-example.region.neon.tech");
        assertEquals("ep-example.region.neon.tech", PartyConfig.load(yaml, pooledDatabase).directHost());
        yaml.set("party.direct-host", "EP-EXAMPLE-POOLER.region.neon.tech");
        assertThrows(ConfigException.class, () -> PartyConfig.load(yaml, database));
        yaml.set("party.direct-host", "bad host");
        assertThrows(ConfigException.class, () -> PartyConfig.load(yaml, database));
    }

    @Test
    void nonSectionWrongTypesAndInvalidOwnerFailExplicitly() {
        var yaml = new YamlConfiguration();
        yaml.set("party", false);
        assertThrows(ConfigException.class, () -> PartyConfig.load(yaml, database));
        yaml.set("party", null);
        for (String key : new String[]{"enabled", "menu-enabled", "chat-enabled"}) {
            yaml.set("party." + key, "true");
            assertThrows(ConfigException.class, () -> PartyConfig.load(yaml, database));
            yaml.set("party." + key, null);
        }
        for (String key : new String[]{"owner-id", "direct-host"}) {
            yaml.set("party." + key, 123);
            assertThrows(ConfigException.class, () -> PartyConfig.load(yaml, database));
            yaml.set("party." + key, null);
        }
        for (String owner : new String[]{"", "bad owner", "a".repeat(65)}) {
            yaml.set("party.owner-id", owner);
            assertThrows(ConfigException.class, () -> PartyConfig.load(yaml, database));
        }
    }

    @Test
    void integerTypesAndAllRangeBoundariesAreEnforced() {
        var yaml = new YamlConfiguration();
        for (var entry : Map.of("max-members", new int[]{1, 33}, "invite-expiry-seconds", new int[]{0, 3601},
                "disconnect-grace-seconds", new int[]{0, 3601}, "invites-per-minute", new int[]{0, 101},
                "max-pending-invites", new int[]{0, 101}, "same-target-cooldown-seconds", new int[]{0, 86401},
                "direct-port", new int[]{0, 65536}).entrySet()) {
            yaml.set("party." + entry.getKey(), "5");
            assertThrows(ConfigException.class, () -> PartyConfig.load(yaml, database));
            yaml.set("party." + entry.getKey(), 1.5);
            assertThrows(ConfigException.class, () -> PartyConfig.load(yaml, database));
            for (int value : entry.getValue()) {
                yaml.set("party." + entry.getKey(), value);
                assertThrows(ConfigException.class, () -> PartyConfig.load(yaml, database), entry.getKey());
            }
            yaml.set("party." + entry.getKey(), null);
        }
    }

    private static DatabaseConfig database(String host) {
        return new DatabaseConfig(host, 5432, "club_dev", "club_app", "test-only-password", "verify-full", 4, 5000);
    }
}
