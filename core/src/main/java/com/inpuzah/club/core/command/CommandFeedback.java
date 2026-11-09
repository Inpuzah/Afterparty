package com.inpuzah.club.core.command;

import com.inpuzah.club.core.session.ProfileSessionManager;
import com.inpuzah.club.core.session.ProfileSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.Optional;
import java.util.function.BiConsumer;

public final class CommandFeedback {
    private final Server server;
    private final ProfileSessionManager sessions;
    private final Executor mainThread;

    public CommandFeedback(Server server, ProfileSessionManager sessions, Executor mainThread) {
        this.server = server;
        this.sessions = sessions;
        this.mainThread = mainThread;
    }

    public <T> void complete(CommandSender sender, CompletableFuture<T> result, BiConsumer<T, Throwable> callback) {
        Optional<ProfileSession> session = sender instanceof Player player
                ? sessions.get(player.getUniqueId()) : Optional.empty();
        result.whenCompleteAsync((value, error) -> {
            if (sender instanceof Player player) {
                if (server.getPlayer(player.getUniqueId()) != player) {
                    return;
                }
                if (session.isPresent() && sessions.get(player.getUniqueId())
                        .filter(current -> current.token() == session.get().token()).isEmpty()) {
                    return;
                }
            }
            callback.accept(value, unwrap(error));
        }, mainThread);
    }

    public <T> void completeAction(CompletableFuture<T> result, BiConsumer<T, Throwable> callback) {
        result.whenCompleteAsync((value, error) -> callback.accept(value, unwrap(error)), mainThread);
    }

    static void error(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text, NamedTextColor.RED));
    }

    static void success(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text, NamedTextColor.GOLD));
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }
}
