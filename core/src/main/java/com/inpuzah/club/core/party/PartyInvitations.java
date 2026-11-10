package com.inpuzah.club.core.party;

import com.inpuzah.club.core.command.CommandFeedback;
import com.inpuzah.club.core.party.model.PartyOutcome;
import com.inpuzah.club.core.party.service.PartyService;
import com.inpuzah.club.core.session.ProfileSessionManager;
import com.inpuzah.club.core.session.SessionState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Single main-thread invitation entry point shared by commands and menus. */
public final class PartyInvitations {
    private final Server server;
    private final ProfileSessionManager profiles;
    private final PartyService service;
    private final CommandFeedback feedback;
    private final Executor main;
    private final BooleanSupplier enabled;
    private final Predicate<UUID> admitted;
    private final Map<UUID, CompletableFuture<PartyOutcome>> pending = new HashMap<>();

    public PartyInvitations(Server server, ProfileSessionManager profiles, PartyService service, Executor main,
                            BooleanSupplier enabled, Predicate<UUID> admitted) {
        this.server = server;
        this.profiles = profiles;
        this.service = service;
        this.main = main;
        this.enabled = enabled;
        this.admitted = admitted;
        feedback = new CommandFeedback(server, profiles, main);
    }

    public CompletableFuture<PartyOutcome> invite(Player actor, Player target) {
        if (!enabled.getAsBoolean() || !actor.hasPermission("club.party.use") || !ready(actor) || !ready(target)
                || actor.getUniqueId().equals(target.getUniqueId())) {
            return CompletableFuture.completedFuture(PartyOutcome.of(PartyOutcome.Code.TARGET_UNAVAILABLE));
        }
        if (pending.containsKey(actor.getUniqueId())) {
            return CompletableFuture.completedFuture(PartyOutcome.of(PartyOutcome.Code.RATE_LIMITED));
        }
        var result = service.invite(actor.getUniqueId(), target.getUniqueId());
        pending.put(actor.getUniqueId(), result);
        result.whenCompleteAsync((value, error) -> pending.remove(actor.getUniqueId(), result), main);
        String name = actor.getName();
        feedback.complete(target, result, (value, error) -> {
            if (error == null && enabled.getAsBoolean() && ready(target) && target.hasPermission("club.party.use")
                    && value.code() == PartyOutcome.Code.INVITED) {
                UUID id = value.inviteId().orElseThrow();
                target.sendMessage(Component.text(name + " invited you to a party. ", NamedTextColor.YELLOW)
                        .append(Component.text("[Accept]", NamedTextColor.GREEN)
                                .clickEvent(ClickEvent.runCommand("/party accept " + id)))
                        .append(Component.text(" [Deny]", NamedTextColor.RED)
                                .clickEvent(ClickEvent.runCommand("/party deny " + id))));
            }
        });
        return result.copy();
    }

    public boolean ready(Player player) {
        return player != null && server.getPlayer(player.getUniqueId()) == player && admitted.test(player.getUniqueId())
                && profiles.get(player.getUniqueId()).filter(session -> session.state() == SessionState.READY).isPresent();
    }
}
