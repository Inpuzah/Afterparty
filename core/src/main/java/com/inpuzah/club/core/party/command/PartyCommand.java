package com.inpuzah.club.core.party.command;

import com.inpuzah.club.core.command.CommandFeedback;
import com.inpuzah.club.core.party.model.PartyInvite;
import com.inpuzah.club.core.party.model.PartyOutcome;
import com.inpuzah.club.core.party.service.PartyService;
import com.inpuzah.club.core.session.ProfileSessionManager;
import com.inpuzah.club.core.session.SessionState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Server-thread presentation only; repository transactions own membership authorization. */
public final class PartyCommand implements TabExecutor {
    private static final List<String> ACTIONS = List.of("create", "invite", "invites", "accept", "deny",
            "list", "leave", "kick", "promote", "disband", "help");
    private static final int PAGE_SIZE = 10;
    private static final String USAGE = "Usage: /party create|invite <online-player>|invites [page]|accept <invite-uuid>"
            + "|deny <invite-uuid>|list|leave|kick <online-player|uuid>|promote <online-player|uuid>|disband";
    private final Server server;
    private final ProfileSessionManager sessions;
    private final PartyService service;
    private final CommandFeedback feedback;
    private final long timeoutMillis;
    private final Logger logger;
    private final BooleanSupplier enabled;
    private final Consumer<Player> openMenu;
    private final java.util.function.Predicate<UUID> admitted;
    private final Duration disconnectGrace;
    private com.inpuzah.club.core.party.PartyInvitations invitations;

    public void invitations(com.inpuzah.club.core.party.PartyInvitations invitations) {
        this.invitations = java.util.Objects.requireNonNull(invitations);
    }

    public PartyCommand(Server server, ProfileSessionManager sessions, PartyService service,
                        CommandFeedback feedback, Duration timeout, Logger logger,
                        BooleanSupplier enabled, Consumer<Player> openMenu) {
        this(server, sessions, service, feedback, timeout, logger, enabled, openMenu, id -> true, Duration.ofMinutes(5));
    }

    public PartyCommand(Server server, ProfileSessionManager sessions, PartyService service,
                        CommandFeedback feedback, Duration timeout, Logger logger,
                        BooleanSupplier enabled, Consumer<Player> openMenu, java.util.function.Predicate<UUID> admitted,
                        Duration disconnectGrace) {
        this.server = server;
        this.sessions = sessions;
        this.service = service;
        this.feedback = feedback;
        this.timeoutMillis = timeout.toMillis();
        this.logger = logger;
        this.enabled = enabled;
        this.openMenu = openMenu;
        this.admitted = admitted;
        this.disconnectGrace = disconnectGrace;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("club.party.use")) {
            message(sender, "You do not have permission to use parties.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            message(sender, "This command can only be used by a player.");
            return true;
        }
        if (!enabled.getAsBoolean()) {
            message(player, "Parties are disabled or temporarily unavailable.");
            return true;
        }
        if (!ready(player.getUniqueId())) {
            message(player, "Your profile is loading or unavailable. Try again shortly.");
            return true;
        }
        if (args.length == 0) {
            openMenu.accept(player);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        // Validate player input before starting any asynchronous operation.
        UUID target = null;
        int page = 1;
        try {
            switch (action) {
                case "invite", "kick", "promote" -> {
                    if (args.length != 2) { throw new IllegalArgumentException(USAGE); }
                    target = target(args[1], action.equals("invite"));
                    if (target.equals(player.getUniqueId())) {
                        throw new IllegalArgumentException("Choose another player.");
                    }
                }
                case "accept", "deny" -> {
                    if (args.length != 2) { throw new IllegalArgumentException(USAGE); }
                    target = uuid(args[1], "Use the full invitation UUID shown by /party invites.");
                }
                case "invites" -> {
                    if (args.length > 2) { throw new IllegalArgumentException(USAGE); }
                    if (args.length == 2) {
                        page = page(args[1]);
                    }
                }
                case "create", "list", "leave", "disband", "help" -> {
                    if (args.length != 1) { throw new IllegalArgumentException(USAGE); }
                }
                default -> throw new IllegalArgumentException(USAGE);
            }
        } catch (IllegalArgumentException error) {
            message(player, error.getMessage());
            return true;
        }
        UUID actor = player.getUniqueId();
        switch (action) {
            case "create" -> mutation(player, service.create(actor), null);
            case "invite" -> {
                if (invitations == null) { mutation(player, service.invite(actor, target), server.getPlayer(target)); }
                else { mutation(player, invitations.invite(player, server.getPlayer(target)), null); }
            }
            case "accept" -> mutation(player, service.accept(actor, target), null);
            case "deny" -> mutation(player, service.deny(actor, target), null);
            case "kick" -> mutation(player, service.kick(actor, target), null);
            case "promote" -> mutation(player, service.promote(actor, target), null);
            case "disband" -> mutation(player, service.disband(actor), null);
            case "leave" -> mutation(player, service.leave(actor, server.getOnlinePlayers().stream()
                    .filter(online -> ready(online.getUniqueId())).map(Player::getUniqueId).collect(Collectors.toUnmodifiableSet())), null);
            case "list" -> list(player);
            case "invites" -> invites(player, page);
            case "help" -> {
                message(player, USAGE);
                message(player, "Private party chat: /pc <message> (when enabled).");
                message(player, "Parties end on server restart. Disconnect grace: " + disconnectGrace.toSeconds() + " seconds.");
            }
            default -> throw new IllegalStateException("Validated party action was not handled");
        }
        return true;
    }

    private void mutation(Player player, CompletableFuture<PartyOutcome> result, Player recipient) {
        respond(player, result, outcome -> message(player, describe(outcome.code())));
        if (recipient != null) {
            String name = player.getName();
            feedback.complete(recipient, result, (outcome, error) -> {
                if (error == null && canReply(recipient) && outcome.code() == PartyOutcome.Code.INVITED) {
                    UUID id = outcome.inviteId().orElseThrow();
                    message(recipient, name + " invited you to a party.");
                    inviteActions(recipient, id);
                }
            });
        }
    }

    private void list(Player player) {
        respond(player, service.getParty(player.getUniqueId()), result -> {
            if (result.isEmpty()) {
                message(player, "You are not in a party. Use /party create or /party invite <online-player>.");
                return;
            }
            var party = result.orElseThrow();
            message(player, "Party: " + party.members().size() + "/" + party.maxMembers()
                    + " members. Parties end on server restart.");
            for (var member : party.members()) {
                message(player, (party.isLeader(member.playerId()) ? "[Leader] " : "") + member.username()
                        + " [" + member.playerId() + "] - "
                        + (server.getPlayer(member.playerId()) != null ? "online" : "offline"));
            }
        });
    }

    private void invites(Player player, int page) {
        respond(player, service.getInvites(player.getUniqueId(), (page - 1) * PAGE_SIZE, PAGE_SIZE), rows -> {
            message(player, "Incoming party invitations - page " + page + (rows.isEmpty() ? ": none." : ":"));
            for (PartyInvite invite : rows) {
                Player inviter = server.getPlayer(invite.inviterId());
                String name = inviter == null ? "" : inviter.getName() + " ";
                message(player, "From " + name + "[" + invite.inviterId() + "] - expires " + invite.expiresAt());
                inviteActions(player, invite.id());
            }
            if (rows.size() == PAGE_SIZE && page < 1_000_000) {
                message(player, "Next page: /party invites " + (page + 1));
            }
        });
    }

    private static void inviteActions(Player player, UUID inviteId) {
        player.sendMessage(Component.text("[Accept]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/party accept " + inviteId))
                .append(Component.text(" "))
                .append(Component.text("[Deny]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/party deny " + inviteId)))
                .append(Component.text(" [" + inviteId + "]", NamedTextColor.GRAY)));
    }

    private <T> void respond(Player player, CompletableFuture<T> result, Consumer<T> success) {
        feedback.complete(player, result.copy().orTimeout(timeoutMillis, TimeUnit.MILLISECONDS), (value, error) -> {
            if (!canReply(player)) {
                return;
            }
            if (error != null) {
                logger.warn("Party command outcome unavailable for {}; a mutation may have committed", player.getUniqueId(), error);
                message(player, "Party action is not confirmed. It may still complete. Check /party list or /party invites before retrying.");
            } else {
                success.accept(value);
            }
        });
    }

    private boolean canReply(Player player) {
        return enabled.getAsBoolean() && player.hasPermission("club.party.use") && ready(player.getUniqueId());
    }

    private UUID target(String name, boolean requireOnline) {
        Player online = server.getPlayerExact(name);
        if (online != null) {
            if (!ready(online.getUniqueId())) {
                throw new IllegalArgumentException("Target profile is loading or unavailable.");
            }
            return online.getUniqueId();
        }
        if (requireOnline) {
            throw new IllegalArgumentException("Target must be an online player with a ready profile.");
        }
        return uuid(name, "Use an exact online name or the full member UUID shown by /party list.");
    }

    private static UUID uuid(String text, String error) {
        try {
            UUID id = UUID.fromString(text);
            if (!id.toString().equalsIgnoreCase(text)) {
                throw new IllegalArgumentException();
            }
            return id;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(error);
        }
    }

    private static int page(String text) {
        try {
            int page = Integer.parseInt(text);
            if (page < 1 || page > 1_000_000) { throw new NumberFormatException(); }
            return page;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Page must be a whole number from 1 to 1000000.");
        }
    }

    private boolean ready(UUID player) {
        return admitted.test(player) && sessions.get(player).filter(session -> session.state() == SessionState.READY).isPresent();
    }

    public static String describe(PartyOutcome.Code code) {
        return switch (code) {
            case CREATED -> "Party created. Parties end on server restart.";
            case INVITED -> "Party invitation saved.";
            case ACCEPTED -> "You joined the party.";
            case DENIED -> "Party invitation declined.";
            case LEFT -> "You left the party.";
            case KICKED -> "Party member removed.";
            case PROMOTED -> "Party leadership transferred.";
            case DISBANDED -> "Party disbanded.";
            case STALE_PARTY -> "Party changed. Review the current party before trying again.";
            case ALREADY_IN_PARTY -> "That player is already in a party.";
            case NOT_IN_PARTY -> "You are not in a party.";
            case NOT_LEADER -> "Only the party leader can do that.";
            case PARTY_FULL -> "The party is full. Invitations do not reserve slots.";
            case INVITE_EXPIRED -> "That party invitation has expired.";
            case INVITE_NOT_FOUND -> "That party invitation is missing or already resolved.";
            case ALREADY_INVITED -> "That player already has a pending party invitation.";
            case TARGET_UNAVAILABLE -> "Target profile is unavailable.";
            case BLOCKED_OR_RESTRICTED -> "That party invitation cannot be sent or accepted.";
            case INVALID_TARGET -> "Choose another current party member or eligible target.";
            case RATE_LIMITED -> "Party invitation limit or cooldown reached. Try again later.";
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !canReply(player)) {
            return List.of();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return ACTIONS.stream().filter(action -> action.startsWith(prefix)).toList();
        }
        if (args.length == 2 && List.of("invite", "kick", "promote").contains(args[0].toLowerCase(Locale.ROOT))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return server.getOnlinePlayers().stream().filter(target -> !target.getUniqueId().equals(player.getUniqueId()))
                    .filter(target -> ready(target.getUniqueId())).map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
        }
        return List.of();
    }

    private static void message(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text, NamedTextColor.YELLOW));
    }
}
