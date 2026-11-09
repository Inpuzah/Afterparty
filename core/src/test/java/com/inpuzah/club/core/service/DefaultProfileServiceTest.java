package com.inpuzah.club.core.service;

import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.repository.ProfileRepository;
import com.inpuzah.club.core.repository.ProfileStorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultProfileServiceTest {

    private static final UUID ID = UUID.randomUUID();

    private final FakeRepository repository = new FakeRepository();
    private DefaultProfileService service = DefaultProfileService.create(repository, 2, 10);

    @AfterEach
    void tearDown() throws InterruptedException {
        repository.release.countDown();
        service.shutdown(Duration.ofSeconds(5));
    }

    @Test
    void getOrCreateReturnsRepositoryProfile() throws Exception {
        repository.release.countDown();

        PlayerProfile profile = service.getOrCreate(ID, "Player").get(5, TimeUnit.SECONDS);

        assertEquals(ID, profile.uuid());
        assertEquals("Player", profile.username());
    }

    @Test
    void concurrentLoadsForSamePlayerAreCoalesced() throws Exception {
        CompletableFuture<PlayerProfile> first = service.getOrCreate(ID, "Player");
        CompletableFuture<PlayerProfile> second = service.getOrCreate(ID, "Player");
        repository.release.countDown();

        assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        assertEquals(1, repository.upserts.get());
    }

    @Test
    void callerTimeoutDoesNotFailOtherWaiters() throws Exception {
        CompletableFuture<PlayerProfile> impatient = service.getOrCreate(ID, "Player").orTimeout(1, TimeUnit.MILLISECONDS);
        CompletableFuture<PlayerProfile> patient = service.getOrCreate(ID, "Player");

        assertThrows(ExecutionException.class, () -> impatient.get(5, TimeUnit.SECONDS));
        repository.release.countDown();
        assertEquals(ID, patient.get(5, TimeUnit.SECONDS).uuid());
    }

    @Test
    void newLoadIsIssuedAfterPreviousCompletes() throws Exception {
        repository.release.countDown();
        service.getOrCreate(ID, "Player").get(5, TimeUnit.SECONDS);
        service.getOrCreate(ID, "Player").get(5, TimeUnit.SECONDS);

        assertEquals(2, repository.upserts.get());
    }

    @Test
    void missingProfileIsEmptyNotAnError() throws Exception {
        repository.release.countDown();

        assertEquals(Optional.empty(), service.findById(UUID.randomUUID()).get(5, TimeUnit.SECONDS));
    }

    @Test
    void storageFailureIsReportedAsStorageException() {
        repository.fail = true;
        repository.release.countDown();

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> service.getOrCreate(ID, "Player").get(5, TimeUnit.SECONDS));
        assertInstanceOf(ProfileStorageException.class, error.getCause());
    }

    @Test
    void workAfterShutdownIsRejected() throws Exception {
        repository.release.countDown();
        assertEquals(0, service.shutdown(Duration.ofSeconds(5)));

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> service.touchLastSeen(ID).get(5, TimeUnit.SECONDS));
        assertInstanceOf(ProfileServiceClosedException.class, error.getCause());
    }

    @Test
    void fullQueueRejectsInsteadOfGrowingUnbounded() throws Exception {
        service.shutdown(Duration.ZERO);
        service = DefaultProfileService.create(repository, 1, 1);

        service.getOrCreate(UUID.randomUUID(), "Busy");     // occupies the worker
        service.getOrCreate(UUID.randomUUID(), "Queued");   // fills the queue
        CompletableFuture<Void> rejected = service.touchLastSeen(ID);

        assertTrue(rejected.isCompletedExceptionally());
        ExecutionException error = assertThrows(ExecutionException.class, rejected::get);
        assertInstanceOf(ProfileServiceClosedException.class, error.getCause());
    }

    @Test
    void shutdownReportsAbandonedWork() throws Exception {
        service.shutdown(Duration.ZERO);
        service = DefaultProfileService.create(repository, 1, 10);

        service.getOrCreate(UUID.randomUUID(), "Busy");
        service.getOrCreate(UUID.randomUUID(), "Queued");

        assertEquals(1, service.shutdown(Duration.ofMillis(100)));
    }

    private static final class FakeRepository implements ProfileRepository {
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger upserts = new AtomicInteger();
        volatile boolean fail;

        @Override
        public PlayerProfile upsertOnJoin(UUID uuid, String username) {
            upserts.incrementAndGet();
            awaitRelease();
            if (fail) {
                throw new ProfileStorageException("simulated outage", null);
            }
            return PlayerProfile.newProfile(uuid, username, Instant.EPOCH);
        }

        @Override
        public Optional<PlayerProfile> findById(UUID uuid) {
            awaitRelease();
            return Optional.empty();
        }

        @Override
        public boolean touchLastSeen(UUID uuid) {
            awaitRelease();
            return true;
        }

        private void awaitRelease() {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProfileStorageException("interrupted", e);
            }
        }
    }
}
