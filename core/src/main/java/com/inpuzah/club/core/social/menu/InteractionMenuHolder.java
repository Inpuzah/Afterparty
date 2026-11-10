package com.inpuzah.club.core.social.menu;

import com.inpuzah.club.core.social.model.Relationship;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

final class InteractionMenuHolder implements InventoryHolder {
    final UUID viewer;
    final UUID target;
    final long viewerToken;
    final long targetToken;
    Relationship relationship;
    PlayerInteractionMenu.State state = PlayerInteractionMenu.State.LOADING;
    boolean queued;
    long revision;
    private Inventory inventory;

    InteractionMenuHolder(UUID viewer, UUID target, long viewerToken, long targetToken) {
        this.viewer = viewer;
        this.target = target;
        this.viewerToken = viewerToken;
        this.targetToken = targetToken;
    }

    void bind(Inventory inventory) {
        if (this.inventory != null) {
            throw new IllegalStateException("Interaction menu already bound");
        }
        this.inventory = inventory;
    }

    @Override public Inventory getInventory() { return inventory; }
}
