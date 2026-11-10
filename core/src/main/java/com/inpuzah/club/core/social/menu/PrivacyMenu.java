package com.inpuzah.club.core.social.menu;

import com.inpuzah.club.core.social.model.SocialPreferences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

final class PrivacyMenu {
    static final int SIZE = 27;
    static final int REQUESTS = 13;
    static final int PARTY_INVITES = 15;
    static final int BACK = 18;
    static final int CLOSE = 22;
    enum State { LOADING, READY, SAVING, UNAVAILABLE }

    private PrivacyMenu() {}

    static void render(Inventory inventory, SocialPreferences confirmed, State state) {
        inventory.clear();
        inventory.setItem(4, item(Material.BOOK, "Social Privacy",
                "Choose who can send requests and invitations."));
        String choice = confirmed == null ? "Unknown" : choice(confirmed.friendRequestsEnabled());
        inventory.setItem(REQUESTS, item(state == State.READY ? Material.NAME_TAG : Material.GRAY_DYE,
                "Friend requests: " + status(confirmed, state),
                "Last confirmed: " + choice,
                state == State.UNAVAILABLE ? "Click to retry loading."
                        : state == State.READY ? "Click to switch Everyone / Nobody." : "Wait for confirmation.",
                "Existing friendships are not removed.",
                "Blocks are always enforced."));
        String partyChoice = confirmed == null ? "Unknown" : choice(confirmed.partyInvitesEnabled());
        inventory.setItem(PARTY_INVITES, item(state == State.READY ? Material.PAPER : Material.GRAY_DYE,
                "Party invites: " + partyStatus(confirmed, state),
                "Last confirmed: " + partyChoice,
                state == State.UNAVAILABLE ? "Click to retry loading."
                        : state == State.READY ? "Click to switch Everyone / Nobody." : "Wait for confirmation.",
                "Existing party membership is not changed.",
                "Blocks are always enforced.",
                "Party availability depends on server configuration."));
        inventory.setItem(BACK, item(Material.ARROW, "Back to settings"));
        inventory.setItem(CLOSE, item(Material.BARRIER, "Close"));
    }

    static String status(SocialPreferences preferences, State state) {
        return status(preferences == null ? null : preferences.friendRequestsEnabled(), state);
    }

    static String partyStatus(SocialPreferences preferences, State state) {
        return status(preferences == null ? null : preferences.partyInvitesEnabled(), state);
    }

    private static String status(Boolean enabled, State state) {
        return switch (state) {
            case LOADING -> "Loading...";
            case SAVING -> "Saving...";
            case UNAVAILABLE -> "Unavailable";
            case READY -> enabled == null ? "Unavailable" : choice(enabled);
        };
    }

    private static String choice(boolean enabled) {
        return enabled ? "Everyone" : "Nobody";
    }

    private static ItemStack item(Material material, String name, String... lore) {
        var item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GOLD));
        meta.lore(List.of(lore).stream().map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta);
        return item;
    }
}
