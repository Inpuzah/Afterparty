package com.inpuzah.club.core.settings;

import com.inpuzah.club.core.display.DisplayPreferenceService;
import com.inpuzah.club.core.display.DisplayPreferences;
import com.inpuzah.club.core.session.ProfileSessionManager;
import com.inpuzah.club.core.session.SessionState;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Reuses V3 and its ordered bounded worker; no duplicate setting columns or display cache. */
public final class DefaultPlayerSettingsService implements PlayerSettingsService {
    private final DisplayPreferenceService storage;
    private final ProfileSessionManager profiles;
    private final Logger logger;
    private final long loadTimeoutMillis;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private Consumer<UUID> observer = uuid -> {};
    private boolean closed;

    public DefaultPlayerSettingsService(DisplayPreferenceService storage, ProfileSessionManager profiles,
                                       Duration loadTimeout, Logger logger) {
        this.storage = storage;
        this.profiles = profiles;
        this.loadTimeoutMillis = loadTimeout.toMillis();
        this.logger = logger;
    }

    /** Observer must be nonblocking and must not throw or access Bukkit from a worker. */
    public synchronized void observe(Consumer<UUID> observer) {
        this.observer = java.util.Objects.requireNonNull(observer);
    }

    /** Called on join; entry identity guards all callbacks across reconnects. */
    public synchronized void begin(UUID uuid) {
        if (closed) {
            throw new SettingsException(SettingsException.Code.CLOSED, "Settings service is closed");
        }
        Entry entry = new Entry();
        entries.put(uuid, entry);
        load(uuid, entry);
    }

    public synchronized void end(UUID uuid) {
        entries.remove(uuid);
    }

    @Override
    public synchronized CompletableFuture<PlayerSettings> get(UUID uuid) {
        Entry entry = entries.get(uuid);
        if (closed || entry == null) {
            return rejected(closed ? SettingsException.Code.CLOSED : SettingsException.Code.NOT_ONLINE);
        }
        if (entry.state == State.UNAVAILABLE) {
            load(uuid, entry);
            return entry.read.copy();
        }
        return entry.state == State.LOADING ? entry.read.copy() : CompletableFuture.completedFuture(entry.settings);
    }

    @Override
    public synchronized Optional<PlayerSettings> cached(UUID uuid) {
        Entry entry = entries.get(uuid);
        return Optional.ofNullable(entry == null ? null : entry.settings);
    }

    @Override
    public synchronized State state(UUID uuid) {
        Entry entry = entries.get(uuid);
        return entry == null ? State.OFFLINE : entry.state;
    }

    @Override
    public CompletableFuture<PlayerSettings> setBossbarVisible(UUID uuid, boolean visible) {
        return save(uuid, true, visible);
    }

    @Override
    public CompletableFuture<PlayerSettings> setScoreboardVisible(UUID uuid, boolean visible) {
        return save(uuid, false, visible);
    }

    private synchronized CompletableFuture<PlayerSettings> save(UUID uuid, boolean bossbar, boolean visible) {
        Entry entry = entries.get(uuid);
        if (closed || entry == null) {
            return rejected(closed ? SettingsException.Code.CLOSED : SettingsException.Code.NOT_ONLINE);
        }
        if (entry.state != State.READY) {
            return rejected(entry.state == State.SAVING ? SettingsException.Code.BUSY : SettingsException.Code.UNAVAILABLE);
        }
        if (profiles.get(uuid).filter(profile -> profile.state() == SessionState.READY).isEmpty()) {
            return rejected(SettingsException.Code.NOT_READY);
        }
        entry.state = State.SAVING;
        observer.accept(uuid);
        CompletableFuture<PlayerSettings> result = new CompletableFuture<>();
        storage.save(uuid, bossbar, visible).whenComplete((saved, error) -> {
            synchronized (this) {
                if (!closed && entries.get(uuid) == entry) {
                    if (error == null) {
                        entry.settings = snapshot(uuid, saved);
                        entry.state = State.READY;
                        observer.accept(uuid);
                    } else {
                        // Keep last-confirmed data for error UI, but don't permit another write before recovery.
                        entry.state = State.UNAVAILABLE;
                        logger.warn("Settings save outcome uncertain for {}; reloading authoritative choices", uuid, error);
                        observer.accept(uuid);
                        load(uuid, entry);
                    }
                }
                if (error == null) {
                    result.complete(snapshot(uuid, saved));
                } else {
                    result.completeExceptionally(error);
                }
            }
        });
        return result.copy();
    }

    private void load(UUID uuid, Entry entry) {
        entry.state = State.LOADING;
        entry.read = new CompletableFuture<>();
        CompletableFuture<PlayerSettings> read = entry.read;
        storage.load(uuid).orTimeout(loadTimeoutMillis, TimeUnit.MILLISECONDS).whenComplete((loaded, error) -> {
            synchronized (this) {
                if (!closed && entries.get(uuid) == entry) {
                    if (error == null) {
                        entry.settings = snapshot(uuid, loaded);
                        entry.state = State.READY;
                    } else {
                        entry.state = State.UNAVAILABLE;
                        logger.warn("Settings unavailable for {}; use /settings or /display status to retry", uuid, error);
                    }
                    observer.accept(uuid);
                }
                if (error == null) {
                    read.complete(snapshot(uuid, loaded));
                } else {
                    read.completeExceptionally(error);
                }
            }
        });
    }

    private static PlayerSettings snapshot(UUID uuid, DisplayPreferences preferences) {
        return new PlayerSettings(uuid, preferences.bossbar(), preferences.scoreboard());
    }

    private static CompletableFuture<PlayerSettings> rejected(SettingsException.Code code) {
        return CompletableFuture.failedFuture(new SettingsException(code, "Settings request rejected: " + code));
    }

    @Override
    public int shutdown(Duration timeout) throws InterruptedException {
        synchronized (this) {
            closed = true;
            entries.clear();
            observer = uuid -> {};
        }
        return storage.shutdown(timeout);
    }

    private static final class Entry {
        State state = State.LOADING;
        PlayerSettings settings;
        CompletableFuture<PlayerSettings> read;
    }
}
