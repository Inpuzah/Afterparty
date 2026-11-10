package com.inpuzah.club.core.party.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;
import java.util.UUID;

final class PartyInviteMenuHolder implements InventoryHolder {
    record Entry(UUID id, String label, long token, String detail) {}
    final UUID viewer;
    final long token;
    final boolean incoming;
    Inventory inventory;
    List<Entry> entries = List.of();
    PartyMenu.State state = PartyMenu.State.LOADING;
    int page;
    long revision;
    boolean next;
    boolean queued;

    PartyInviteMenuHolder(UUID viewer, long token, boolean incoming) {
        this.viewer = viewer;
        this.token = token;
        this.incoming = incoming;
    }
    @Override public Inventory getInventory() { return inventory; }
}
