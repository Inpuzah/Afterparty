package com.inpuzah.club.core.party.chat;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Synchronous moderation hook, before delivery. Cancel to enforce an external mute/filter. */
public final class PartyChatEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final UUID partyId;
    private final String message;
    private boolean cancelled;

    public PartyChatEvent(Player player, UUID partyId, String message) {
        this.player = java.util.Objects.requireNonNull(player);
        this.partyId = java.util.Objects.requireNonNull(partyId);
        this.message = java.util.Objects.requireNonNull(message);
    }
    public Player getPlayer() { return player; }
    public UUID getPartyId() { return partyId; }
    public String getMessage() { return message; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
