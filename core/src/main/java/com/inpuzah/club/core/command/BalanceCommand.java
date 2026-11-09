package com.inpuzah.club.core.command;

import com.inpuzah.club.core.economy.EconomyService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class BalanceCommand implements TabExecutor {
    private final EconomyService economy;
    private final CommandFeedback feedback;
    private final Duration timeout;
    private final Logger logger;

    public BalanceCommand(EconomyService economy, CommandFeedback feedback, Duration timeout, Logger logger) {
        this.economy = economy;
        this.feedback = feedback;
        this.timeout = timeout;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("club.balance.view")) {
            CommandFeedback.error(sender, "You do not have permission to use this command.");
        } else if (!(sender instanceof Player player)) {
            sender.sendMessage(ProfileCommand.PLAYERS_ONLY);
        } else if (args.length != 0) {
            CommandFeedback.error(sender, "Usage: /balance");
        } else {
            var id = player.getUniqueId();
            feedback.complete(sender, economy.balance(id).copy().orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS),
                    (balance, error) -> {
                        if (error != null) {
                            logger.warn("Balance lookup failed for {}", id, error);
                            CommandFeedback.error(sender, "Balances are temporarily unavailable.");
                        } else {
                            CommandFeedback.success(sender, "Club Coins: " + balance.coins());
                        }
                    });
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
