package com.inpuzah.club.core.command;

import com.inpuzah.club.core.display.DisplayManager;
import com.inpuzah.club.core.display.DisplayReloadService;
import com.inpuzah.club.core.settings.PlayerSettingsService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

public final class DisplayCommand implements TabExecutor {
    private final DisplayManager displays;
    private final DisplayReloadService reloads;
    private final CommandFeedback feedback;
    private final Logger logger;
    private final long timeoutMillis;
    private boolean reloading;
    private final PlayerSettingsService settings;

    public DisplayCommand(DisplayManager displays, PlayerSettingsService settings, DisplayReloadService reloads, CommandFeedback feedback,
                          Logger logger, long timeoutMillis) {
        this.displays = displays;
        this.settings = settings;
        this.reloads = reloads;
        this.feedback = feedback;
        this.logger = logger;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("club.display")) {
            CommandFeedback.error(sender, "You do not have permission to use this command.");
        } else if (!(sender instanceof Player player)) {
            sender.sendMessage(ProfileCommand.PLAYERS_ONLY);
        } else if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            var preference = settings.cached(player.getUniqueId()).orElse(null);
            var state = settings.state(player.getUniqueId());
            if (preference == null || state == PlayerSettingsService.State.UNAVAILABLE
                    || state == PlayerSettingsService.State.LOADING) {
                settings.get(player.getUniqueId());
                CommandFeedback.error(sender, "Display preferences are loading or unavailable. Try again shortly.");
            } else {
                var config = displays.config();
                CommandFeedback.success(sender, "Saved displays: bossbar " + (preference.bossbarVisible() ? "on" : "off")
                        + ", scoreboard " + (preference.scoreboardVisible() ? "on" : "off")
                        + (state == PlayerSettingsService.State.SAVING ? " (saving...)" : "")
                        + ". Server enabled: " + config.enabled()
                        + "; bossbar: " + config.bossbarEnabled() + "; scoreboard: " + config.scoreboardEnabled() + ".");
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("bossbar") || args[0].equalsIgnoreCase("scoreboard"))
                && (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            var uuid = player.getUniqueId();
            if (settings.state(uuid) != PlayerSettingsService.State.READY) {
                CommandFeedback.error(sender, "Display preferences are loading, saving or unavailable. Use /display status to retry.");
            } else {
                String kind = args[0].toLowerCase(Locale.ROOT);
                boolean enabled = args[1].equalsIgnoreCase("on");
                feedback.complete(sender, (kind.equals("bossbar") ? settings.setBossbarVisible(uuid, enabled)
                        : settings.setScoreboardVisible(uuid, enabled))
                        .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS), (saved, error) -> {
                    if (error != null) {
                        logger.warn("Display toggle could not be confirmed for {}", uuid, error);
                        CommandFeedback.error(sender, "Could not confirm saved display preference. Check /display status shortly.");
                    } else {
                        CommandFeedback.success(sender, kind + " saved " + (enabled ? "on" : "off") + ".");
                    }
                });
            }
        } else {
            CommandFeedback.error(sender, "Usage: /display status or /display <bossbar|scoreboard> <on|off>");
        }
        return true;
    }

    public boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("club.admin.displays")) {
            CommandFeedback.error(sender, "You do not have permission to use this command.");
        } else if (args.length != 2 || !args[1].equalsIgnoreCase("reload")) {
            CommandFeedback.error(sender, "Usage: /clubadmin displays reload");
        } else if (reloading) {
            CommandFeedback.error(sender, "A display reload is already running.");
        } else {
            reloading = true;
            var reload = reloads.load().orTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
            var applied = new CompletableFuture<Void>();
            feedback.completeAction(reload, (config, error) -> {
                reloading = false;
                if (error != null) {
                    logger.warn("Display reload rejected; previous configuration retained", error);
                    applied.completeExceptionally(error);
                } else {
                    displays.replaceConfig(config);
                    applied.complete(null);
                }
            });
            feedback.complete(sender, applied, (ignored, error) -> {
                if (error != null) {
                    CommandFeedback.error(sender, "Display reload failed; previous settings retained. See server logs.");
                } else {
                    CommandFeedback.success(sender, "Display configuration reloaded.");
                }
            });
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("club.display") || !(sender instanceof Player) || args.length == 0) {
            return List.of();
        }
        var candidates = args.length == 1 ? List.of("status", "bossbar", "scoreboard")
                : args.length == 2 && (args[0].equalsIgnoreCase("bossbar") || args[0].equalsIgnoreCase("scoreboard"))
                ? List.of("on", "off") : List.<String>of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return candidates.stream().filter(value -> value.startsWith(prefix)).toList();
    }
}
