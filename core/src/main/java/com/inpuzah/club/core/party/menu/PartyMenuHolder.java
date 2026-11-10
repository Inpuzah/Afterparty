package com.inpuzah.club.core.party.menu;

import com.inpuzah.club.core.party.model.Party;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Objects;
import java.util.UUID;

final class PartyMenuHolder implements InventoryHolder {
    final UUID playerId;
    final long loginToken;
    private Inventory inventory;
    Party confirmed;
    PartyMenu.State state = PartyMenu.State.LOADING;
    int page;
    long revision;
    boolean actionQueued;
    boolean confirmDisband;

    PartyMenuHolder(UUID playerId, long loginToken) {
        this.playerId = playerId;
        this.loginToken = loginToken;
    }

    void bind(Inventory inventory) {
        if (this.inventory != null) {
            throw new IllegalStateException("Inventory already bound");
        }
        this.inventory = Objects.requireNonNull(inventory);
    }

    @Override public Inventory getInventory() {
        return Objects.requireNonNull(inventory, "Inventory not bound");
    }
}
