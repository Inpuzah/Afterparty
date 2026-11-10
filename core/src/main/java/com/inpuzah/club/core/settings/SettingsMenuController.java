package com.inpuzah.club.core.settings;

import com.inpuzah.club.core.session.ProfileSessionManager;
import com.inpuzah.club.core.session.SessionState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** GUI state and all Bukkit interactions are confined to the primary server thread. */
public final class SettingsMenuController implements TabExecutor, Listener, AutoCloseable {
    private final Server server;
    private final ProfileSessionManager profiles;
    private final PlayerSettingsService settings;
    private final Executor mainThread;
    private final long timeoutMillis;
    private final Map<UUID, SettingsMenuHolder> menus = new HashMap<>();
    private boolean closed;
    private final Renderer renderer;
    private Consumer<Player> privacy;

    public void privacyMenu(Consumer<Player> privacy) {
        this.privacy = java.util.Objects.requireNonNull(privacy);
    }

    @FunctionalInterface
    interface Renderer {
        void render(Inventory inventory, PlayerSettings settings, PlayerSettingsService.State state);
    }

    public SettingsMenuController(Server server, ProfileSessionManager profiles, PlayerSettingsService settings,
                                  Executor mainThread, long timeoutMillis) {
        this(server, profiles, settings, mainThread, timeoutMillis, SettingsMenu::render);
    }

    SettingsMenuController(Server server, ProfileSessionManager profiles, PlayerSettingsService settings,
                           Executor mainThread, long timeoutMillis, Renderer renderer) {
        this.server = server;
        this.profiles = profiles;
        this.settings = settings;
        this.mainThread = mainThread;
        this.timeoutMillis = timeoutMillis;
        this.renderer = renderer;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("club.settings")) {
            message(sender, "You do not have permission to use this command.");
        } else if (!(sender instanceof Player player)) {
            message(sender, "This command can only be used by a player.");
        } else if (args.length != 0) {
            message(sender, "Usage: /settings");
        } else if (!ready(player.getUniqueId())) {
            message(sender, "Your profile is loading or unavailable. Try again shortly.");
        } else if (!closed) {
            var uuid = player.getUniqueId();
            var snapshot = settings.cached(uuid);
            if (snapshot.isEmpty() || settings.state(uuid) == PlayerSettingsService.State.UNAVAILABLE
                    || settings.state(uuid) == PlayerSettingsService.State.LOADING) {
                settings.get(uuid);
                message(sender, "Settings are loading or unavailable. Try /settings again shortly.");
                return true;
            }
            SettingsMenuHolder holder = new SettingsMenuHolder(uuid, profiles.get(uuid).orElseThrow().token());
            Inventory inventory = server.createInventory(holder, SettingsMenu.SIZE, Component.text("Player Settings"));
            holder.bind(inventory);
            // Opening closes the previous inventory first; publish this holder after the close callback.
            renderer.render(inventory, snapshot.get(), settings.state(uuid));
            player.openInventory(inventory);
            if (player.getOpenInventory().getTopInventory() == inventory) {
                menus.put(uuid, holder);
            }
        }
        return true;
    }

    public void refresh(UUID uuid) {
        SettingsMenuHolder holder = menus.get(uuid);
        Player player = server.getPlayer(uuid);
        if (holder == null || player == null || !current(player, holder)) {
            return;
        }
        if (!player.hasPermission("club.settings")) {
            menus.remove(uuid);
            player.closeInventory();
            return;
        }
        renderer.render(holder.getInventory(), settings.cached(uuid).orElse(null),
                ready(uuid) ? settings.state(uuid) : PlayerSettingsService.State.UNAVAILABLE);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof SettingsMenuHolder holder)) {
            return;
        }
        // Cancel all interactions while our menu is open, including bottom inventory and outside clicks.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !current(player, holder)) {
            return;
        }
        int slot = event.getRawSlot();
        // Do not turn hotbar swaps, shift-click, double-click or drop attempts into settings mutations.
        if (!event.isLeftClick() && !event.isRightClick() || event.isShiftClick()
                || event.getClick() != org.bukkit.event.inventory.ClickType.LEFT
                && event.getClick() != org.bukkit.event.inventory.ClickType.RIGHT) {
            return;
        }
        if (slot != SettingsMenu.BOSSBAR && slot != SettingsMenu.SCOREBOARD && slot != SettingsMenu.CLOSE
                && !(slot == SettingsMenu.INFO && privacy != null)) {
            return;
        }
        if (holder.actionPending && slot != SettingsMenu.CLOSE) {
            return;
        }
        holder.actionPending = true;
        // Defer inventory mutations out of the click callback, retaining exact inventory/session identity.
        mainThread.execute(() -> act(player, holder, slot));
    }

    private void act(Player player, SettingsMenuHolder holder, int slot) {
        if (!current(player, holder)) {
            holder.actionPending = false;
            return;
        }
        if (!player.hasPermission("club.settings") || slot == SettingsMenu.CLOSE) {
            player.closeInventory();
            return;
        }
        UUID uuid = player.getUniqueId();
        if (slot == SettingsMenu.INFO && privacy != null) {
            holder.actionPending = false;
            if (ready(uuid)) {
                privacy.accept(player);
            } else {
                message(player, "Your profile is loading or unavailable.");
            }
            return;
        }
        if (!ready(uuid) || settings.state(uuid) != PlayerSettingsService.State.READY) {
            holder.actionPending = false;
            message(player, "Settings are loading, saving or unavailable. Try again shortly.");
            refresh(uuid);
            return;
        }
        var before = settings.cached(uuid).orElseThrow();
        var save = slot == SettingsMenu.BOSSBAR ? settings.setBossbarVisible(uuid, !before.bossbarVisible())
                : settings.setScoreboardVisible(uuid, !before.scoreboardVisible());
        save.whenCompleteAsync((value, error) -> holder.actionPending = false, mainThread);
        refresh(uuid);
        save.copy().orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).whenCompleteAsync((value, error) -> {
            if (!current(player, holder)) {
                return;
            }
            refresh(uuid);
            if (error != null) {
                message(player, "Could not confirm saved settings. Last confirmed choices are shown; reopen /settings to retry.");
            }
        }, mainThread);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof SettingsMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent event) {
        if (event.getInventory().getHolder(false) instanceof SettingsMenuHolder holder
                && (closed || !holder.playerId().equals(event.getPlayer().getUniqueId())
                || profiles.get(holder.playerId()).filter(session -> session.token() == holder.sessionToken()).isEmpty()
                || holder.getInventory() != event.getInventory()
                || !event.getPlayer().hasPermission("club.settings"))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof SettingsMenuHolder holder) {
            menus.remove(holder.playerId(), holder);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        menus.remove(event.getPlayer().getUniqueId());
    }

    private boolean current(Player player, SettingsMenuHolder holder) {
        return !closed && menus.get(holder.playerId()) == holder
                && holder.playerId().equals(player.getUniqueId()) && server.getPlayer(holder.playerId()) == player
                && profiles.get(holder.playerId()).filter(session -> session.token() == holder.sessionToken()).isPresent()
                && player.getOpenInventory().getTopInventory() == holder.getInventory();
    }

    private boolean ready(UUID uuid) {
        return profiles.get(uuid).filter(session -> session.state() == SessionState.READY).isPresent();
    }

    @Override
    public void close() {
        closed = true;
        for (var entry : Map.copyOf(menus).entrySet()) {
            Player player = server.getPlayer(entry.getKey());
            if (player != null && player.getOpenInventory().getTopInventory() == entry.getValue().getInventory()) {
                player.closeInventory();
            }
        }
        menus.clear();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    private static void message(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text, NamedTextColor.YELLOW));
    }
}
