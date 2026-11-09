package com.inpuzah.club.core;

import com.inpuzah.club.core.command.ProfileCommand;
import com.inpuzah.club.core.config.ClubCoreConfig;
import com.inpuzah.club.core.config.ConfigException;
import com.inpuzah.club.core.listener.PlayerSessionListener;
import com.inpuzah.club.core.persistence.DatabaseManager;
import com.inpuzah.club.core.persistence.Migrations;
import com.inpuzah.club.core.repository.JdbcProfileRepository;
import com.inpuzah.club.core.service.DefaultProfileService;
import com.inpuzah.club.core.service.ProfileService;
import com.inpuzah.club.core.session.ProfileSessionManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.concurrent.Executor;

public final class ClubCorePlugin extends JavaPlugin {

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private static final int PROFILE_QUEUE_CAPACITY = 1_000;

    private DatabaseManager database;
    private DefaultProfileService profileService;
    private ProfileSessionManager sessions;

    @Override
    public void onEnable() {
        Logger log = getSLF4JLogger();
        log.info("Starting ClubCore {}", getPluginMeta().getVersion());
        saveDefaultConfig();

        ClubCoreConfig config;
        try {
            config = ClubCoreConfig.load(getConfig(), System::getenv);
        } catch (ConfigException e) {
            fail("Invalid configuration: " + e.getMessage(), null);
            return;
        }
        log.info("Database: {}", config.database());

        try {
            database = DatabaseManager.connect(config.database());
            MigrateResult migration = Migrations.apply(database.dataSource(), getClassLoader());
            log.info("Schema migration complete: {} migration(s) applied, schema version {}",
                    migration.migrationsExecuted,
                    migration.targetSchemaVersion != null ? migration.targetSchemaVersion : migration.initialSchemaVersion);
        } catch (RuntimeException e) {
            fail("Could not connect to or migrate the database", e);
            return;
        }

        sessions = new ProfileSessionManager();
        profileService = DefaultProfileService.create(
                new JdbcProfileRepository(database.dataSource()), config.database().poolSize(), PROFILE_QUEUE_CAPACITY);

        Executor mainThread = task -> {
            if (isEnabled()) {
                getServer().getScheduler().runTask(this, task);
            }
        };
        PlayerSessionListener listener = new PlayerSessionListener(
                getServer(), log, profileService, sessions, config.profile(), mainThread);
        getServer().getPluginManager().registerEvents(listener, this);

        PluginCommand profileCommand = getCommand("profile");
        if (profileCommand == null) {
            fail("Command 'profile' is missing from plugin.yml", null);
            return;
        }
        ProfileCommand executor = new ProfileCommand(sessions);
        profileCommand.setExecutor(executor);
        profileCommand.setTabCompleter(executor);

        // Players already online after a reload never fire PlayerJoinEvent for us.
        for (Player player : getServer().getOnlinePlayers()) {
            listener.startSession(player);
        }

        log.info("Profile service ready");
    }

    @Override
    public void onDisable() {
        Logger log = getSLF4JLogger();
        if (profileService != null) {
            sessions.closeAll().forEach(session -> profileService.touchLastSeen(session.uuid()));
            try {
                int abandoned = profileService.shutdown(SHUTDOWN_TIMEOUT);
                if (abandoned > 0) {
                    log.warn("{} profile write(s) did not finish within {}s and were abandoned",
                            abandoned, SHUTDOWN_TIMEOUT.toSeconds());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for profile writes to finish");
            }
            profileService = null;
        }
        if (database != null) {
            database.close();
            database = null;
        }
        log.info("ClubCore disabled");
    }

    public ProfileService profileService() {
        return profileService;
    }

    private void fail(String reason, Throwable cause) {
        if (cause != null) {
            getSLF4JLogger().error("{}; disabling ClubCore.", reason, cause);
        } else {
            getSLF4JLogger().error("{}; disabling ClubCore.", reason);
        }
        getServer().getPluginManager().disablePlugin(this);
    }
}
