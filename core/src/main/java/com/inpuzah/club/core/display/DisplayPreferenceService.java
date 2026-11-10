package com.inpuzah.club.core.display;

import com.inpuzah.club.core.service.BoundedServiceExecutor;
import com.inpuzah.club.core.service.ProfileServiceClosedException;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class DisplayPreferenceService {
    private final DisplayPreferenceRepository repository;
    // One bounded worker preserves save/load order across rapid toggles and reconnects.
    private final BoundedServiceExecutor workers = new BoundedServiceExecutor(
            BoundedServiceExecutor.workers("ClubCore-Preferences", 1, 1000),
            message -> new ProfileServiceClosedException(message, null));

    public DisplayPreferenceService(DisplayPreferenceRepository repository) {
        this.repository = repository;
    }

    public CompletableFuture<DisplayPreferences> load(UUID uuid) {
        return workers.submit(() -> repository.load(uuid));
    }

    public CompletableFuture<DisplayPreferences> save(UUID uuid, boolean bossbar, boolean enabled) {
        return workers.submit(() -> repository.save(uuid, bossbar, enabled));
    }

    public int shutdown(Duration timeout) throws InterruptedException {
        return workers.shutdown(timeout);
    }
}
