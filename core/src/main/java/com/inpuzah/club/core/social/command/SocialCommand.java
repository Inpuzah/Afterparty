package com.inpuzah.club.core.social.command;

import com.inpuzah.club.core.command.CommandFeedback;
import com.inpuzah.club.core.session.ProfileSessionManager;
import com.inpuzah.club.core.session.SessionState;
import com.inpuzah.club.core.social.model.FriendSummary;
import com.inpuzah.club.core.social.model.SocialAction;
import com.inpuzah.club.core.social.model.SocialResult;
import com.inpuzah.club.core.social.service.SocialService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class SocialCommand implements TabExecutor {
    public enum Kind { FRIEND, FRIENDS, BLOCK, UNBLOCK, BLOCKED }
    private static final int PAGE_SIZE = 10;
    private final Kind kind;
    private final Server server;
    private final ProfileSessionManager sessions;
    private final SocialService service;
    private final CommandFeedback feedback;
    private final long timeoutMillis;
    private final Logger logger;

    public SocialCommand(Kind kind, Server server, ProfileSessionManager sessions, SocialService service,
                         CommandFeedback feedback, Duration timeout, Logger logger) {
        this.kind = kind;
        this.server = server;
        this.sessions = sessions;
        this.service = service;
        this.feedback = feedback;
        this.timeoutMillis = timeout.toMillis();
        this.logger = logger;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("club.social.use")) {
            message(sender, "You do not have permission to use this command.");
        } else if (!(sender instanceof Player player)) {
            message(sender, "This command can only be used by a player.");
        } else if (!ready(player.getUniqueId())) {
            message(sender, "Your profile is loading or unavailable. Try again shortly.");
        } else {
            try {
                if (kind == Kind.FRIENDS || kind == Kind.BLOCKED) {
                    if (args.length > 1) { throw new IllegalArgumentException(usage()); }
                    list(player, args.length == 0 ? 1 : page(args[0]), false);
                } else if (kind == Kind.FRIEND && args.length >= 1 && args[0].equalsIgnoreCase("requests")) {
                    if (args.length > 2) { throw new IllegalArgumentException(usage()); }
                    list(player, args.length == 1 ? 1 : page(args[1]), true);
                } else {
                    SocialAction action;
                    String targetName;
                    if (kind == Kind.FRIEND) {
                        if (args.length != 2) { throw new IllegalArgumentException(usage()); }
                        action = switch (args[0].toLowerCase(Locale.ROOT)) {
                            case "add" -> SocialAction.REQUEST;
                            case "accept" -> SocialAction.ACCEPT;
                            case "deny" -> SocialAction.DENY;
                            case "cancel" -> SocialAction.CANCEL;
                            case "remove" -> SocialAction.REMOVE;
                            default -> throw new IllegalArgumentException(usage());
                        };
                        targetName = args[1];
                    } else {
                        if (args.length != 1) { throw new IllegalArgumentException(usage()); }
                        action = kind == Kind.BLOCK ? SocialAction.BLOCK : SocialAction.UNBLOCK;
                        targetName = args[0];
                    }
                    mutate(player, action, targetName);
                }
            } catch (IllegalArgumentException e) {
                message(sender, e.getMessage());
            }
        }
        return true;
    }

    private void mutate(Player player, SocialAction action, String name) {
        Player online = server.getPlayerExact(name);
        UUID target;
        if (online != null) {
            target = online.getUniqueId();
            if (!ready(target)) {
                throw new IllegalArgumentException("Target profile is loading or unavailable.");
            }
        } else if (action == SocialAction.REQUEST || action == SocialAction.BLOCK) {
            throw new IllegalArgumentException("Target must be an online player with a ready profile.");
        } else {
            try {
                target = UUID.fromString(name);
                if (!target.toString().equalsIgnoreCase(name)) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Use an exact online name or the full UUID shown in your list.");
            }
        }
        var result = service.mutate(action, player.getUniqueId(), target);
        feedback.complete(player, result.copy().orTimeout(timeoutMillis, TimeUnit.MILLISECONDS), (value, error) -> {
            if (error != null) {
                failure(player, error);
            } else {
                message(player, describe(value));
            }
        });
        if (online != null && (action == SocialAction.REQUEST || action == SocialAction.ACCEPT)) {
            String actorName = player.getName();
            feedback.complete(online, result, (value, error) -> {
                if (error == null && online.hasPermission("club.social.use")) {
                    if (value == SocialResult.REQUEST_SENT) {
                        message(online, actorName + " sent a friend request. Use /friend requests.");
                    } else if (value == SocialResult.ACCEPTED) {
                        message(online, actorName + " accepted your friend request.");
                    }
                }
            });
        }
    }

    private void list(Player player, int page, boolean requests) {
        int offset = (page - 1) * PAGE_SIZE;
        if (requests) {
            feedback.complete(player, service.listIncomingRequests(player.getUniqueId(), offset, PAGE_SIZE)
                    .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS), (rows, error) -> {
                if (error != null) {
                    failure(player, error);
                } else {
                    message(player, "Incoming friend requests - page " + page + (rows.isEmpty() ? ": none." : ":"));
                    rows.forEach(row -> message(player, row.senderName() + " [" + row.senderId() + "]"));
                    if (rows.size() == PAGE_SIZE) { message(player, "Next page: /friend requests " + (page + 1)); }
                }
            });
        } else {
            var result = kind == Kind.BLOCKED ? service.listBlocked(player.getUniqueId(), offset, PAGE_SIZE)
                    : service.listFriends(player.getUniqueId(), offset, PAGE_SIZE);
            feedback.complete(player, result.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS), (rows, error) -> {
                if (error != null) {
                    failure(player, error);
                } else {
                    message(player, (kind == Kind.BLOCKED ? "Blocked players" : "Friends") + " - page " + page
                            + (rows.isEmpty() ? ": none." : ":"));
                    rows.forEach(row -> summary(player, row));
                    if (rows.size() == PAGE_SIZE) {
                        message(player, "Next page: /" + kind.name().toLowerCase(Locale.ROOT) + " " + (page + 1));
                    }
                }
            });
        }
    }

    private void summary(Player viewer, FriendSummary row) {
        String presence = kind == Kind.FRIENDS ? (server.getPlayer(row.playerId()) == null ? " - offline" : " - online") : "";
        message(viewer, row.username() + presence + " [" + row.playerId() + "]");
    }

    private void failure(Player player, Throwable error) {
        logger.warn("Social command could not be confirmed for {}; check current state before retrying",
                player.getUniqueId(), error);
        message(player, "Social services are temporarily unavailable or the operation timed out. "
                + "A change may have saved; check your lists before retrying.");
    }

    private boolean ready(UUID uuid) {
        return sessions.get(uuid).filter(session -> session.state() == SessionState.READY).isPresent();
    }

    private static int page(String text) {
        try {
            if (!text.matches("[0-9]+")) { throw new NumberFormatException(); }
            int value = Integer.parseInt(text);
            if (value < 1 || value > 1_000_000) { throw new NumberFormatException(); }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Page must be a whole number between 1 and 1000000.");
        }
    }

    private String usage() {
        return switch (kind) {
            case FRIEND -> "Usage: /friend <add|accept|deny|cancel|remove> <player> or /friend requests [page]";
            case FRIENDS -> "Usage: /friends [page]";
            case BLOCK -> "Usage: /block <online-player>";
            case UNBLOCK -> "Usage: /unblock <online-player|uuid>";
            case BLOCKED -> "Usage: /blocked [page]";
        };
    }

    public static String describe(SocialResult result) {
        return switch (result) {
            case REQUEST_SENT -> "Friend request saved.";
            case ACCEPTED -> "Friend request accepted; friendship saved.";
            case DENIED -> "Friend request declined.";
            case CANCELLED -> "Outgoing friend request cancelled.";
            case REMOVED -> "Friendship removed.";
            case BLOCKED -> "Player blocked; friendship and pending requests removed.";
            case UNBLOCKED -> "Player unblocked.";
            case SELF_TARGET -> "You cannot target yourself.";
            case PLAYER_NOT_FOUND -> "Target profile is unavailable.";
            case ALREADY_FRIENDS -> "You are already friends.";
            case ALREADY_REQUESTED -> "You already have a pending request to this player.";
            case INCOMING_REQUEST_EXISTS -> "This player has already requested you. Use /friend accept.";
            case REQUEST_MISSING -> "No active request was found; it may have expired.";
            case NOT_FRIENDS -> "You are not friends with this player.";
            case ALREADY_BLOCKED -> "You have already blocked this player.";
            case NOT_BLOCKED -> "This player is not on your block list.";
            case CANNOT_REQUEST -> "This friend request cannot be sent or accepted.";
            case RATE_LIMITED -> "Friend request rate limit or retry cooldown reached. Try again later.";
            case INBOX_FULL -> "This player cannot receive more friend requests right now.";
        };
    }

    private static void message(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text, NamedTextColor.YELLOW));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("club.social.use") || !(sender instanceof Player player) || !ready(player.getUniqueId())
                || args.length == 0) {
            return List.of();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (kind == Kind.FRIEND && args.length == 1) {
            return List.of("add", "accept", "deny", "cancel", "remove", "requests").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        if ((kind == Kind.FRIEND && args.length == 2 && !args[0].equalsIgnoreCase("requests"))
                || ((kind == Kind.BLOCK || kind == Kind.UNBLOCK) && args.length == 1)) {
            return server.getOnlinePlayers().stream().filter(target -> !target.getUniqueId().equals(player.getUniqueId()))
                    .filter(target -> ready(target.getUniqueId())).map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
        }
        return List.of();
    }
}
