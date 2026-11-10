package com.inpuzah.club.core.social.menu;

import com.inpuzah.club.core.command.CommandFeedback;
import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.session.ProfileSessionManager;
import com.inpuzah.club.core.session.SessionState;
import com.inpuzah.club.core.social.command.SocialCommand;
import com.inpuzah.club.core.social.model.Relationship;
import com.inpuzah.club.core.social.model.SocialAction;
import com.inpuzah.club.core.social.model.SocialResult;
import com.inpuzah.club.core.social.service.SocialService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Bukkit operations and menu ownership are confined to the server thread. */
public final class InteractionMenuController implements Listener, AutoCloseable {
    @FunctionalInterface interface Renderer {
        void render(Inventory inventory, PlayerProfile profile, Relationship relationship, PlayerInteractionMenu.State state);
    }
    private final Server server;
    private final ProfileSessionManager profiles;
    private final SocialService service;
    private final Executor main;
    private final long timeoutMillis;
    private final Logger logger;
    private final boolean enabled;
    private final Renderer renderer;
    private final Predicate<Inventory> ordinaryInventory;
    private final CommandFeedback feedback;
    private final Map<UUID, InteractionMenuHolder> menus = new HashMap<>();
    private final Set<UUID> opening = new HashSet<>();
    private final Map<UUID, CompletableFuture<SocialResult>> pending = new HashMap<>();
    private boolean closed;

    public InteractionMenuController(Server server, ProfileSessionManager profiles, SocialService service,
                                     Executor main, long timeoutMillis, Logger logger, boolean enabled) {
        this(server, profiles, service, main, timeoutMillis, logger, enabled, PlayerInteractionMenu::render,
                inventory -> inventory.getType() == InventoryType.CRAFTING || inventory.getType() == InventoryType.CREATIVE);
    }

    InteractionMenuController(Server server, ProfileSessionManager profiles, SocialService service,
                              Executor main, long timeoutMillis, Logger logger, boolean enabled, Renderer renderer,
                              Predicate<Inventory> ordinaryInventory) {
        this.server = server;
        this.profiles = profiles;
        this.service = service;
        this.main = main;
        this.timeoutMillis = timeoutMillis;
        this.logger = logger;
        this.enabled = enabled;
        this.renderer = renderer;
        this.ordinaryInventory = ordinaryInventory;
        this.feedback = new CommandFeedback(server, profiles, main);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.isCancelled() || closed || !enabled || event.getHand() != EquipmentSlot.HAND
                || !(event.getRightClicked() instanceof Player target)) {
            return;
        }
        Player viewer = event.getPlayer();
        if (!viewer.isSneaking() || viewer.getUniqueId().equals(target.getUniqueId())
                || !viewer.hasPermission("club.social.use") || !ready(viewer.getUniqueId()) || !ready(target.getUniqueId())
                || !ordinaryInventory.test(viewer.getOpenInventory().getTopInventory())) {
            return;
        }
        if (!opening.add(viewer.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        var original = viewer.getOpenInventory().getTopInventory();
        long viewerToken = profiles.get(viewer.getUniqueId()).orElseThrow().token();
        long targetToken = profiles.get(target.getUniqueId()).orElseThrow().token();
        main.execute(() -> {
            opening.remove(viewer.getUniqueId());
            if (!closed && server.getPlayer(viewer.getUniqueId()) == viewer
                    && server.getPlayer(target.getUniqueId()) == target && viewer.hasPermission("club.social.use")
                    && viewer.isSneaking() && viewer.getOpenInventory().getTopInventory() == original
                    && login(viewer.getUniqueId(), viewerToken) && login(target.getUniqueId(), targetToken)
                    && ready(viewer.getUniqueId()) && ready(target.getUniqueId())) {
                open(viewer, target, viewerToken, targetToken);
            }
        });
    }

    private void open(Player viewer, Player target, long viewerToken, long targetToken) {
        var holder = new InteractionMenuHolder(viewer.getUniqueId(), target.getUniqueId(), viewerToken, targetToken);
        var inventory = server.createInventory(holder, PlayerInteractionMenu.SIZE, Component.text("Afterparty \u2022 Player"));
        holder.bind(inventory);
        renderer.render(inventory, profiles.get(holder.target).orElseThrow().profile(), null, holder.state);
        viewer.openInventory(inventory);
        if (viewer.getOpenInventory().getTopInventory() == inventory) {
            menus.put(holder.viewer, holder);
            load(viewer, holder);
        }
    }

    private void load(Player player, InteractionMenuHolder holder) {
        holder.state = PlayerInteractionMenu.State.LOADING;
        long revision = ++holder.revision;
        render(player, holder);
        var write = pending.get(holder.viewer);
        var read = write == null ? service.relationship(holder.viewer, holder.target)
                : write.handle((value, error) -> null).thenCompose(ignored -> service.relationship(holder.viewer, holder.target));
        read.copy().orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).whenCompleteAsync((relationship, error) -> {
            if (!current(player, holder) || holder.revision != revision) {
                return;
            }
            if (error == null) {
                holder.relationship = relationship;
                holder.state = PlayerInteractionMenu.State.READY;
            } else {
                holder.state = PlayerInteractionMenu.State.UNAVAILABLE;
                logger.warn("Could not load player relationship for {} and {}", holder.viewer, holder.target, error);
                message(player, "Social relationship unavailable. Click an action to retry.");
            }
            render(player, holder);
        }, main);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof InteractionMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !current(player, holder)
                || (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot != PlayerInteractionMenu.FRIEND && slot != PlayerInteractionMenu.BLOCK && slot != PlayerInteractionMenu.CLOSE) {
            return;
        }
        if (slot != PlayerInteractionMenu.CLOSE && (holder.state == PlayerInteractionMenu.State.LOADING
                || holder.state == PlayerInteractionMenu.State.SAVING || pending.containsKey(holder.viewer))) {
            message(player, "A social action is loading or saving. Please wait.");
            return;
        }
        if (holder.queued && slot != PlayerInteractionMenu.CLOSE) {
            return;
        }
        holder.queued = true;
        main.execute(() -> act(player, holder, slot));
    }

    private void act(Player player, InteractionMenuHolder holder, int slot) {
        if (!current(player, holder)) {
            return;
        }
        holder.queued = false;
        if (!player.hasPermission("club.social.use") || slot == PlayerInteractionMenu.CLOSE) {
            player.closeInventory();
        } else if (!available(holder)) {
            message(player, "Player profile is loading or unavailable.");
            render(player, holder);
        } else if (pending.containsKey(holder.viewer) || holder.state == PlayerInteractionMenu.State.LOADING
                || holder.state == PlayerInteractionMenu.State.SAVING) {
            message(player, "A social action is loading or saving. Please wait.");
        } else if (holder.state == PlayerInteractionMenu.State.UNAVAILABLE) {
            load(player, holder);
        } else {
            SocialAction action = slot == PlayerInteractionMenu.BLOCK
                    ? holder.relationship == Relationship.BLOCKED ? SocialAction.UNBLOCK : SocialAction.BLOCK
                    : PlayerInteractionMenu.friendAction(holder.relationship);
            if (action == null) {
                message(player, "Unblock this player before sending a request.");
                return;
            }
            save(player, holder, action);
        }
    }

    private void save(Player player, InteractionMenuHolder holder, SocialAction action) {
        holder.state = PlayerInteractionMenu.State.SAVING;
        ++holder.revision;
        render(player, holder);
        // Explicit actions, not toggles: the repository rechecks blocks, privacy, expiry and state under pair locks.
        var result = service.mutate(action, holder.viewer, holder.target);
        pending.put(holder.viewer, result);
        var target = server.getPlayer(holder.target);
        if (target != null) {
            String name = player.getName();
            feedback.complete(target, result, (value, error) -> {
                if (!closed && error == null && target.hasPermission("club.social.use")) {
                    if (value == SocialResult.REQUEST_SENT) {
                        message(target, name + " sent a friend request. Use /friend requests.");
                    } else if (value == SocialResult.ACCEPTED) {
                        message(target, name + " accepted your friend request.");
                    }
                }
            });
        }
        result.whenCompleteAsync((value, error) -> {
            if (closed) {
                return;
            }
            pending.remove(holder.viewer, result);
            if (!current(player, holder)) {
                return;
            }
            if (error == null) {
                message(player, SocialCommand.describe(value));
            } else {
                logger.warn("Interaction action outcome uncertain for {} and {}", holder.viewer, holder.target, error);
                message(player, "Could not confirm social action. Reloading current relationship.");
            }
            load(player, holder);
        }, main);
        result.copy().orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).whenCompleteAsync((value, error) -> {
            if (error != null && current(player, holder) && pending.get(holder.viewer) == result) {
                message(player, "Social action is not confirmed yet. It may still complete; please wait.");
            }
        }, main);
    }

    public void refresh(UUID uuid) {
        for (var holder : java.util.List.copyOf(menus.values())) {
            if (holder.viewer.equals(uuid) || holder.target.equals(uuid)) {
                Player player = server.getPlayer(holder.viewer);
                if (player != null && current(player, holder)) {
                    render(player, holder);
                }
            }
        }
    }

    private void render(Player player, InteractionMenuHolder holder) {
        if (!player.hasPermission("club.social.use") || !login(holder.target, holder.targetToken)
                || server.getPlayer(holder.target) == null) {
            player.closeInventory();
            return;
        }
        var target = profiles.get(holder.target).orElseThrow().profile();
        if (target == null) {
            player.closeInventory();
            return;
        }
        renderer.render(holder.getInventory(), target, holder.relationship,
                available(holder) ? holder.state : PlayerInteractionMenu.State.UNAVAILABLE);
    }

    private boolean available(InteractionMenuHolder holder) {
        return ready(holder.viewer) && ready(holder.target) && login(holder.target, holder.targetToken)
                && server.getPlayer(holder.target) != null;
    }

    private boolean current(Player player, InteractionMenuHolder holder) {
        return !closed && menus.get(holder.viewer) == holder && holder.viewer.equals(player.getUniqueId())
                && server.getPlayer(holder.viewer) == player && login(holder.viewer, holder.viewerToken)
                && player.getOpenInventory().getTopInventory() == holder.getInventory();
    }

    private boolean login(UUID uuid, long token) {
        return profiles.get(uuid).filter(session -> session.token() == token).isPresent();
    }

    private boolean ready(UUID uuid) {
        return profiles.get(uuid).filter(session -> session.state() == SessionState.READY).isPresent();
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof InteractionMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent event) {
        if (event.getInventory().getHolder(false) instanceof InteractionMenuHolder holder
                && (closed || !holder.viewer.equals(event.getPlayer().getUniqueId())
                || !event.getPlayer().hasPermission("club.social.use") || event.getInventory() != holder.getInventory()
                || !login(holder.viewer, holder.viewerToken) || !login(holder.target, holder.targetToken))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof InteractionMenuHolder holder) {
            menus.remove(holder.viewer, holder);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        opening.remove(uuid);
        for (var holder : java.util.List.copyOf(menus.values())) {
            if (holder.viewer.equals(uuid) || holder.target.equals(uuid)) {
                menus.remove(holder.viewer, holder);
                var viewer = server.getPlayer(holder.viewer);
                if (viewer != null && viewer.getOpenInventory().getTopInventory() == holder.getInventory()) {
                    viewer.closeInventory();
                }
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        for (var holder : java.util.List.copyOf(menus.values())) {
            var player = server.getPlayer(holder.viewer);
            if (player != null && player.getOpenInventory().getTopInventory() == holder.getInventory()) {
                player.closeInventory();
            }
        }
        menus.clear();
        opening.clear();
        pending.clear();
    }

    private static void message(Player player, String text) {
        player.sendMessage(Component.text(text, NamedTextColor.YELLOW));
    }
}
