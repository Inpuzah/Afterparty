package com.inpuzah.club.core.display;

import com.inpuzah.club.core.config.ConfigException;
import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.session.ProfileSession;
import com.inpuzah.club.core.session.SessionState;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayConfigTest {
    static YamlConfiguration defaults() throws Exception {
        var yaml = new YamlConfiguration();
        try (var reader = new InputStreamReader(DisplayConfigTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            yaml.load(reader);
        }
        return yaml;
    }

    @Test
    void bundledConfigSupportsFramesAndIndependentFlags() throws Exception {
        var yaml = defaults();
        var config = DisplayConfig.load(yaml);
        assertTrue(config.enabled());
        assertEquals(3, config.messages().size());
        assertEquals(7, config.lines().size());
        yaml.set("displays.bossbar.enabled", false);
        assertTrue(DisplayConfig.load(yaml).scoreboardEnabled());
    }

    @Test
    void rejectsInvalidConfigBeforeItCanBeApplied() throws Exception {
        Object[][] cases = {
                {"bossbar.update-ticks", 0}, {"scoreboard.stats-refresh-ticks", 4},
                {"scoreboard.title-animation-ticks", "10"}, {"bossbar.rotation-ticks", 72001},
                {"bossbar.progress", -0.1}, {"bossbar.progress", 1.1}, {"bossbar.progress", Double.NaN},
                {"bossbar.color", "ORANGE"}, {"bossbar.overlay", "SOMETIMES"}, {"enabled", "yes"},
                {"server", "bad<server>"}, {"bossbar.messages", List.of()},
                {"bossbar", true}, {"scoreboard", "broken"}, {"server", 12},
                {"scoreboard.title-frames", List.of()}, {"scoreboard.lines", java.util.Collections.nCopies(16, "line")},
                {"scoreboard.lines", List.of(1)}, {"scoreboard.title-frames", List.of("x".repeat(65))},
                {"bossbar.messages", List.of("<red>unclosed")},
                {"scoreboard.lines", List.of("<click:run_command:'/op x'>bad</click>")},
                {"scoreboard.lines", List.of("{unknown}")}, {"scoreboard.lines", List.of("bad\nline")}
        };
        for (Object[] item : cases) {
            var yaml = defaults();
            yaml.set("displays." + item[0], item[1]);
            assertThrows(ConfigException.class, () -> DisplayConfig.load(yaml), item[0].toString());
        }
    }

    @Test
    void placeholdersUseOneViewerSnapshotAndLocaleIndependentFormatting() {
        UUID id = UUID.randomUUID();
        var profile = new PlayerProfile(id, "Player", Instant.EPOCH, Instant.EPOCH, 7, 2100, 2450);
        var session = new ProfileSession(id, 1, SessionState.READY, profile);
        Map<String, String> values = PlaceholderResolver.values(session, "Player", 1234, "lobby-1");
        assertEquals("2,450", values.get("coins"));
        assertEquals("2,100", values.get("xp"));
        assertEquals("7", values.get("level"));
        assertEquals("1,234", values.get("online"));
        var text = new DisplayText("<gold>{username}: {coins}</gold>", 128).render(values);
        assertEquals("Player: 2,450", PlainTextComponentSerializer.plainText().serialize(text));
        assertEquals("Loading...", PlaceholderResolver.values(null, "Player", 1, "lobby").get("coins"));
        assertEquals("Unavailable", PlaceholderResolver.values(
                new ProfileSession(id, 1, SessionState.UNAVAILABLE, null), "Player", 1, "lobby").get("coins"));
    }

    @Test
    void placeholdersAreLiteralNotMarkupAndTextSizesAreBounded() {
        var text = new DisplayText("{username}", 128);
        assertThrows(IllegalArgumentException.class, () -> text.render(Map.of("username", "<red>")));
        assertThrows(ConfigException.class, () -> new DisplayText("{coins", 128));
        assertThrows(ConfigException.class, () -> new DisplayText("x".repeat(1025), 128));
        assertThrows(ConfigException.class, () -> new DisplayText("<selector:@a>", 128));
        assertThrows(ConfigException.class, () -> new DisplayText("x".repeat(129), 128));
    }

    @Test
    void duplicateAndBlankLinesRemainDistinctConfigRows() throws Exception {
        var yaml = defaults();
        var lines = new ArrayList<>(List.of("", " ", "same", "same"));
        yaml.set("displays.scoreboard.lines", lines);
        assertEquals(4, DisplayConfig.load(yaml).lines().size());
    }

    @Test
    void animationWrapsExactlyAtBoundaries() {
        assertEquals(0, AnimationFrames.index(0, 10, 3));
        assertEquals(0, AnimationFrames.index(9, 10, 3));
        assertEquals(1, AnimationFrames.index(10, 10, 3));
        assertEquals(2, AnimationFrames.index(29, 10, 3));
        assertEquals(0, AnimationFrames.index(30, 10, 3));
        assertEquals(0, AnimationFrames.index(Long.MAX_VALUE, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> AnimationFrames.index(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> AnimationFrames.index(1, 10, 0));
    }
}
