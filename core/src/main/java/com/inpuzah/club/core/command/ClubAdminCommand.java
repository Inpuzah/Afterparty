package com.inpuzah.club.core.command;

import com.inpuzah.club.core.config.AdminCommandConfig;
import com.inpuzah.club.core.economy.CurrencyOperation;
import com.inpuzah.club.core.economy.CurrencyOperationResult;
import com.inpuzah.club.core.economy.CurrencyOperationType;
import com.inpuzah.club.core.economy.EconomyException;
import com.inpuzah.club.core.economy.EconomyService;
import com.inpuzah.club.core.progression.ProgressionException;
import com.inpuzah.club.core.progression.ProgressionService;
import com.inpuzah.club.core.progression.XpAwardRequest;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class ClubAdminCommand implements TabExecutor {
    private static final String USAGE =
            "Usage: /clubadmin xp add <online-player|uuid> <amount> [reason] or "
                    + "/clubadmin coins <add|remove> <online-player|uuid> <amount> [reason]";
    private final Server server;
    private final EconomyService economy;
    private final ProgressionService progression;
    private final CommandFeedback feedback;
    private final AdminCommandConfig config;
    private final Duration timeout;
    private final Logger logger;
    private final DisplayCommand displays;

    public ClubAdminCommand(Server server, EconomyService economy, ProgressionService progression,
                            CommandFeedback feedback, AdminCommandConfig config, Duration timeout, Logger logger) {
        this(server, economy, progression, feedback, config, timeout, logger, null);
    }

    public ClubAdminCommand(Server server, EconomyService economy, ProgressionService progression,
                            CommandFeedback feedback, AdminCommandConfig config, Duration timeout, Logger logger,
                            DisplayCommand displays) {
        this.server = server;
        this.economy = economy;
        this.progression = progression;
        this.feedback = feedback;
        this.config = config;
        this.timeout = timeout;
        this.logger = logger;
        this.displays = displays;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("displays") && displays != null) {
            return displays.admin(sender, args);
        }
        if (args.length == 0 || (!args[0].equalsIgnoreCase("xp") && !args[0].equalsIgnoreCase("coins"))) {
            CommandFeedback.error(sender, USAGE);
            return true;
        }
        boolean xp = args[0].equalsIgnoreCase("xp");
        if (!sender.hasPermission(xp ? "club.admin.xp" : "club.admin.coins")) {
            CommandFeedback.error(sender, "You do not have permission to use this command.");
            return true;
        }
        if (args.length < 4 || !(args[1].equalsIgnoreCase("add") || (!xp && args[1].equalsIgnoreCase("remove")))) {
            CommandFeedback.error(sender, USAGE);
            return true;
        }

        UUID operationId = UUID.randomUUID();
        UUID actorId = sender instanceof Player player ? player.getUniqueId() : null;
        UUID target;
        long amount;
        String reason = args.length == 4 ? "Administrator command" : String.join(" ", Arrays.copyOfRange(args, 4, args.length));
        boolean add = args[1].equalsIgnoreCase("add");
        try {
            target = resolveTarget(args[2]);
            if (!args[3].matches("[0-9]+")) {
                throw new IllegalArgumentException("Amount must be a positive whole number.");
            }
            amount = Long.parseLong(args[3]);
            long limit = xp ? config.maxXp() : config.maxCoins();
            if (amount <= 0 || (limit != 0 && amount > limit)) {
                throw new IllegalArgumentException(limit == 0 ? "Amount must be positive." : "Amount must be between 1 and " + limit + ".");
            }
            if (xp) {
                new XpAwardRequest(operationId, target, amount, "admin-command", reason, actorId);
            } else {
                new CurrencyOperation(operationId, target, amount, add ? CurrencyOperationType.CREDIT : CurrencyOperationType.DEBIT,
                        "admin-command", reason, actorId);
            }
        } catch (NumberFormatException e) {
            CommandFeedback.error(sender, "Amount must be a whole number between 1 and " + Long.MAX_VALUE + ".");
            return true;
        } catch (IllegalArgumentException e) {
            CommandFeedback.error(sender, e.getMessage());
            return true;
        }

        logger.info("Admin operation {}: {} {} amount={} player={} actor={}",
                operationId, xp ? "xp" : "coins", add ? "add" : "remove", amount, target,
                actorId == null ? "console" : actorId);
        CompletableFuture<String> result;
        if (xp) {
            result = progression.awardXp(operationId, target, amount, "admin-command", reason, actorId)
                    .thenApply(award -> "XP saved for " + target + ": " + award.experience() + " XP, level " + award.level() + ".");
        } else {
            result = (add ? economy.credit(operationId, target, amount, "admin-command", reason, actorId)
                    : economy.debit(operationId, target, amount, "admin-command", reason, actorId))
                    .thenApply(outcome -> outcome.status() == CurrencyOperationResult.Status.INSUFFICIENT_FUNDS
                            ? "Insufficient Club Coins; no coins removed."
                            : "Coins saved for " + target + ": " + outcome.balance() + " Club Coins.");
        }
        // Log even if the initiating player has disconnected before the response arrives.
        result.whenComplete((message, error) -> {
            if (error != null) {
                logger.warn("Admin operation {} could not be confirmed", operationId, error);
            } else {
                logger.info("Admin operation {}: {}", operationId, message);
            }
        });
        feedback.complete(sender, result.copy().orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS), (message, error) -> {
            if (error == null) {
                CommandFeedback.success(sender, message);
            } else {
                logger.warn("Admin operation {} response failed; retry only with the original ID and payload", operationId, error);
                CommandFeedback.error(sender, describe(error, operationId));
            }
        });
        return true;
    }

    private UUID resolveTarget(String value) {
        Player online = server.getPlayerExact(value);
        if (online != null) {
            return online.getUniqueId();
        }
        try {
            UUID uuid = UUID.fromString(value);
            if (uuid.toString().equalsIgnoreCase(value)) {
                return uuid;
            }
        } catch (IllegalArgumentException e) {
            // Only online names and canonical UUIDs are accepted; never resolve arbitrary offline names.
        }
        throw new IllegalArgumentException("Use an online player's exact name or a known player's full UUID.");
    }

    private static String describe(Throwable error, UUID operationId) {
        if (error instanceof EconomyException e) {
            return switch (e.code()) {
                case PROFILE_NOT_FOUND -> "That player has no saved profile.";
                case BALANCE_LIMIT_EXCEEDED -> "Credit would exceed the 1,000,000,000 Club Coins balance cap.";
                case OPERATION_CONFLICT -> "Operation ID conflict; check the server logs.";
                case SERVICE_UNAVAILABLE -> "Economy is temporarily unavailable; check the server logs before retrying.";
            };
        }
        if (error instanceof ProgressionException e) {
            return switch (e.code()) {
                case PROFILE_NOT_FOUND -> "That player has no saved profile.";
                case XP_OVERFLOW -> "Award would exceed the supported lifetime XP total.";
                case OPERATION_CONFLICT -> "Operation ID conflict; check the server logs.";
                case SERVICE_UNAVAILABLE -> "Progression is temporarily unavailable; check the server logs before retrying.";
            };
        }
        return "Could not confirm operation " + operationId + ". Check server logs before retrying; it may have committed.";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> candidates;
        if (args.length == 1) {
            candidates = (displays == null ? List.of("xp", "coins") : List.of("xp", "coins", "displays")).stream()
                    .filter(kind -> sender.hasPermission("club.admin." + kind)).toList();
        } else if (args.length == 2 && displays != null && args[0].equalsIgnoreCase("displays")
                && sender.hasPermission("club.admin.displays")) {
            candidates = List.of("reload");
        } else if (args.length >= 2 && (args[0].equalsIgnoreCase("xp") || args[0].equalsIgnoreCase("coins"))
                && sender.hasPermission("club.admin." + args[0].toLowerCase(Locale.ROOT))) {
            candidates = switch (args.length) {
                case 2 -> args[0].equalsIgnoreCase("xp") ? List.of("add") : List.of("add", "remove");
                case 3 -> server.getOnlinePlayers().stream().map(Player::getName).sorted().toList();
                default -> List.of();
            };
        } else {
            return List.of();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return candidates.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
