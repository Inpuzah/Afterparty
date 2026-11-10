package com.inpuzah.club.core.social.menu;

import com.inpuzah.club.core.social.model.SocialPreferences;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

final class PrivacyMenuHolder implements InventoryHolder {
    final UUID playerId;
    final long loginToken;
    SocialPreferences confirmed;
    PrivacyMenu.State state = PrivacyMenu.State.LOADING;
    boolean actionQueued;
    long revision;
    private Inventory inventory;

    PrivacyMenuHolder(UUID playerId, long loginToken) {
        this.playerId = playerId;
        this.loginToken = loginToken;
    }

    void bind(Inventory inventory) {
        if (this.inventory != null) {
            throw new IllegalStateException("Privacy inventory already bound");
        }
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
