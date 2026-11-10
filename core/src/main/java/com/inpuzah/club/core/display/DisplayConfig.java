package com.inpuzah.club.core.display;

import com.inpuzah.club.core.config.ConfigException;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Locale;

public record DisplayConfig(
        boolean enabled, String server, boolean bossbarEnabled, int bossbarTicks, int rotationTicks,
        BossBar.Color color, BossBar.Overlay overlay, float progress, List<DisplayText> messages,
        boolean scoreboardEnabled, int titleTicks, int statsTicks, List<DisplayText> titles, List<DisplayText> lines
) {
    public DisplayConfig {
        messages = List.copyOf(messages);
        titles = List.copyOf(titles);
        lines = List.copyOf(lines);
    }

    public static DisplayConfig load(ConfigurationSection root) {
        for (String section : List.of("displays", "displays.bossbar", "displays.scoreboard")) {
            if (!(root.get(section) instanceof ConfigurationSection)) {
                throw new ConfigException(section + " must be a configuration section");
            }
        }
        String server = string(root, "server");
        if (!server.matches("[a-zA-Z0-9_-]{1,32}")) {
            throw new ConfigException("displays.server must be a 1-32 character server identifier");
        }
        double progress = number(root, "bossbar.progress");
        if (!Double.isFinite(progress) || progress < 0 || progress > 1) {
            throw new ConfigException("displays.bossbar.progress must be between 0 and 1");
        }
        try {
            return new DisplayConfig(bool(root, "enabled"), server,
                    bool(root, "bossbar.enabled"), ticks(root, "bossbar.update-ticks"), ticks(root, "bossbar.rotation-ticks"),
                    BossBar.Color.valueOf(string(root, "bossbar.color").toUpperCase(Locale.ROOT)),
                    BossBar.Overlay.valueOf(string(root, "bossbar.overlay").toUpperCase(Locale.ROOT)),
                    (float) progress, texts(root, "bossbar.messages", 1, 32, 256),
                    bool(root, "scoreboard.enabled"), ticks(root, "scoreboard.title-animation-ticks"),
                    ticks(root, "scoreboard.stats-refresh-ticks"), texts(root, "scoreboard.title-frames", 1, 32, 64),
                    texts(root, "scoreboard.lines", 0, 15, 128));
        } catch (IllegalArgumentException e) {
            throw new ConfigException("Invalid display configuration: " + e.getMessage());
        }
    }

    private static Object value(ConfigurationSection root, String path) {
        Object value = root.get("displays." + path);
        if (value == null) {
            throw new ConfigException("Missing displays." + path);
        }
        return value;
    }

    private static boolean bool(ConfigurationSection root, String path) {
        if (value(root, path) instanceof Boolean b) {
            return b;
        }
        throw new ConfigException("displays." + path + " must be true or false");
    }

    private static int ticks(ConfigurationSection root, String path) {
        if (value(root, path) instanceof Integer i && i >= 5 && i <= 72000) {
            return i;
        }
        throw new ConfigException("displays." + path + " must be 5-72000 ticks");
    }

    private static String string(ConfigurationSection root, String path) {
        if (value(root, path) instanceof String s) {
            return s;
        }
        throw new ConfigException("displays." + path + " must be a string");
    }

    private static double number(ConfigurationSection root, String path) {
        if (value(root, path) instanceof Number n) {
            return n.doubleValue();
        }
        throw new ConfigException("displays." + path + " must be a number");
    }

    private static List<DisplayText> texts(ConfigurationSection root, String path, int min, int max, int limit) {
        if (!(value(root, path) instanceof List<?> list) || list.size() < min || list.size() > max
                || list.stream().anyMatch(item -> !(item instanceof String))) {
            throw new ConfigException("displays." + path + " requires " + min + "-" + max + " text entries");
        }
        return list.stream().map(item -> new DisplayText((String) item, limit)).toList();
    }
}
