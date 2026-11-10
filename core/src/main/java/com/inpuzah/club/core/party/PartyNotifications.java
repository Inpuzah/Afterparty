package com.inpuzah.club.core.party;

import com.inpuzah.club.core.party.model.Party;
import com.inpuzah.club.core.party.model.PartyChange;
import com.inpuzah.club.core.session.ProfileSessionManager;
import com.inpuzah.club.core.session.SessionState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Receives immutable committed events on a worker; no Bukkit access until the main callback. */
public final class PartyNotifications implements Consumer<PartyChange> {
    private final Server server;
    private final ProfileSessionManager profiles;
    private final Executor main;
    private final BooleanSupplier enabled;

    public PartyNotifications(Server server, ProfileSessionManager profiles, Executor main, BooleanSupplier enabled) {
        this.server = server;
        this.profiles = profiles;
        this.main = main;
        this.enabled = enabled;
    }

    @Override public void accept(PartyChange change) {
        var recipients = new LinkedHashSet<UUID>();
        change.before().ifPresent(party -> party.members().forEach(member -> recipients.add(member.playerId())));
        change.after().ifPresent(party -> party.members().forEach(member -> recipients.add(member.playerId())));
        var tokens = new HashMap<UUID, Long>();
        recipients.forEach(id -> profiles.get(id).ifPresent(session -> tokens.put(id, session.token())));
        var lines = lines(change);
        if (lines.isEmpty()) { return; }
        main.execute(() -> {
            if (!enabled.getAsBoolean()) { return; }
            tokens.forEach((id, token) -> {
                var player = server.getPlayer(id);
                if (player != null && player.hasPermission("club.party.use")
                        && profiles.get(id).filter(session -> session.token() == token
                        && session.state() == SessionState.READY).isPresent()) {
                    lines.forEach(line -> player.sendMessage(Component.text("[Party] " + line, NamedTextColor.YELLOW)));
                }
            });
        });
    }

    static java.util.List<String> lines(PartyChange change) {
        var lines = new java.util.ArrayList<String>();
        Party before = change.before().orElse(null);
        Party after = change.after().orElse(null);
        if (after != null) {
            after.members().stream().filter(member -> change.outcome().code()
                            == com.inpuzah.club.core.party.model.PartyOutcome.Code.ACCEPTED
                            ? member.playerId().equals(change.actor()) : before == null || !before.isMember(member.playerId()))
                    .forEach(member -> lines.add(member.username() + " joined the party."));
        }
        if (before != null) {
            before.members().stream().filter(member -> after == null || !after.isMember(member.playerId()))
                    .forEach(member -> lines.add(member.username()
                            + (change.outcome().code() == com.inpuzah.club.core.party.model.PartyOutcome.Code.KICKED
                            ? " was removed from the party." : " left the party.")));
        }
        if (after != null && (before == null
                ? change.outcome().code() != com.inpuzah.club.core.party.model.PartyOutcome.Code.ACCEPTED
                : !before.leaderId().equals(after.leaderId()))) {
            var leader = after.members().stream().filter(member -> member.playerId().equals(after.leaderId())).findFirst().orElseThrow();
            lines.add(leader.username() + " is now party leader.");
        }
        if (before != null && after == null && (change.outcome().code()
                == com.inpuzah.club.core.party.model.PartyOutcome.Code.DISBANDED || before.members().size() == 1)) {
            lines.add("The party closed.");
        }
        return java.util.List.copyOf(lines);
    }
}
