package com.inpuzah.club.core.service;

import com.inpuzah.club.core.model.PlayerProfile;
import com.inpuzah.club.core.economy.BalanceSnapshot;
import com.inpuzah.club.core.economy.CurrencyOperation;
import com.inpuzah.club.core.economy.CurrencyOperationResult;
import com.inpuzah.club.core.economy.DefaultEconomyService;
import com.inpuzah.club.core.economy.EconomyRepository;
import com.inpuzah.club.core.progression.DefaultProgressionService;
import com.inpuzah.club.core.progression.ProgressionRepository;
import com.inpuzah.club.core.progression.ProgressionSnapshot;
import com.inpuzah.club.core.progression.XpAwardRequest;
import com.inpuzah.club.core.progression.XpAwardResult;
import com.inpuzah.club.core.repository.ProfileStorageException;
import com.inpuzah.club.core.session.ProfileSessionManager;
import com.inpuzah.club.core.session.SessionState;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileRefreshServiceTest {
    private final UUID id = UUID.randomUUID();
    private final ProfileSessionManager sessions = new ProfileSessionManager();
    private final List<CompletableFuture<Optional<PlayerProfile>>> reads = new ArrayList<>();
    private final ProfileService profiles = new ProfileService() {
        @Override public CompletableFuture<PlayerProfile> getOrCreate(UUID uuid, String username) {
            throw new AssertionError("Refresh must not upsert");
        }
        @Override public CompletableFuture<Optional<PlayerProfile>> findById(UUID uuid) {
            assertEquals(id, uuid);
            var read = new CompletableFuture<Optional<PlayerProfile>>();
            reads.add(read);
            return read;
        }
        @Override public CompletableFuture<Void> touchLastSeen(UUID uuid) { throw new AssertionError(); }
        @Override public int shutdown(Duration timeout) { throw new AssertionError(); }
    };
    private final ProfileRefreshService refreshes = new ProfileRefreshService(
            profiles, sessions, Duration.ofSeconds(1), LoggerFactory.getLogger(getClass()));

    @Test
    void committedRefreshInvalidatesAndReplacesSnapshot() {
        long token = sessions.begin(id).token();
        sessions.markReady(id, token, profile(0));
        refreshes.refresh(id);
        assertEquals(SessionState.LOADING, sessions.get(id).orElseThrow().state());
        reads.getFirst().complete(Optional.of(profile(100)));
        assertEquals(100, sessions.get(id).orElseThrow().profile().coins());
        assertEquals(token, sessions.get(id).orElseThrow().token());
    }

    @Test
    void olderReadCannotOverwriteNewerRefresh() {
        sessions.begin(id);
        refreshes.refresh(id);
        refreshes.refresh(id);
        reads.get(1).complete(Optional.of(profile(200)));
        reads.get(0).complete(Optional.of(profile(100)));
        assertEquals(200, sessions.get(id).orElseThrow().profile().coins());
    }

    @Test
    void olderFailureCannotInvalidateNewerReadySnapshot() {
        sessions.begin(id);
        refreshes.refresh(id);
        refreshes.refresh(id);
        reads.get(1).complete(Optional.of(profile(200)));
        reads.get(0).completeExceptionally(new ProfileStorageException("outage", null));
        assertEquals(SessionState.READY, sessions.get(id).orElseThrow().state());
    }

    @Test
    void initialJoinResultCannotOverwriteRefresh() {
        long token = sessions.begin(id).token();
        refreshes.refresh(id);
        assertFalse(sessions.markReady(id, token, profile(0)));
        assertFalse(sessions.markUnavailable(id, token));
        reads.getFirst().complete(Optional.of(profile(100)));
        assertEquals(100, sessions.get(id).orElseThrow().profile().coins());
    }

    @Test
    void quitAndRejoinRejectOldReadEvenWithSameRevision() {
        sessions.begin(id);
        refreshes.refresh(id);
        sessions.close(id);
        sessions.begin(id);
        refreshes.refresh(id);
        reads.get(0).complete(Optional.of(profile(100)));
        assertEquals(SessionState.LOADING, sessions.get(id).orElseThrow().state());
        reads.get(1).complete(Optional.of(profile(200)));
        assertEquals(200, sessions.get(id).orElseThrow().profile().coins());
    }

    @Test
    void missingProfileAndOutageInvalidateInsteadOfShowingStaleTotals() {
        sessions.begin(id);
        refreshes.refresh(id);
        reads.getFirst().complete(Optional.empty());
        assertEquals(SessionState.UNAVAILABLE, sessions.get(id).orElseThrow().state());
        refreshes.refresh(id);
        reads.get(1).completeExceptionally(new ProfileStorageException("outage", null));
        assertEquals(SessionState.UNAVAILABLE, sessions.get(id).orElseThrow().state());
    }

    @Test
    void timedOutReadCannotPublishItsLateResult() throws Exception {
        sessions.begin(id);
        refreshes.refresh(id).get(5, TimeUnit.SECONDS);
        assertEquals(SessionState.UNAVAILABLE, sessions.get(id).orElseThrow().state());
        assertFalse(reads.getFirst().isDone());
        reads.getFirst().complete(Optional.of(profile(100)));
        assertEquals(SessionState.UNAVAILABLE, sessions.get(id).orElseThrow().state());
    }

    @Test
    void offlineAndShutdownNeverStartReads() {
        refreshes.refresh(id);
        assertTrue(reads.isEmpty());
        sessions.begin(id);
        refreshes.close();
        refreshes.refresh(id);
        assertTrue(reads.isEmpty());
    }

    @Test
    void uncertainStorageOutcomesInvalidateBothServiceCachesWithoutHidingFailure() throws Exception {
        var storageError = new ProfileStorageException("commit acknowledgement lost", null);
        EconomyRepository coins = new EconomyRepository() {
            @Override public BalanceSnapshot balance(UUID uuid) { throw new AssertionError(); }
            @Override public CurrencyOperationResult apply(CurrencyOperation operation) { throw storageError; }
        };
        ProgressionRepository xp = new ProgressionRepository() {
            @Override public ProgressionSnapshot getProgress(UUID uuid) { throw new AssertionError(); }
            @Override public XpAwardResult awardXp(XpAwardRequest request) { throw storageError; }
        };
        var economy = DefaultEconomyService.create(coins, 1, 10, uuid -> refreshes.refresh(uuid));
        var progression = DefaultProgressionService.create(xp, 1, 10, uuid -> refreshes.refresh(uuid));
        sessions.begin(id);
        try {
            var credit = economy.credit(UUID.randomUUID(), id, 100, "test", "grant", null);
            assertInstanceOf(ProfileStorageException.class,
                    assertThrows(ExecutionException.class, () -> credit.get(5, TimeUnit.SECONDS)).getCause());
            assertEquals(1, reads.size());
            reads.getFirst().complete(Optional.of(profile(100)));
            assertEquals(100, sessions.get(id).orElseThrow().profile().coins());

            var award = progression.awardXp(UUID.randomUUID(), id, 100, "test", "grant", null);
            assertInstanceOf(ProfileStorageException.class,
                    assertThrows(ExecutionException.class, () -> award.get(5, TimeUnit.SECONDS)).getCause());
            assertEquals(SessionState.LOADING, sessions.get(id).orElseThrow().state());
            assertEquals(2, reads.size());
            reads.getLast().completeExceptionally(storageError);
            assertEquals(SessionState.UNAVAILABLE, sessions.get(id).orElseThrow().state());
        } finally {
            economy.shutdown(Duration.ofSeconds(5));
            progression.shutdown(Duration.ofSeconds(5));
        }
    }

    private PlayerProfile profile(long coins) {
        return new PlayerProfile(id, "Player", Instant.EPOCH, Instant.EPOCH, 1, 0, coins);
    }
}
