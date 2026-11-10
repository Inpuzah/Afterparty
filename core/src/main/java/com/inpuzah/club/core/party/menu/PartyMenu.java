package com.inpuzah.club.core.party.menu;

import com.inpuzah.club.core.party.model.Party;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

final class PartyMenu {
    static final int SIZE = 27;
    static final int PREVIOUS = 0, NEXT = 8, FIRST_MEMBER = 10, MEMBERS_PER_PAGE = 7;
    static final int INVITES = 18, INVITE = 20, CHAT = 22, MEMBERSHIP = 24, CLOSE = 26, REFRESH = 4;
    enum State { LOADING, READY, SAVING, UNAVAILABLE }

    private PartyMenu() {}

    static int pages(Party party) {
        return party == null ? 1 : (party.members().size() + MEMBERS_PER_PAGE - 1) / MEMBERS_PER_PAGE;
    }

    static void render(Inventory inventory, Party party, State state, int page,
                       boolean confirmDisband, UUID viewer, Set<UUID> online) {
        inventory.clear();
        inventory.setItem(REFRESH, item(Material.BOOK, "Party: " + label(state),
                party == null ? "No confirmed party." : "Members: " + party.members().size() + "/" + party.maxMembers(),
                "Click to refresh. Last confirmed members shown.",
                "Parties end on restart. See /party help for disconnect grace."));
        if (party != null) {
            int start = page * MEMBERS_PER_PAGE;
            for (int index = start; index < Math.min(start + MEMBERS_PER_PAGE, party.members().size()); index++) {
                var member = party.members().get(index);
                inventory.setItem(FIRST_MEMBER + index - start, item(Material.PLAYER_HEAD, member.username(),
                        member.playerId().toString(),
                        party.leaderId().equals(member.playerId()) ? "Leader" : "Member",
                        online.contains(member.playerId()) ? "Online" : "Offline",
                        "Click for explicit member commands."));
            }
        }
        if (page > 0) { inventory.setItem(PREVIOUS, item(Material.ARROW, "Previous members")); }
        if (page + 1 < pages(party)) { inventory.setItem(NEXT, item(Material.ARROW, "Next members")); }
        inventory.setItem(INVITES, item(Material.PAPER, "Incoming invitations", "Click to view and accept or deny invitations."));
        inventory.setItem(INVITE, item(Material.NAME_TAG, "Invite player", "Click to browse ready online players."));
        inventory.setItem(CHAT, item(Material.OAK_SIGN, "Party communication", "Click for /pc usage (when enabled)."));
        inventory.setItem(MEMBERSHIP, item(state == State.READY ? Material.EMERALD : Material.GRAY_DYE,
                membershipLabel(party, viewer, confirmDisband),
                state == State.READY ? "Click to perform this explicit action." : "Wait for confirmation.",
                "Leader leave transfers leadership; disband removes everyone.",
                "Disband requires a separate right-click, then left-click confirmation."));
        inventory.setItem(CLOSE, item(Material.BARRIER, "Close"));
    }

    static String label(State state) {
        return switch (state) {
            case LOADING -> "Loading...";
            case READY -> "Ready";
            case SAVING -> "Saving...";
            case UNAVAILABLE -> "Unavailable";
        };
    }

    static String membershipLabel(Party party, UUID viewer, boolean confirmation) {
        if (party == null) { return "Create party"; }
        if (!party.leaderId().equals(viewer)) { return "Leave party"; }
        return confirmation ? "Confirm disband (left-click)" : "Leave (left) / Disband (right)";
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
