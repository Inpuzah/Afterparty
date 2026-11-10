package com.inpuzah.club.core.display;

import com.inpuzah.club.core.config.ConfigException;
import com.inpuzah.club.core.service.BoundedServiceExecutor;
import com.inpuzah.club.core.service.ProfileServiceClosedException;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class DisplayReloadService {
    private final File file;
    private final Supplier<InputStream> defaults;
    private final BoundedServiceExecutor worker = new BoundedServiceExecutor(
            BoundedServiceExecutor.workers("ClubCore-DisplayConfig", 1, 1),
            message -> new ProfileServiceClosedException(message, null));

    public DisplayReloadService(File file, Supplier<InputStream> defaults) {
        this.file = file;
        this.defaults = defaults;
    }

    public CompletableFuture<DisplayConfig> load() {
        return worker.submit(() -> {
            try (var reader = new InputStreamReader(Objects.requireNonNull(defaults.get()), StandardCharsets.UTF_8)) {
                var bundled = new YamlConfiguration();
                bundled.load(reader);
                var updated = new YamlConfiguration();
                updated.load(file);
                updated.setDefaults(bundled);
                return DisplayConfig.load(updated);
            } catch (IOException | InvalidConfigurationException e) {
                throw new ConfigException("Cannot read display config: " + e.getMessage());
            }
        });
    }

    public int shutdown(Duration timeout) throws InterruptedException {
        return worker.shutdown(timeout);
    }
}
