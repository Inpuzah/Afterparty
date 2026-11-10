package com.inpuzah.club.core.settings;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class SettingsMenuHolder implements InventoryHolder {
    private final UUID playerId;
    private final long sessionToken;
    private Inventory inventory;
    boolean actionPending;

    public SettingsMenuHolder(UUID playerId, long sessionToken) {
        this.playerId = playerId;
        this.sessionToken = sessionToken;
    }

    public UUID playerId() { return playerId; }
    public long sessionToken() { return sessionToken; }

    void bind(Inventory inventory) {
        if (this.inventory != null) {
            throw new IllegalStateException("Settings menu already bound");
        }
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
