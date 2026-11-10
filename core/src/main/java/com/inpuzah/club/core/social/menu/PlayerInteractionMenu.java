package com.inpuzah.club.core.social.menu;

import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.social.model.Relationship;
import com.inpuzah.club.core.social.model.SocialAction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Arrays;

final class PlayerInteractionMenu {
    static final int SIZE = 27;
    static final int FRIEND = 11;
    static final int SUMMARY = 13;
    static final int BLOCK = 15;
    static final int CLOSE = 22;
    enum State { LOADING, READY, SAVING, UNAVAILABLE }

    private PlayerInteractionMenu() {}

    static SocialAction friendAction(Relationship relationship) {
        return switch (relationship) {
            case NONE -> SocialAction.REQUEST;
            case FRIENDS -> SocialAction.REMOVE;
            case INCOMING_REQUEST -> SocialAction.ACCEPT;
            case OUTGOING_REQUEST -> SocialAction.CANCEL;
            case BLOCKED -> null;
        };
    }

    static String friendLabel(Relationship relationship) {
        return switch (relationship) {
            case NONE -> "Send friend request";
            case FRIENDS -> "Remove friend";
            case INCOMING_REQUEST -> "Accept friend request";
            case OUTGOING_REQUEST -> "Cancel outgoing request";
            case BLOCKED -> "Unblock before requesting";
        };
    }

    static void render(Inventory inventory, PlayerProfile target, Relationship relationship, State state) {
        inventory.clear();
        var head = item(Material.PLAYER_HEAD, target.username(), "Online on this server",
                "Level: " + target.level(), "UUID: " + target.uuid());
        var skull = (SkullMeta) head.getItemMeta();
        skull.setPlayerProfile(Bukkit.createProfile(target.uuid(), target.username()));
        head.setItemMeta(skull);
        inventory.setItem(4, head);
        inventory.setItem(SUMMARY, item(Material.BOOK, "Profile preview", "Level: " + target.level(),
                "XP: " + target.experience(), "Club Coins: " + target.coins(),
                "Relationship: " + (relationship == null ? "Unknown" : relationship.name())));
        String status = switch (state) {
            case LOADING -> "Loading...";
            case SAVING -> "Saving...";
            case UNAVAILABLE -> "Unavailable - click to retry";
            case READY -> "";
        };
        inventory.setItem(FRIEND, item(Material.NAME_TAG, state == State.READY && relationship != null
                ? friendLabel(relationship) : status, "Actions are revalidated before saving."));
        inventory.setItem(BLOCK, item(Material.BARRIER, state == State.READY && relationship != null
                ? relationship == Relationship.BLOCKED ? "Unblock player" : "Block player" : status,
                "Blocking removes friendship and pending requests."));
        inventory.setItem(CLOSE, item(Material.RED_STAINED_GLASS_PANE, "Close"));
    }

    private static ItemStack item(Material material, String name, String... lore) {
        var item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GOLD));
        meta.lore(Arrays.stream(lore).map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta);
        return item;
    }
}
