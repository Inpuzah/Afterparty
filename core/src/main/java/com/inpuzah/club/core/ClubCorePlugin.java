package com.inpuzah.club.core;

import com.inpuzah.club.core.command.ProfileCommand;
import com.inpuzah.club.core.command.BalanceCommand;
import com.inpuzah.club.core.command.ClubAdminCommand;
import com.inpuzah.club.core.command.CommandFeedback;
import com.inpuzah.club.core.command.DisplayCommand;
import com.inpuzah.club.core.display.DisplayConfig;
import com.inpuzah.club.core.display.DisplayManager;
import com.inpuzah.club.core.display.DisplayPreferenceService;
import com.inpuzah.club.core.display.DisplayReloadService;
import com.inpuzah.club.core.display.JdbcDisplayPreferenceRepository;
import com.inpuzah.club.core.config.ClubCoreConfig;
import com.inpuzah.club.core.config.ConfigException;
import com.inpuzah.club.core.listener.PlayerSessionListener;
import com.inpuzah.club.core.economy.DefaultEconomyService;
import com.inpuzah.club.core.economy.EconomyService;
import com.inpuzah.club.core.economy.JdbcEconomyRepository;
import com.inpuzah.club.core.progression.DefaultProgressionService;
import com.inpuzah.club.core.progression.ProgressionService;
import com.inpuzah.club.core.progression.JdbcProgressionRepository;
import com.inpuzah.club.core.persistence.DatabaseManager;
import com.inpuzah.club.core.persistence.Migrations;
import com.inpuzah.club.core.repository.JdbcProfileRepository;
import com.inpuzah.club.core.service.DefaultProfileService;
import com.inpuzah.club.core.service.ProfileService;
import com.inpuzah.club.core.service.ProfileRefreshService;
import com.inpuzah.club.core.session.ProfileSessionManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;

import java.time.Duration;
import java.io.File;
import java.util.concurrent.Executor;

public final class ClubCorePlugin extends JavaPlugin {

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private static final int PROFILE_QUEUE_CAPACITY = 1_000;

    private DatabaseManager database;
    private DefaultProfileService profileService;
    private ProfileSessionManager sessions;
    private DefaultEconomyService economyService;
    private DefaultProgressionService progressionService;
    private ProfileRefreshService refreshes;
    private DisplayManager displays;
    private DisplayPreferenceService displayPreferences;
    private DisplayReloadService displayReloads;

    @Override
    public void onEnable() {
        Logger log = getSLF4JLogger();
        log.info("Starting ClubCore {}", getPluginMeta().getVersion());
        saveDefaultConfig();

        ClubCoreConfig config;
        DisplayConfig displayConfig;
        try {
            config = ClubCoreConfig.load(getConfig(), System::getenv);
            displayConfig = DisplayConfig.load(getConfig());
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
        refreshes = new ProfileRefreshService(profileService, sessions, config.profile().loadTimeout(), log);
        economyService = DefaultEconomyService.create(new JdbcEconomyRepository(database.dataSource()),
                config.database().poolSize(), PROFILE_QUEUE_CAPACITY, uuid -> refreshes.refresh(uuid));
        progressionService = DefaultProgressionService.create(new JdbcProgressionRepository(database.dataSource()),
                config.database().poolSize(), PROFILE_QUEUE_CAPACITY, uuid -> refreshes.refresh(uuid));

        Executor mainThread = task -> {
            if (isEnabled()) {
                getServer().getScheduler().runTask(this, task);
            }
        };
        PlayerSessionListener listener = new PlayerSessionListener(
                getServer(), log, profileService, sessions, config.profile(), mainThread, refreshes);
        getServer().getPluginManager().registerEvents(listener, this);

        CommandFeedback feedback = new CommandFeedback(getServer(), sessions, mainThread);
        displayPreferences = new DisplayPreferenceService(new JdbcDisplayPreferenceRepository(database.dataSource()));
        displayReloads = new DisplayReloadService(new File(getDataFolder(), "config.yml"), () -> getResource("config.yml"));
        displays = new DisplayManager(getServer(), sessions, displayPreferences, displayConfig, log,
                config.profile().loadTimeout().toMillis());
        sessions.observe(displays::markDirty);
        getServer().getPluginManager().registerEvents(displays, this);
        DisplayCommand displayCommand = new DisplayCommand(displays, displayReloads, feedback, log,
                config.profile().loadTimeout().toMillis());
        if (!registerCommand("profile", new ProfileCommand(sessions))
                || !registerCommand("balance", new BalanceCommand(economyService, feedback, config.profile().loadTimeout(), log))
                || !registerCommand("display", displayCommand)
                || !registerCommand("clubadmin", new ClubAdminCommand(getServer(), economyService, progressionService,
                        feedback, config.adminCommands(), config.profile().loadTimeout(), log, displayCommand))) {
            return;
        }

        // Players already online after a reload never fire PlayerJoinEvent for us.
        for (Player player : getServer().getOnlinePlayers()) {
            listener.startSession(player);
        }
        displays.start(this);

        log.info("Profile, progression and economy services ready");
    }

    @Override
    public void onDisable() {
        Logger log = getSLF4JLogger();
        if (displays != null) {
            sessions.observe(uuid -> {});
            displays.close();
            displays = null;
        }
        if (displayReloads != null) {
            drain("display-config", displayReloads::shutdown);
            displayReloads = null;
        }
        if (displayPreferences != null) {
            drain("display-preferences", displayPreferences::shutdown);
            displayPreferences = null;
        }
        if (refreshes != null) {
            refreshes.close();
        }
        if (profileService != null) {
            sessions.closeAll().forEach(session -> profileService.touchLastSeen(session.uuid())
                    .exceptionally(error -> {
                        log.warn("Could not record shutdown last-seen time for {}", session.uuid(), error);
                        return null;
                    }));
        }
        if (economyService != null) {
            drain("economy", economyService::shutdown);
            economyService = null;
        }
        if (progressionService != null) {
            drain("progression", progressionService::shutdown);
            progressionService = null;
        }
        if (profileService != null) {
            drain("profile", profileService::shutdown);
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

    public EconomyService economyService() {
        return economyService;
    }

    public ProgressionService progressionService() {
        return progressionService;
    }

    public DisplayManager displays() {
        return displays;
    }

    private boolean registerCommand(String name, TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            fail("Command '" + name + "' is missing from plugin.yml", null);
            return false;
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        return true;
    }

    private void drain(String name, Shutdown shutdown) {
        try {
            int abandoned = shutdown.stop(SHUTDOWN_TIMEOUT);
            if (abandoned > 0) {
                getSLF4JLogger().warn("{} {} request(s) unresolved after {}s; in-flight commits may have succeeded",
                        abandoned, name, SHUTDOWN_TIMEOUT.toSeconds());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            getSLF4JLogger().warn("Interrupted while waiting for {} workers to finish", name);
        }
    }

    @FunctionalInterface
    private interface Shutdown {
        int stop(Duration timeout) throws InterruptedException;
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
