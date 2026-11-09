package com.inpuzah.club.core.command;

import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.session.ProfileSessionManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.text.NumberFormat;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * {@code /profile} — shows the sender's own profile from in-memory session state. Never touches the database.
 */
public final class ProfileCommand implements TabExecutor {

    static final Component PLAYERS_ONLY = Component.text("This command can only be used by a player.", NamedTextColor.RED);
    static final Component USAGE = Component.text("Usage: /profile", NamedTextColor.RED);
    static final Component LOADING = Component.text("Your profile is still loading. Try again shortly.", NamedTextColor.YELLOW);
    static final Component UNAVAILABLE = Component.text("Profiles are temporarily unavailable.", NamedTextColor.RED);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final Component HEADER = Component.text("──────── Club Profile ────────", NamedTextColor.GOLD, TextDecoration.BOLD);
    private static final Component FOOTER = Component.text("──────────────────────────────", NamedTextColor.GOLD);

    private final ProfileSessionManager sessions;

    public ProfileCommand(ProfileSessionManager sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PLAYERS_ONLY);
            return true;
        }
        if (args.length != 0) {
            sender.sendMessage(USAGE);
            return true;
        }

        var session = sessions.get(player.getUniqueId());
        if (session.isEmpty()) {
            player.sendMessage(LOADING);
            return true;
        }
        switch (session.get().state()) {
            case READY -> player.sendMessage(render(session.get().profile()));
            case LOADING -> player.sendMessage(LOADING);
            case UNAVAILABLE, CLOSED -> player.sendMessage(UNAVAILABLE);
        }
        return true;
    }

    static Component render(PlayerProfile profile) {
        NumberFormat numbers = NumberFormat.getIntegerInstance(Locale.ROOT);
        numbers.setGroupingUsed(true);
        return Component.text()
                .append(HEADER).appendNewline()
                .append(line("Player", profile.username())).appendNewline()
                .append(line("Level", numbers.format(profile.level()))).appendNewline()
                .append(line("Experience", numbers.format(profile.experience()) + " XP")).appendNewline()
                .append(line("Coins", numbers.format(profile.coins()))).appendNewline()
                .append(line("First Joined", DATE.format(profile.firstJoinedAt()))).appendNewline()
                .append(FOOTER)
                .build();
    }

    private static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
