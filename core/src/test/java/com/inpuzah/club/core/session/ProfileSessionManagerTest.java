package com.inpuzah.club.core.session;

import com.inpuzah.club.core.model.PlayerProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileSessionManagerTest {

    private static final UUID ID = UUID.randomUUID();
    private static final PlayerProfile PROFILE = PlayerProfile.newProfile(ID, "Player", Instant.EPOCH);

    private final ProfileSessionManager sessions = new ProfileSessionManager();

    @Test
    void beginStartsLoading() {
        ProfileSession session = sessions.begin(ID);

        assertEquals(SessionState.LOADING, session.state());
        assertEquals(SessionState.LOADING, sessions.get(ID).orElseThrow().state());
    }

    @Test
    void markReadyWithCurrentTokenStoresProfile() {
        long token = sessions.begin(ID).token();

        assertTrue(sessions.markReady(ID, token, PROFILE));
        ProfileSession session = sessions.get(ID).orElseThrow();
        assertEquals(SessionState.READY, session.state());
        assertEquals(PROFILE, session.profile());
    }

    @Test
    void staleTokenFromPreviousSessionIsRejected() {
        long oldToken = sessions.begin(ID).token();
        sessions.close(ID);
        long newToken = sessions.begin(ID).token();

        assertNotEquals(oldToken, newToken);
        assertFalse(sessions.markReady(ID, oldToken, PROFILE));
        assertFalse(sessions.markUnavailable(ID, oldToken));
        assertEquals(SessionState.LOADING, sessions.get(ID).orElseThrow().state());
    }

    @Test
    void resultAfterQuitIsDropped() {
        long token = sessions.begin(ID).token();
        sessions.close(ID);

        assertFalse(sessions.markReady(ID, token, PROFILE));
        assertTrue(sessions.get(ID).isEmpty());
    }

    @Test
    void markUnavailable() {
        long token = sessions.begin(ID).token();

        assertTrue(sessions.markUnavailable(ID, token));
        assertEquals(SessionState.UNAVAILABLE, sessions.get(ID).orElseThrow().state());
        assertTrue(sessions.get(ID).orElseThrow().readyProfile().isEmpty());
    }

    @Test
    void onlyLoadingSessionsCanTransition() {
        long token = sessions.begin(ID).token();
        sessions.markReady(ID, token, PROFILE);

        assertFalse(sessions.markUnavailable(ID, token));
        assertEquals(SessionState.READY, sessions.get(ID).orElseThrow().state());
    }

    @Test
    void rejectsProfileForDifferentPlayer() {
        long token = sessions.begin(ID).token();
        PlayerProfile other = PlayerProfile.newProfile(UUID.randomUUID(), "Other", Instant.EPOCH);

        assertThrows(IllegalArgumentException.class, () -> sessions.markReady(ID, token, other));
    }

    @Test
    void closeReturnsClosedSnapshot() {
        sessions.begin(ID);

        assertEquals(SessionState.CLOSED, sessions.close(ID).orElseThrow().state());
        assertTrue(sessions.close(ID).isEmpty());
    }

    @Test
    void closeAllRemovesEverySession() {
        sessions.begin(ID);
        sessions.begin(UUID.randomUUID());

        assertEquals(2, sessions.closeAll().size());
        assertTrue(sessions.get(ID).isEmpty());
    }
}
