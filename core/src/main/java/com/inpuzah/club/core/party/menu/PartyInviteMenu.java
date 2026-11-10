package com.inpuzah.club.core.party.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;

final class PartyInviteMenu {
    static final int PAGE_SIZE = 18, BACK = 18, PREVIOUS = 19, REFRESH = 20, NEXT = 25, CLOSE = 26;
    private PartyInviteMenu() {}

    static void render(Inventory inventory, PartyInviteMenuHolder holder) {
        inventory.clear();
        inventory.setItem(22, item(Material.BOOK, (holder.incoming ? "Incoming invitations" : "Invite player")
                + ": " + PartyMenu.label(holder.state), "Page " + (holder.page + 1),
                "Actions use the exact displayed identity.", "Last confirmed entries are retained while saving."));
        for (int i = 0; i < holder.entries.size(); i++) {
            var entry = holder.entries.get(i);
            inventory.setItem(i, item(Material.PAPER, entry.label(), entry.id().toString(), entry.detail(),
                    holder.incoming ? "Left: accept. Right: deny." : "Click to invite this online session."));
        }
        inventory.setItem(BACK, item(Material.ARROW, "Back to party"));
        inventory.setItem(REFRESH, item(Material.CLOCK, "Refresh / Retry"));
        if (holder.page > 0) { inventory.setItem(PREVIOUS, item(Material.ARROW, "Previous page")); }
        if (holder.next) { inventory.setItem(NEXT, item(Material.ARROW, "Next page")); }
        inventory.setItem(CLOSE, item(Material.BARRIER, "Close"));
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
