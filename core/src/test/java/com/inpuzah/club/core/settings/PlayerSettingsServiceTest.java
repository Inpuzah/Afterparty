package com.inpuzah.club.core.settings;

import com.inpuzah.club.core.display.DisplayPreferenceRepository;
import com.inpuzah.club.core.display.DisplayPreferenceService;
import com.inpuzah.club.core.display.DisplayPreferences;
import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.repository.ProfileStorageException;
import com.inpuzah.club.core.session.ProfileSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlayerSettingsServiceTest {
    private final UUID uuid = UUID.randomUUID();
    private final ProfileSessionManager profiles = new ProfileSessionManager();
    private final AtomicInteger notifications = new AtomicInteger();
    private final AtomicInteger writes = new AtomicInteger();
    private final AtomicInteger reads = new AtomicInteger();
    private volatile DisplayPreferences saved = new DisplayPreferences(false, true);
    private volatile boolean fail;
    private volatile boolean failRead;
    private CountDownLatch gate = new CountDownLatch(0);
    private CountDownLatch readGate = new CountDownLatch(0);
    private final DisplayPreferenceService storage = new DisplayPreferenceService(new DisplayPreferenceRepository() {
        @Override public DisplayPreferences load(UUID id) {
            reads.incrementAndGet();
            try {
                if (!readGate.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("read gate timeout"); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            if (failRead) { throw new ProfileStorageException("read failure", null); }
            return saved;
        }
        @Override public DisplayPreferences save(UUID id, boolean bossbar, boolean enabled) {
            try {
                if (!gate.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("gate timeout"); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            writes.incrementAndGet();
            if (fail) { throw new ProfileStorageException("save failure", null); }
            saved = bossbar ? new DisplayPreferences(enabled, saved.scoreboard())
                    : new DisplayPreferences(saved.bossbar(), enabled);
            return saved;
        }
    });
    private final DefaultPlayerSettingsService settings = new DefaultPlayerSettingsService(
            storage, profiles, Duration.ofSeconds(1), LoggerFactory.getLogger(getClass()));

    @AfterEach
    void stop() throws Exception {
        gate.countDown();
        readGate.countDown();
        settings.shutdown(Duration.ofSeconds(5));
    }

    private void join() throws Exception {
        profiles.markReady(uuid, profiles.begin(uuid).token(), PlayerProfile.newProfile(uuid, "Player", Instant.EPOCH));
        settings.observe(id -> notifications.incrementAndGet());
        settings.begin(uuid);
        settings.get(uuid).get(5, TimeUnit.SECONDS);
    }

    @Test
    void loadsExistingV3ChoicesAndExposesOneImmutableSnapshot() throws Exception {
        join();
        assertEquals(new PlayerSettings(uuid, false, true), settings.get(uuid).get());
        assertEquals(settings.get(uuid).get(), settings.cached(uuid).orElseThrow());
        assertEquals(PlayerSettingsService.State.READY, settings.state(uuid));
        int count = reads.get();
        settings.get(uuid).get();
        assertEquals(count, reads.get());
    }

    @Test
    void rejectsRapidMutationsAndPublishesOnlyCommittedResult() throws Exception {
        join();
        gate = new CountDownLatch(1);
        var first = settings.setBossbarVisible(uuid, true);
        assertEquals(PlayerSettingsService.State.SAVING, settings.state(uuid));
        assertFalse(settings.cached(uuid).orElseThrow().bossbarVisible());
        var error = assertThrows(ExecutionException.class, () -> settings.setScoreboardVisible(uuid, false).get());
        assertEquals(SettingsException.Code.BUSY, assertInstanceOf(SettingsException.class, error.getCause()).code());
        gate.countDown();
        assertTrue(first.get(5, TimeUnit.SECONDS).bossbarVisible());
        assertEquals(1, writes.get());
        assertEquals(PlayerSettingsService.State.READY, settings.state(uuid));
        assertTrue(notifications.get() >= 3);
    }

    @Test
    void timeoutAndCancellationDoNotCancelSaveOrItsCacheUpdate() throws Exception {
        join();
        gate = new CountDownLatch(1);
        var caller = settings.setBossbarVisible(uuid, true).orTimeout(1, TimeUnit.MILLISECONDS);
        assertThrows(ExecutionException.class, () -> caller.get(5, TimeUnit.SECONDS));
        assertEquals(PlayerSettingsService.State.SAVING, settings.state(uuid));
        gate.countDown();
        storage.load(uuid).get(5, TimeUnit.SECONDS);
        assertTrue(settings.cached(uuid).orElseThrow().bossbarVisible());
        gate = new CountDownLatch(1);
        var cancelled = settings.setScoreboardVisible(uuid, false);
        assertTrue(cancelled.cancel(true));
        gate.countDown();
        storage.load(uuid).get(5, TimeUnit.SECONDS);
        assertFalse(settings.cached(uuid).orElseThrow().scoreboardVisible());
        assertEquals(2, writes.get());
    }

    @Test
    void reconnectReadFollowsAcceptedSaveAndOldCallbackCannotUpdateNewSession() throws Exception {
        join();
        gate = new CountDownLatch(1);
        var save = settings.setBossbarVisible(uuid, true);
        settings.end(uuid);
        assertEquals(PlayerSettingsService.State.OFFLINE, settings.state(uuid));
        settings.begin(uuid);
        var read = settings.get(uuid);
        gate.countDown();
        save.get(5, TimeUnit.SECONDS);
        assertTrue(read.get(5, TimeUnit.SECONDS).bossbarVisible());
        assertEquals(PlayerSettingsService.State.READY, settings.state(uuid));
    }

    @Test
    void failedWriteKeepsConfirmedChoiceAndRecoveryDoesNotReportSuccess() throws Exception {
        join();
        fail = true;
        failRead = true;
        assertThrows(ExecutionException.class, () -> settings.setBossbarVisible(uuid, true).get(5, TimeUnit.SECONDS));
        try { storage.load(uuid).get(5, TimeUnit.SECONDS); } catch (ExecutionException expected) {
            assertInstanceOf(ProfileStorageException.class, expected.getCause());
        }
        assertEquals(new PlayerSettings(uuid, false, true), settings.cached(uuid).orElseThrow());
        assertEquals(PlayerSettingsService.State.UNAVAILABLE, settings.state(uuid));
        failRead = false;
        fail = false;
        assertEquals(new PlayerSettings(uuid, false, true), settings.get(uuid).get(5, TimeUnit.SECONDS));
        settings.setScoreboardVisible(uuid, false).get(5, TimeUnit.SECONDS);
        assertFalse(settings.cached(uuid).orElseThrow().scoreboardVisible());
    }

    @Test
    void offlineNotReadyAndShutdownRejectWritesWithoutPersistence() throws Exception {
        assertThrows(ExecutionException.class, () -> settings.get(uuid).get());
        join();
        profiles.beginRefresh(uuid);
        var rejected = assertThrows(ExecutionException.class, () -> settings.setBossbarVisible(uuid, true).get());
        assertEquals(SettingsException.Code.NOT_READY, assertInstanceOf(SettingsException.class, rejected.getCause()).code());
        assertEquals(0, writes.get());
        settings.shutdown(Duration.ofSeconds(5));
        assertTrue(settings.cached(uuid).isEmpty());
        assertThrows(ExecutionException.class, () -> settings.get(uuid).get());
    }

    @Test
    void loadFailureIsExplicitAndCanBeRetriedWithoutFalseDefault() throws Exception {
        failRead = true;
        settings.begin(uuid);
        assertThrows(ExecutionException.class, () -> settings.get(uuid).get(5, TimeUnit.SECONDS));
        assertTrue(settings.cached(uuid).isEmpty());
        failRead = false;
        assertEquals(new PlayerSettings(uuid, false, true), settings.get(uuid).get(5, TimeUnit.SECONDS));
    }

    @Test
    void callerCannotCancelSharedReadAndTimedOutLoadCannotPublishLate() throws Exception {
        readGate = new CountDownLatch(1);
        settings.begin(uuid);
        var caller = settings.get(uuid);
        assertTrue(caller.cancel(true));
        var remaining = settings.get(uuid);
        assertThrows(ExecutionException.class, () -> remaining.get(5, TimeUnit.SECONDS));
        assertEquals(PlayerSettingsService.State.UNAVAILABLE, settings.state(uuid));
        assertTrue(settings.cached(uuid).isEmpty());
        readGate.countDown();
        storage.load(uuid).get(5, TimeUnit.SECONDS);
        assertEquals(PlayerSettingsService.State.UNAVAILABLE, settings.state(uuid));
        assertTrue(settings.cached(uuid).isEmpty());
        assertEquals(new PlayerSettings(uuid, false, true), settings.get(uuid).get(5, TimeUnit.SECONDS));
    }

    @Test
    void snapshotRejectsMissingIdentity() {
        assertThrows(NullPointerException.class, () -> new PlayerSettings(null, true, true));
    }
}
