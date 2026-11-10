package com.inpuzah.club.core.party.chat;

import com.inpuzah.club.core.command.CommandFeedback;
import com.inpuzah.club.core.party.model.Party;
import com.inpuzah.club.core.party.repository.PartyException;
import com.inpuzah.club.core.party.service.PartyService;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Literal, local-only delivery while the repository retains the membership transaction lock. */
public final class PartyChatCommand implements TabExecutor, Listener, AutoCloseable {
    private final Server server;
    private final ProfileSessionManager profiles;
    private final PartyService service;
    private final Executor main;
    private final BooleanSupplier enabled;
    private final Predicate<UUID> admitted;
    private final Consumer<PartyChatEvent> moderation;
    private final LongSupplier nanoTime;
    private final Logger logger;
    private final CommandFeedback feedback;
    private final Map<UUID, Long> lastAttempt = new HashMap<>();
    private final Set<UUID> pending = new HashSet<>();
    private boolean closed;

    public PartyChatCommand(Server server, ProfileSessionManager profiles, PartyService service, Executor main,
                            BooleanSupplier enabled, Predicate<UUID> admitted, Logger logger) {
        this(server, profiles, service, main, enabled, admitted, logger,
                event -> server.getPluginManager().callEvent(event), System::nanoTime);
    }

    PartyChatCommand(Server server, ProfileSessionManager profiles, PartyService service, Executor main,
                     BooleanSupplier enabled, Predicate<UUID> admitted, Logger logger,
                     Consumer<PartyChatEvent> moderation, LongSupplier nanoTime) {
        this.server = server;
        this.profiles = profiles;
        this.service = service;
        this.main = main;
        this.enabled = enabled;
        this.admitted = admitted;
        this.logger = logger;
        this.moderation = moderation;
        this.nanoTime = nanoTime;
        feedback = new CommandFeedback(server, profiles, main);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { message(sender, "Party chat can only be used by a player."); return true; }
        if (!eligible(player) || player.hasPermission("club.party.muted")) {
            message(player, "Party chat is disabled, restricted or your profile is unavailable."); return true;
        }
        String text = String.join(" ", args);
        if (text.isBlank() || text.length() > 256 || text.codePoints().anyMatch(value -> Character.isISOControl(value)
                || Character.getType(value) == Character.FORMAT || Character.getType(value) == Character.LINE_SEPARATOR
                || Character.getType(value) == Character.PARAGRAPH_SEPARATOR || Character.getType(value) == Character.SURROGATE)) {
            message(player, "Usage: /pc <message> (1-256 characters, no control characters).");
            return true;
        }
        UUID id = player.getUniqueId();
        long now = nanoTime.getAsLong();
        Long previous = lastAttempt.get(id);
        if (pending.contains(id) || previous != null && now - previous < Duration.ofSeconds(1).toNanos()) {
            message(player, "Party chat is rate limited. Wait at least one second between messages.");
            return true;
        }
        lastAttempt.put(id, now);
        pending.add(id);
        long token = profiles.get(id).orElseThrow().token();
        String name = player.getName();
        // The worker must not release its DB lock until the main callback completes or is safely cancelled.
        var result = service.withParty(id, party -> deliver(player, token, name, text, party));
        result.whenCompleteAsync((value, error) -> pending.remove(id), main);
        feedback.complete(player, result, (value, error) -> {
            if (error == null || !eligible(player)) { return; }
            if (error instanceof PartyException missing && missing.code() == PartyException.Code.NOT_IN_PARTY) {
                message(player, "You are not in a party.");
            } else {
                logger.warn("Party chat delivery could not be confirmed for {}; message content is not logged", id, error);
                message(player, "Party chat delivery was not confirmed. Do not automatically resend.");
            }
        });
        return true;
    }

    private void deliver(Player sender, long senderToken, String name, String text, Party party) {
        var tokens = new HashMap<UUID, Long>();
        party.members().forEach(member -> profiles.get(member.playerId())
                .ifPresent(session -> tokens.put(member.playerId(), session.token())));
        var finished = new CompletableFuture<Void>();
        var active = new AtomicBoolean(true);
        main.execute(() -> {
            if (!active.compareAndSet(true, false)) { return; }
            try {
                if (!eligible(sender) || sender.hasPermission("club.party.muted") || server.getPlayer(sender.getUniqueId()) != sender
                        || !login(sender.getUniqueId(), senderToken)) {
                    throw new IllegalStateException("Party sender session changed before delivery");
                }
                var event = new PartyChatEvent(sender, party.id(), text);
                moderation.accept(event);
                if (event.isCancelled()) {
                    message(sender, "Party message was blocked by moderation.");
                    finished.complete(null);
                    return;
                }
                if (!eligible(sender) || sender.hasPermission("club.party.muted") || !login(sender.getUniqueId(), senderToken)) {
                    throw new IllegalStateException("Party sender became unavailable during moderation");
                }
                var content = Component.text("[Party] " + name + ": ", NamedTextColor.LIGHT_PURPLE)
                        .append(Component.text(text, NamedTextColor.WHITE));
                tokens.forEach((id, token) -> {
                    var recipient = server.getPlayer(id);
                    if (recipient != null && eligible(recipient) && login(id, token)) { recipient.sendMessage(content); }
                });
                finished.complete(null);
            } catch (RuntimeException error) {
                finished.completeExceptionally(error);
            }
        });
        try {
            finished.get(2, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException error) {
            // If already executing, retain the DB lock until main-thread delivery has actually finished.
            if (!active.compareAndSet(true, false)) { finished.join(); }
            else { throw new IllegalStateException("Party delivery expired before the server callback", error); }
        } catch (InterruptedException error) {
            if (!active.compareAndSet(true, false)) { finished.join(); }
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Party delivery interrupted", error);
        } catch (java.util.concurrent.ExecutionException error) {
            throw new IllegalStateException("Party delivery failed", error.getCause());
        }
    }

    private boolean login(UUID id, long token) {
        return profiles.get(id).filter(session -> session.token() == token && session.state() == SessionState.READY).isPresent();
    }
    private boolean eligible(Player player) {
        return !closed && enabled.getAsBoolean() && server.getPlayer(player.getUniqueId()) == player && admitted.test(player.getUniqueId())
                && player.hasPermission("club.party.use") && player.hasPermission("club.party.chat")
                && profiles.get(player.getUniqueId()).filter(session -> session.state() == SessionState.READY).isPresent();
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return List.of();
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { lastAttempt.remove(event.getPlayer().getUniqueId()); }
    @Override public void close() { closed = true; lastAttempt.clear(); }
    private static void message(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text, NamedTextColor.YELLOW));
    }
}
