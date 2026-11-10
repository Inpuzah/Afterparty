package com.inpuzah.club.core.settings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public final class SettingsMenu {
    public static final int SIZE = 27;
    public static final int BOSSBAR = 11;
    public static final int SCOREBOARD = 13;
    public static final int INFO = 15;
    public static final int CLOSE = 22;

    private SettingsMenu() {}

    public static void render(Inventory inventory, PlayerSettings settings, PlayerSettingsService.State state) {
        inventory.clear();
        for (int i = 0; i < SIZE; i++) {
            if (i < 9 || i >= 18) {
                inventory.setItem(i, item(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY, List.of()));
            }
        }
        inventory.setItem(4, item(Material.NETHER_STAR, "Player Settings", NamedTextColor.GOLD,
                List.of("Your choices are saved automatically.")));
        inventory.setItem(BOSSBAR, toggle("Bossbar", settings != null && settings.bossbarVisible(), state));
        inventory.setItem(SCOREBOARD, toggle("Sidebar", settings != null && settings.scoreboardVisible(), state));
        inventory.setItem(INFO, item(Material.BOOK, "Social Privacy", NamedTextColor.AQUA,
                List.of("Display choices persist across reconnects and restarts.",
                        "Server-wide switches and game ownership still apply.",
                        "Click to choose who can send friend requests.")));
        inventory.setItem(CLOSE, item(Material.BARRIER, "Close", NamedTextColor.RED, List.of()));
    }

    public static String status(boolean enabled, PlayerSettingsService.State state) {
        return switch (state) {
            case READY -> enabled ? "Enabled" : "Disabled";
            case SAVING -> "Saving...";
            case LOADING -> "Loading...";
            case UNAVAILABLE, OFFLINE -> "Unavailable";
        };
    }

    private static ItemStack toggle(String name, boolean enabled, PlayerSettingsService.State state) {
        boolean ready = state == PlayerSettingsService.State.READY;
        return item(ready ? (enabled ? Material.LIME_DYE : Material.RED_DYE) : Material.GRAY_DYE,
                name + ": " + status(enabled, state), ready && enabled ? NamedTextColor.GREEN : NamedTextColor.YELLOW,
                List.of("Last confirmed: " + (enabled ? "Enabled" : "Disabled"),
                        ready ? "Click to toggle and save." : "Wait for confirmation; reopen /settings to retry."));
    }

    private static ItemStack item(Material material, String name, NamedTextColor color, List<String> lore) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(Component.text(name, color));
        meta.lore(lore.stream().map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta);
        return item;
    }
}
