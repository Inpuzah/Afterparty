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
import com.inpuzah.club.core.settings.DefaultPlayerSettingsService;
import com.inpuzah.club.core.settings.PlayerSettingsService;
import com.inpuzah.club.core.settings.SettingsMenuController;
import com.inpuzah.club.core.social.SocialConfig;
import com.inpuzah.club.core.social.command.SocialCommand;
import com.inpuzah.club.core.social.repository.JdbcSocialRepository;
import com.inpuzah.club.core.social.service.DefaultSocialService;
import com.inpuzah.club.core.social.service.SocialService;
import com.inpuzah.club.core.social.menu.PrivacyMenuController;
import com.inpuzah.club.core.social.menu.InteractionMenuController;
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
    private DefaultPlayerSettingsService settingsService;
    private SettingsMenuController settingsMenus;
    private DisplayReloadService displayReloads;
    private DefaultSocialService socialService;
    private PrivacyMenuController privacyMenus;
    private InteractionMenuController interactionMenus;

    @Override
    public void onEnable() {
        Logger log = getSLF4JLogger();
        log.info("Starting ClubCore {}", getPluginMeta().getVersion());
        saveDefaultConfig();

        ClubCoreConfig config;
        DisplayConfig displayConfig;
        SocialConfig socialConfig;
        try {
            config = ClubCoreConfig.load(getConfig(), System::getenv);
            displayConfig = DisplayConfig.load(getConfig());
            socialConfig = SocialConfig.load(getConfig());
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
        socialService = DefaultSocialService.create(new JdbcSocialRepository(database.dataSource(), socialConfig),
                config.database().poolSize(), PROFILE_QUEUE_CAPACITY, log);
        settingsService = new DefaultPlayerSettingsService(
                new DisplayPreferenceService(new JdbcDisplayPreferenceRepository(database.dataSource())),
                sessions, config.profile().loadTimeout(), log);
        displayReloads = new DisplayReloadService(new File(getDataFolder(), "config.yml"), () -> getResource("config.yml"));
        displays = new DisplayManager(getServer(), sessions, settingsService, displayConfig);
        settingsMenus = new SettingsMenuController(getServer(), sessions, settingsService, mainThread,
                config.profile().loadTimeout().toMillis());
        privacyMenus = new PrivacyMenuController(getServer(), sessions, socialService, mainThread,
                config.profile().loadTimeout().toMillis(), log);
        settingsMenus.privacyMenu(privacyMenus::open);
        privacyMenus.backToSettings(player -> settingsMenus.onCommand(player, null, "settings", new String[0]));
        interactionMenus = new InteractionMenuController(getServer(), sessions, socialService, mainThread,
                config.profile().loadTimeout().toMillis(), log, socialConfig.interactionsEnabled());
        settingsService.observe(uuid -> {
            displays.markDirty(uuid);
            mainThread.execute(() -> settingsMenus.refresh(uuid));
        });
        sessions.observe(uuid -> {
            displays.markDirty(uuid);
            mainThread.execute(() -> settingsMenus.refresh(uuid));
            mainThread.execute(() -> privacyMenus.refresh(uuid));
            mainThread.execute(() -> interactionMenus.refresh(uuid));
        });
        getServer().getPluginManager().registerEvents(displays, this);
        getServer().getPluginManager().registerEvents(settingsMenus, this);
        getServer().getPluginManager().registerEvents(privacyMenus, this);
        getServer().getPluginManager().registerEvents(interactionMenus, this);
        DisplayCommand displayCommand = new DisplayCommand(displays, settingsService, displayReloads, feedback, log,
                config.profile().loadTimeout().toMillis());
        if (!registerCommand("profile", new ProfileCommand(sessions))
                || !registerCommand("balance", new BalanceCommand(economyService, feedback, config.profile().loadTimeout(), log))
                || !registerCommand("display", displayCommand)
                || !registerCommand("settings", settingsMenus)
                || !registerCommand("clubadmin", new ClubAdminCommand(getServer(), economyService, progressionService,
                        feedback, config.adminCommands(), config.profile().loadTimeout(), log, displayCommand))) {
            return;
        }
        for (SocialCommand.Kind kind : SocialCommand.Kind.values()) {
            if (!registerCommand(kind.name().toLowerCase(java.util.Locale.ROOT),
                    new SocialCommand(kind, getServer(), sessions, socialService, feedback,
                            config.profile().loadTimeout(), log))) {
                return;
            }
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
        if (interactionMenus != null) {
            interactionMenus.close();
            interactionMenus = null;
        }
        if (privacyMenus != null) {
            privacyMenus.close();
            privacyMenus = null;
        }
        if (settingsService != null) {
            settingsService.observe(uuid -> {});
        }
        if (settingsMenus != null) {
            settingsMenus.close();
            settingsMenus = null;
        }
        if (displays != null) {
            sessions.observe(uuid -> {});
            displays.close();
            displays = null;
        }
        if (displayReloads != null) {
            drain("display-config", displayReloads::shutdown);
            displayReloads = null;
        }
        if (settingsService != null) {
            drain("settings", settingsService::shutdown);
            settingsService = null;
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
        if (socialService != null) {
            drain("social", socialService::shutdown);
            socialService = null;
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

    public PlayerSettingsService settingsService() {
        return settingsService;
    }

    public SocialService socialService() {
        return socialService;
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
