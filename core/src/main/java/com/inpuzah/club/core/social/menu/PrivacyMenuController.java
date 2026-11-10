package com.inpuzah.club.core.social.menu;

import com.inpuzah.club.core.session.ProfileSessionManager;
import com.inpuzah.club.core.session.SessionState;
import com.inpuzah.club.core.social.model.SocialPreferences;
import com.inpuzah.club.core.social.service.SocialService;
import net.kyori.adventure.text.Component;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Inventory and controller state are confined to the server thread. */
public final class PrivacyMenuController implements Listener, AutoCloseable {
    @FunctionalInterface
    interface Renderer {
        void render(Inventory inventory, SocialPreferences preferences, PrivacyMenu.State state);
    }

    private final Server server;
    private final ProfileSessionManager profiles;
    private final SocialService service;
    private final Executor main;
    private final long timeoutMillis;
    private final Logger logger;
    private final Renderer renderer;
    private final Map<UUID, PrivacyMenuHolder> menus = new HashMap<>();
    // Keep accepted writes across menu close/rejoin until completion; new reads must follow them.
    private final Map<UUID, CompletableFuture<SocialPreferences>> pending = new HashMap<>();
    private boolean closed;
    private Consumer<Player> back = Player::closeInventory;

    public PrivacyMenuController(Server server, ProfileSessionManager profiles, SocialService service,
                                 Executor main, long timeoutMillis, Logger logger) {
        this(server, profiles, service, main, timeoutMillis, logger, PrivacyMenu::render);
    }

    PrivacyMenuController(Server server, ProfileSessionManager profiles, SocialService service,
                          Executor main, long timeoutMillis, Logger logger, Renderer renderer) {
        this.server = server;
        this.profiles = profiles;
        this.service = service;
        this.main = main;
        this.timeoutMillis = timeoutMillis;
        this.logger = logger;
        this.renderer = renderer;
    }

    public void backToSettings(Consumer<Player> back) {
        this.back = java.util.Objects.requireNonNull(back);
    }

    public void open(Player player) {
        if (closed) {
            return;
        }
        if (!permitted(player) || !ready(player.getUniqueId())) {
            message(player, "Privacy settings require permission and a ready player profile.");
            return;
        }
        UUID uuid = player.getUniqueId();
        var holder = new PrivacyMenuHolder(uuid, profiles.get(uuid).orElseThrow().token());
        Inventory inventory = server.createInventory(holder, PrivacyMenu.SIZE, Component.text("Social Privacy"));
        holder.bind(inventory);
        renderer.render(inventory, null, PrivacyMenu.State.LOADING);
        player.openInventory(inventory);
        if (player.getOpenInventory().getTopInventory() == inventory) {
            menus.put(uuid, holder);
            load(player, holder);
        }
    }

    private void load(Player player, PrivacyMenuHolder holder) {
        holder.state = PrivacyMenu.State.LOADING;
        long revision = ++holder.revision;
        render(player, holder);
        var write = pending.get(holder.playerId);
        CompletableFuture<SocialPreferences> read = write == null ? service.preferences(holder.playerId)
                : write.handle((value, error) -> null).thenCompose(ignored -> service.preferences(holder.playerId));
        read.copy().orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).whenCompleteAsync((value, error) -> {
            if (!current(player, holder) || holder.revision != revision) {
                return;
            }
            if (error == null) {
                holder.confirmed = value;
                holder.state = PrivacyMenu.State.READY;
            } else {
                holder.state = PrivacyMenu.State.UNAVAILABLE;
                logger.warn("Could not load social privacy settings for {}", holder.playerId, error);
                message(player, "Privacy settings are unavailable. Click the privacy item to retry.");
            }
            render(player, holder);
        }, main);
    }

    public void refresh(UUID uuid) {
        var holder = menus.get(uuid);
        var player = server.getPlayer(uuid);
        if (holder != null && player != null && current(player, holder)) {
            render(player, holder);
        }
    }

    private void render(Player player, PrivacyMenuHolder holder) {
        if (!permitted(player)) {
            player.closeInventory();
            return;
        }
        renderer.render(holder.getInventory(), holder.confirmed,
                ready(holder.playerId) ? holder.state : PrivacyMenu.State.UNAVAILABLE);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof PrivacyMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !current(player, holder)
                || (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot != PrivacyMenu.REQUESTS && slot != PrivacyMenu.BACK && slot != PrivacyMenu.CLOSE) {
            return;
        }
        if (holder.actionQueued && slot == PrivacyMenu.REQUESTS) {
            return;
        }
        holder.actionQueued = true;
        main.execute(() -> act(player, holder, slot));
    }

    private void act(Player player, PrivacyMenuHolder holder, int slot) {
        if (!current(player, holder)) {
            return;
        }
        holder.actionQueued = false;
        if (!permitted(player) || slot == PrivacyMenu.CLOSE) {
            player.closeInventory();
        } else if (slot == PrivacyMenu.BACK) {
            player.closeInventory();
            back.accept(player);
        } else if (!ready(holder.playerId)) {
            message(player, "Your profile is loading or unavailable.");
            render(player, holder);
        } else if (pending.containsKey(holder.playerId) || holder.state == PrivacyMenu.State.LOADING
                || holder.state == PrivacyMenu.State.SAVING) {
            message(player, "Privacy settings are loading or saving. Wait for confirmation.");
        } else if (holder.state == PrivacyMenu.State.UNAVAILABLE) {
            load(player, holder);
        } else {
            save(player, holder);
        }
    }

    private void save(Player player, PrivacyMenuHolder holder) {
        holder.state = PrivacyMenu.State.SAVING;
        ++holder.revision;
        render(player, holder);
        var result = service.setPreferences(holder.playerId, !holder.confirmed.friendRequestsEnabled());
        pending.put(holder.playerId, result);
        result.whenCompleteAsync((value, error) -> {
            if (closed) {
                return;
            }
            pending.remove(holder.playerId, result);
            if (!current(player, holder)) {
                return;
            }
            if (error == null) {
                holder.confirmed = value;
                holder.state = PrivacyMenu.State.READY;
                render(player, holder);
            } else {
                logger.warn("Privacy save outcome uncertain for {}; reloading confirmed policy", holder.playerId, error);
                message(player, "Could not confirm privacy save. Reloading the saved policy.");
                load(player, holder);
            }
        }, main);
        result.copy().orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).whenCompleteAsync((value, error) -> {
            if (error != null && current(player, holder) && pending.get(holder.playerId) == result) {
                message(player, "Privacy save is not confirmed yet. It may still complete; please wait.");
            }
        }, main);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof PrivacyMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent event) {
        if (event.getInventory().getHolder(false) instanceof PrivacyMenuHolder holder
                && (closed || !holder.playerId.equals(event.getPlayer().getUniqueId())
                || !event.getPlayer().hasPermission("club.settings") || !event.getPlayer().hasPermission("club.social.use")
                || event.getInventory() != holder.getInventory()
                || profiles.get(holder.playerId).filter(session -> session.token() == holder.loginToken).isEmpty())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof PrivacyMenuHolder holder) {
            menus.remove(holder.playerId, holder);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        menus.remove(event.getPlayer().getUniqueId());
    }

    private boolean current(Player player, PrivacyMenuHolder holder) {
        return !closed && menus.get(holder.playerId) == holder && holder.playerId.equals(player.getUniqueId())
                && server.getPlayer(holder.playerId) == player
                && profiles.get(holder.playerId).filter(session -> session.token() == holder.loginToken).isPresent()
                && player.getOpenInventory().getTopInventory() == holder.getInventory();
    }

    private boolean ready(UUID uuid) {
        return profiles.get(uuid).filter(session -> session.state() == SessionState.READY).isPresent();
    }

    private static boolean permitted(Player player) {
        return player.hasPermission("club.settings") && player.hasPermission("club.social.use");
    }

    private static void message(Player player, String text) {
        player.sendMessage(Component.text(text, NamedTextColor.YELLOW));
    }

    @Override
    public void close() {
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
