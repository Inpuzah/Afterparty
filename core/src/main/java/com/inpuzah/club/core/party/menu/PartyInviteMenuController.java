package com.inpuzah.club.core.party.menu;

import com.inpuzah.club.core.party.PartyInvitations;
import com.inpuzah.club.core.party.command.PartyCommand;
import com.inpuzah.club.core.party.model.PartyOutcome;
import com.inpuzah.club.core.party.service.PartyService;
import com.inpuzah.club.core.session.ProfileSessionManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Explicit invitation IDs and target login tokens, never a click-time name lookup. */
public final class PartyInviteMenuController implements Listener, AutoCloseable {
    @FunctionalInterface interface Renderer { void render(org.bukkit.inventory.Inventory inventory, PartyInviteMenuHolder holder); }
    private final Server server;
    private final ProfileSessionManager profiles;
    private final PartyService service;
    private final PartyInvitations invitations;
    private final Executor main;
    private final long timeoutMillis;
    private final BooleanSupplier enabled;
    private final Consumer<Player> back;
    private final Logger logger;
    private final Renderer renderer;
    private final Map<UUID, PartyInviteMenuHolder> menus = new HashMap<>();
    private final Map<UUID, CompletableFuture<PartyOutcome>> pending = new HashMap<>();
    private boolean closed;

    public PartyInviteMenuController(Server server, ProfileSessionManager profiles, PartyService service,
                                     PartyInvitations invitations, Executor main, long timeoutMillis,
                                     BooleanSupplier enabled, Consumer<Player> back, Logger logger) {
        this(server, profiles, service, invitations, main, timeoutMillis, enabled, back, logger, PartyInviteMenu::render);
    }
    PartyInviteMenuController(Server server, ProfileSessionManager profiles, PartyService service,
                              PartyInvitations invitations, Executor main, long timeoutMillis,
                              BooleanSupplier enabled, Consumer<Player> back, Logger logger, Renderer renderer) {
        this.server = server;
        this.profiles = profiles;
        this.service = service;
        this.invitations = invitations;
        this.main = main;
        this.timeoutMillis = timeoutMillis;
        this.enabled = enabled;
        this.back = back;
        this.logger = logger;
        this.renderer = renderer;
    }
    public void openIncoming(Player player) { open(player, true); }
    public void openCandidates(Player player) { open(player, false); }

    private void open(Player player, boolean incoming) {
        if (!eligible(player)) { message(player, "Party menus are unavailable or restricted."); return; }
        var holder = new PartyInviteMenuHolder(player.getUniqueId(),
                profiles.get(player.getUniqueId()).orElseThrow().token(), incoming);
        holder.inventory = server.createInventory(holder, 27, Component.text(incoming ? "Party invitations" : "Invite player"));
        renderer.render(holder.inventory, holder);
        player.openInventory(holder.inventory);
        if (player.getOpenInventory().getTopInventory() == holder.inventory) {
            menus.put(holder.viewer, holder);
            load(player, holder);
        }
    }

    private void load(Player player, PartyInviteMenuHolder holder) {
        holder.state = PartyMenu.State.LOADING;
        long revision = ++holder.revision;
        renderer.render(holder.inventory, holder);
        var write = pending.get(holder.viewer);
        var barrier = write == null ? CompletableFuture.completedFuture(null) : write.handle((value, error) -> null);
        if (holder.incoming) {
            barrier.thenCompose(ignored -> service.getInvites(holder.viewer, holder.page * PartyInviteMenu.PAGE_SIZE,
                            PartyInviteMenu.PAGE_SIZE + 1))
                    .copy().orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).whenCompleteAsync((rows, error) -> {
                        if (!validRead(player, holder, revision, error)) { return; }
                        holder.next = rows.size() > PartyInviteMenu.PAGE_SIZE;
                        holder.entries = rows.stream().limit(PartyInviteMenu.PAGE_SIZE)
                                .map(invite -> new PartyInviteMenuHolder.Entry(invite.id(),
                                        "From " + name(invite.inviterId()), 0, "Expires: " + invite.expiresAt())).toList();
                        publish(holder);
                    }, main);
        } else {
            barrier.thenCompose(ignored -> service.getParty(holder.viewer)).copy()
                    .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).whenCompleteAsync((party, error) -> {
                        if (!validRead(player, holder, revision, error)) { return; }
                        if (party.isPresent() && !party.orElseThrow().isLeader(holder.viewer)) {
                            holder.state = PartyMenu.State.UNAVAILABLE;
                            holder.entries = List.of();
                            message(player, "Only the party leader can invite players.");
                        } else {
                            var all = server.getOnlinePlayers().stream()
                                    .filter(target -> !target.getUniqueId().equals(holder.viewer) && invitations.ready(target))
                                    .filter(target -> party.isEmpty() || !party.orElseThrow().isMember(target.getUniqueId()))
                                    .sorted(Comparator.comparing(Player::getName).thenComparing(target -> target.getUniqueId().toString()))
                                    .toList();
                            int start = holder.page * PartyInviteMenu.PAGE_SIZE;
                            holder.next = all.size() > start + PartyInviteMenu.PAGE_SIZE;
                            holder.entries = all.stream().skip(start).limit(PartyInviteMenu.PAGE_SIZE).map(target ->
                                    new PartyInviteMenuHolder.Entry(target.getUniqueId(), target.getName(),
                                            profiles.get(target.getUniqueId()).orElseThrow().token(), "Ready on this server")).toList();
                            holder.state = PartyMenu.State.READY;
                        }
                        renderer.render(holder.inventory, holder);
                    }, main);
        }
    }

    private boolean validRead(Player player, PartyInviteMenuHolder holder, long revision, Throwable error) {
        if (!current(player, holder) || holder.revision != revision) { return false; }
        if (!eligible(player)) { player.closeInventory(); return false; }
        if (error != null) {
            holder.state = PartyMenu.State.UNAVAILABLE;
            logger.warn("Could not load party invitations/candidates for {}", holder.viewer, error);
            message(player, "Party list unavailable. Click refresh to retry.");
            renderer.render(holder.inventory, holder);
            return false;
        }
        return true;
    }
    private void publish(PartyInviteMenuHolder holder) {
        holder.state = PartyMenu.State.READY;
        renderer.render(holder.inventory, holder);
    }
    private String name(UUID id) {
        var player = server.getPlayer(id);
        return player == null ? id.toString() : player.getName();
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof PartyInviteMenuHolder holder)) { return; }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !current(player, holder)
                || event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) { return; }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 27 || slot > 17 && slot != PartyInviteMenu.BACK && slot != PartyInviteMenu.CLOSE
                && slot != PartyInviteMenu.PREVIOUS && slot != PartyInviteMenu.NEXT && slot != PartyInviteMenu.REFRESH) { return; }
        boolean navigation = slot == PartyInviteMenu.CLOSE || slot == PartyInviteMenu.BACK;
        if (!navigation && (holder.queued || pending.containsKey(holder.viewer)
                || holder.state == PartyMenu.State.LOADING || holder.state == PartyMenu.State.SAVING)) { return; }
        holder.queued = true;
        long revision = holder.revision;
        boolean deny = event.getClick() == ClickType.RIGHT;
        main.execute(() -> {
            if (!current(player, holder)) { return; }
            holder.queued = false;
            if (!eligible(player) || slot == PartyInviteMenu.CLOSE) { player.closeInventory(); return; }
            if (slot == PartyInviteMenu.BACK) { back.accept(player); return; }
            if (revision != holder.revision || pending.containsKey(holder.viewer)) { return; }
            if (slot == PartyInviteMenu.REFRESH || holder.state == PartyMenu.State.UNAVAILABLE) { load(player, holder); return; }
            if (holder.state != PartyMenu.State.READY) { return; }
            if (slot == PartyInviteMenu.PREVIOUS || slot == PartyInviteMenu.NEXT) {
                if (slot == PartyInviteMenu.NEXT && holder.next && holder.page < 1_000_000) { holder.page++; }
                if (slot == PartyInviteMenu.PREVIOUS && holder.page > 0) { holder.page--; }
                load(player, holder);
            } else if (slot < holder.entries.size()) { act(player, holder, holder.entries.get(slot), deny); }
        });
    }

    private void act(Player player, PartyInviteMenuHolder holder, PartyInviteMenuHolder.Entry entry, boolean deny) {
        CompletableFuture<PartyOutcome> result;
        if (holder.incoming) { result = deny ? service.deny(holder.viewer, entry.id()) : service.accept(holder.viewer, entry.id()); }
        else {
            var target = server.getPlayer(entry.id());
            if (target == null || !invitations.ready(target)
                    || profiles.get(entry.id()).filter(session -> session.token() == entry.token()).isEmpty()) {
                message(player, "Target session changed. Refresh before inviting.");
                load(player, holder);
                return;
            }
            result = invitations.invite(player, target);
        }
        holder.state = PartyMenu.State.SAVING;
        ++holder.revision;
        pending.put(holder.viewer, result);
        renderer.render(holder.inventory, holder);
        result.whenCompleteAsync((value, error) -> {
            pending.remove(holder.viewer, result);
            if (!current(player, holder)) { return; }
            if (!eligible(player)) { player.closeInventory(); return; }
            if (error != null) {
                logger.warn("Party invitation action uncertain for {}; reloading state", holder.viewer, error);
                message(player, "Action not confirmed; it may have completed. Reloading invitations.");
            } else { message(player, PartyCommand.describe(value.code())); }
            load(player, holder);
        }, main);
        result.copy().orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).whenCompleteAsync((value, error) -> {
            if (error != null && current(player, holder) && pending.get(holder.viewer) == result) {
                message(player, "Invitation action may still complete. Wait for confirmation.");
            }
        }, main);
    }

    public void refresh() {
        for (var holder : List.copyOf(menus.values())) {
            var player = server.getPlayer(holder.viewer);
            if (player != null && current(player, holder) && !eligible(player)) { player.closeInventory(); }
        }
    }
    private boolean eligible(Player player) {
        return !closed && enabled.getAsBoolean() && player.hasPermission("club.party.use") && invitations.ready(player);
    }
    private boolean current(Player player, PartyInviteMenuHolder holder) {
        return !closed && menus.get(holder.viewer) == holder && holder.viewer.equals(player.getUniqueId())
                && server.getPlayer(holder.viewer) == player
                && profiles.get(holder.viewer).filter(session -> session.token() == holder.token).isPresent()
                && player.getOpenInventory().getTopInventory() == holder.inventory;
    }
    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof PartyInviteMenuHolder) { event.setCancelled(true); }
    }
    @EventHandler public void onOpen(InventoryOpenEvent event) {
        if (event.getInventory().getHolder(false) instanceof PartyInviteMenuHolder holder
                && (!(event.getPlayer() instanceof Player player) || !eligible(player)
                || server.getPlayer(holder.viewer) != player || !holder.viewer.equals(player.getUniqueId()) || event.getInventory() != holder.inventory
                || profiles.get(holder.viewer).filter(session -> session.token() == holder.token).isEmpty())) {
            event.setCancelled(true);
        }
    }
    @EventHandler public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof PartyInviteMenuHolder holder) { menus.remove(holder.viewer, holder); }
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { menus.remove(event.getPlayer().getUniqueId()); }
    @Override public void close() {
        closed = true;
        for (var holder : List.copyOf(menus.values())) {
            var player = server.getPlayer(holder.viewer);
            if (player != null && player.getOpenInventory().getTopInventory() == holder.inventory) { player.closeInventory(); }
        }
        menus.clear();
        pending.clear();
    }
    private static void message(Player player, String text) { player.sendMessage(Component.text(text)); }
}
