package com.inpuzah.club.core.party.menu;

import com.inpuzah.club.core.party.model.Party;
import com.inpuzah.club.core.party.model.PartyOutcome;
import com.inpuzah.club.core.party.service.PartyService;
import com.inpuzah.club.core.session.ProfileSessionManager;
import com.inpuzah.club.core.session.SessionState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.bukkit.inventory.Inventory;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/** Server-thread presentation only; storage runs through the bounded party service. */
public final class PartyMenuController implements Listener, AutoCloseable {
    @FunctionalInterface
    interface Renderer {
        void render(Inventory inventory, Party party, PartyMenu.State state, int page,
                    boolean confirmDisband, UUID viewer, Set<UUID> online);
    }

    private enum Action { CREATE, LEAVE, DISBAND }
    private final Server server;
    private final ProfileSessionManager profiles;
    private final PartyService service;
    private final Executor main;
    private final long timeoutMillis;
    private final BooleanSupplier enabled;
    private final Logger logger;
    private final Renderer renderer;
    private final Map<UUID, PartyMenuHolder> menus = new HashMap<>();
    private final Map<UUID, CompletableFuture<PartyOutcome>> pending = new HashMap<>();
    private boolean closed;
    private java.util.function.Consumer<Player> incomingMenu = player -> player.sendMessage(
            Component.text("View incoming invitations: /party invites").clickEvent(ClickEvent.runCommand("/party invites")));
    private java.util.function.Consumer<Player> candidateMenu = player -> message(player, "Invite a ready online player: /party invite <online-player>");
    private boolean chatEnabled;

    public void invitationMenus(java.util.function.Consumer<Player> incoming, java.util.function.Consumer<Player> candidates,
                                boolean chatEnabled) {
        this.incomingMenu = java.util.Objects.requireNonNull(incoming);
        this.candidateMenu = java.util.Objects.requireNonNull(candidates);
        this.chatEnabled = chatEnabled;
    }
    private java.util.function.Predicate<UUID> admitted = id -> true;

    public PartyMenuController(Server server, ProfileSessionManager profiles, PartyService service, Executor main,
                               long timeoutMillis, BooleanSupplier enabled, Logger logger) {
        this(server, profiles, service, main, timeoutMillis, enabled, logger, PartyMenu::render);
    }

    PartyMenuController(Server server, ProfileSessionManager profiles, PartyService service, Executor main,
                        long timeoutMillis, BooleanSupplier enabled, Logger logger, Renderer renderer) {
        this.server = server;
        this.profiles = profiles;
        this.service = service;
        this.main = main;
        this.timeoutMillis = timeoutMillis;
        this.enabled = enabled;
        this.logger = logger;
        this.renderer = renderer;
    }

    public void open(Player player) {
        if (closed) { return; }
        if (!eligible(player)) {
            message(player, "Party menus require an enabled subsystem, permission and a ready profile.");
            return;
        }

        UUID id = player.getUniqueId();
        var holder = new PartyMenuHolder(id, profiles.get(id).orElseThrow().token());
        var inventory = server.createInventory(holder, PartyMenu.SIZE, Component.text("Party"));
        holder.bind(inventory);
        render(player, holder);
        player.openInventory(inventory);
        if (player.getOpenInventory().getTopInventory() == inventory) {
            menus.put(id, holder);
            load(player, holder);
        }
    }

    public void admission(java.util.function.Predicate<UUID> admitted) {
        this.admitted = java.util.Objects.requireNonNull(admitted);
    }

    private void load(Player player, PartyMenuHolder holder) {
        holder.state = PartyMenu.State.LOADING;
        holder.confirmDisband = false;
        long revision = ++holder.revision;
        render(player, holder);
        var write = pending.get(holder.playerId);
        var read = write == null ? service.getParty(holder.playerId)
                : write.handle((value, error) -> null).thenCompose(ignored -> service.getParty(holder.playerId));
        read.copy().orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).whenCompleteAsync((value, error) -> {
            if (!current(player, holder) || holder.revision != revision) { return; }
            if (!eligible(player)) { player.closeInventory(); return; }
            if (error == null) {
                holder.confirmed = value.orElse(null);
                holder.page = Math.min(holder.page, PartyMenu.pages(holder.confirmed) - 1);
                holder.state = PartyMenu.State.READY;
            } else {
                holder.state = PartyMenu.State.UNAVAILABLE;
                logger.warn("Could not load party menu for {}", holder.playerId, error);
                message(player, "Party unavailable. Click the overview to retry.");
            }
            render(player, holder);
        }, main);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof PartyMenuHolder holder)) { return; }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !current(player, holder)
                || event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) { return; }
        int slot = event.getRawSlot();
        if (!actionSlot(slot)) { return; }
        if (slot != PartyMenu.CLOSE && (holder.actionQueued || pending.containsKey(holder.playerId)
                || holder.state == PartyMenu.State.LOADING || holder.state == PartyMenu.State.SAVING)) {
            message(player, "Party menu is loading or saving. Wait for confirmation.");
            return;
        }
        holder.actionQueued = true;
        long revision = holder.revision;
        boolean right = event.getClick() == ClickType.RIGHT;
        main.execute(() -> act(player, holder, slot, right, revision));
    }

    private void act(Player player, PartyMenuHolder holder, int slot, boolean right, long revision) {
        if (!current(player, holder)) { return; }
        holder.actionQueued = false;
        if (!eligible(player) || slot == PartyMenu.CLOSE) { player.closeInventory(); return; }
        if (holder.revision != revision || pending.containsKey(holder.playerId)) { return; }
        if (slot == PartyMenu.REFRESH || holder.state == PartyMenu.State.UNAVAILABLE) { load(player, holder); return; }
        if (holder.state != PartyMenu.State.READY) { return; }
        if (slot == PartyMenu.PREVIOUS || slot == PartyMenu.NEXT) {
            holder.page = Math.clamp(holder.page + (slot == PartyMenu.NEXT ? 1 : -1), 0,
                    PartyMenu.pages(holder.confirmed) - 1);
            holder.confirmDisband = false;
            render(player, holder);
        } else if (slot == PartyMenu.MEMBERSHIP) {
            if (holder.confirmed != null && holder.confirmed.leaderId().equals(holder.playerId) && right) {
                holder.confirmDisband = true;
                render(player, holder);
                return;
            }
            Action action = holder.confirmed == null ? Action.CREATE
                    : holder.confirmDisband ? Action.DISBAND : Action.LEAVE;
            mutate(player, holder, action);
        } else if (slot >= PartyMenu.FIRST_MEMBER && slot < PartyMenu.FIRST_MEMBER + PartyMenu.MEMBERS_PER_PAGE) {
            memberCommands(player, holder, slot);
        } else if (slot == PartyMenu.INVITES) {
            incomingMenu.accept(player);
        } else if (slot == PartyMenu.INVITE) {
            candidateMenu.accept(player);
        } else if (slot == PartyMenu.CHAT) {
            if (chatEnabled) {
                player.sendMessage(Component.text("Send a private party message: /pc <message>", NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.suggestCommand("/pc ")));
            } else { message(player, "Private party chat is disabled."); }
        }
    }

    private void mutate(Player player, PartyMenuHolder holder, Action action) {
        Party before = holder.confirmed;
        holder.state = PartyMenu.State.SAVING;
        holder.confirmDisband = false;
        long revision = ++holder.revision;
        render(player, holder);
        // Re-read before any write, then return to the Paper thread for session and view validation.
        var result = new CompletableFuture<PartyOutcome>();
        boolean[] submitted = {false};
        pending.put(holder.playerId, result);
        service.getParty(holder.playerId).copy().orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .whenCompleteAsync((latest, error) -> {
                    if (error != null) { result.completeExceptionally(error); return; }
                    if (!current(player, holder) || !eligible(player) || holder.revision != revision) {
                        result.completeExceptionally(new IllegalStateException("Party menu session changed before mutation"));
                        return;
                    }
                    if (!sameVersion(before, latest)) {
                        result.completeExceptionally(new IllegalStateException("Party changed before mutation; no write submitted"));
                        return;
                    }
                    submitted[0] = true;
                    var write = switch (action) {
                        case CREATE -> service.create(holder.playerId);
                        case LEAVE -> service.leaveIfCurrent(holder.playerId, online(), before.id(), before.revision());
                        case DISBAND -> service.disbandIfCurrent(holder.playerId, before.id(), before.revision());
                    };
                    write.whenComplete((value, failure) -> {
                        if (failure == null) { result.complete(value); }
                        else { result.completeExceptionally(failure); }
                    });
                }, main);
        result.whenCompleteAsync((value, error) -> {
            pending.remove(holder.playerId, result);
            if (closed || !current(player, holder)) { return; }
            if (!eligible(player)) { player.closeInventory(); return; }
            if (error != null) {
                logger.warn("Party menu action failed for {}; reloading state", holder.playerId, error);
                message(player, submitted[0] ? "Party action not confirmed; it may have completed. Reloading saved state."
                        : "Party action not submitted; the view changed or could not be verified. Reloading saved state.");
            } else if (value.code() == PartyOutcome.Code.CREATED || value.code() == PartyOutcome.Code.LEFT
                    || value.code() == PartyOutcome.Code.DISBANDED) {
                message(player, "Party action confirmed: " + value.code().name().toLowerCase(java.util.Locale.ROOT) + ".");
            } else {
                message(player, "Party action was not applied. Reloading saved state.");
            }
            load(player, holder);
        }, main);
        result.copy().orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).whenCompleteAsync((value, error) -> {
            if (error != null && current(player, holder) && pending.get(holder.playerId) == result) {
                message(player, "Party action is not confirmed yet and may still complete. Please wait.");
            }
        }, main);
    }

    private void memberCommands(Player player, PartyMenuHolder holder, int slot) {
        if (holder.confirmed == null) { return; }
        int index = holder.page * PartyMenu.MEMBERS_PER_PAGE + slot - PartyMenu.FIRST_MEMBER;
        if (index >= holder.confirmed.members().size()) { return; }
        var member = holder.confirmed.members().get(index);
        message(player, member.username() + " (" + member.playerId() + ")");
        if (holder.confirmed.leaderId().equals(holder.playerId) && !holder.playerId.equals(member.playerId())) {
            player.sendMessage(Component.text("[Kick]", NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.suggestCommand("/party kick " + member.playerId()))
                    .append(Component.text(" [Promote]", NamedTextColor.YELLOW)
                            .clickEvent(ClickEvent.suggestCommand("/party promote " + member.playerId()))));
        }
    }

    private static boolean sameVersion(Party before, Optional<Party> latest) {
        return before == null ? latest.isEmpty()
                : latest.filter(value -> value.id().equals(before.id()) && value.revision() == before.revision()).isPresent();
    }

    private static boolean actionSlot(int slot) {
        return slot == PartyMenu.PREVIOUS || slot == PartyMenu.NEXT || slot == PartyMenu.REFRESH
                || slot == PartyMenu.INVITES || slot == PartyMenu.INVITE || slot == PartyMenu.CHAT
                || slot == PartyMenu.MEMBERSHIP || slot == PartyMenu.CLOSE
                || slot >= PartyMenu.FIRST_MEMBER && slot < PartyMenu.FIRST_MEMBER + PartyMenu.MEMBERS_PER_PAGE;
    }

    public void refresh(UUID uuid) {
        var holder = menus.get(uuid);
        var player = server.getPlayer(uuid);
        if (holder != null && player != null && current(player, holder)) {
            if (!eligible(player)) { player.closeInventory(); }
            else { render(player, holder); }
        }
    }

    private void render(Player player, PartyMenuHolder holder) {
        renderer.render(holder.getInventory(), holder.confirmed, holder.state, holder.page,
                holder.confirmDisband, holder.playerId, online());
    }

    private Set<UUID> online() {
        return server.getOnlinePlayers().stream().map(Player::getUniqueId)
                .filter(id -> profiles.get(id).filter(value -> value.state() == SessionState.READY).isPresent())
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean eligible(Player player) {
        return !closed && enabled.getAsBoolean() && player.hasPermission("club.party.use")
                && admitted.test(player.getUniqueId())
                && profiles.get(player.getUniqueId()).filter(value -> value.state() == SessionState.READY).isPresent();
    }

    private boolean current(Player player, PartyMenuHolder holder) {
        return !closed && menus.get(holder.playerId) == holder && holder.playerId.equals(player.getUniqueId())
                && server.getPlayer(holder.playerId) == player
                && profiles.get(holder.playerId).filter(value -> value.token() == holder.loginToken).isPresent()
                && player.getOpenInventory().getTopInventory() == holder.getInventory();
    }

    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof PartyMenuHolder) { event.setCancelled(true); }
    }
    @EventHandler public void onOpen(InventoryOpenEvent event) {
        if (event.getInventory().getHolder(false) instanceof PartyMenuHolder holder
                && (!(event.getPlayer() instanceof Player player) || !eligible(player)
                || !holder.playerId.equals(player.getUniqueId()) || server.getPlayer(holder.playerId) != player
                || event.getInventory() != holder.getInventory()
                || profiles.get(holder.playerId).filter(value -> value.token() == holder.loginToken).isEmpty())) {
            event.setCancelled(true);
        }
    }
    @EventHandler public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof PartyMenuHolder holder) { menus.remove(holder.playerId, holder); }
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { menus.remove(event.getPlayer().getUniqueId()); }

    private static void message(Player player, String text) { player.sendMessage(Component.text(text, NamedTextColor.YELLOW)); }

    @Override public void close() {
        closed = true;
        for (var entry : Map.copyOf(menus).entrySet()) {
            var player = server.getPlayer(entry.getKey());
            if (player != null && player.getOpenInventory().getTopInventory() == entry.getValue().getInventory()) {
                player.closeInventory();
            }
        }
        menus.clear();
        pending.clear();
    }
}
