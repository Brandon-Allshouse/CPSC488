package edu.cpsc488.brainfeed.auth;

import edu.cpsc488.brainfeed.db.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Runs against the real test database (see TestDatabase). Checks the NIST SP 800-63B session rules.
class SessionRepositoryTest {

    SessionRepository sessions;
    long user;

    @BeforeEach
    void setUp() {
        sessions = new SessionRepository(TestDatabase.get(), Duration.ofDays(7), Duration.ofHours(24));
        TestDatabase.reset();
        user = TestDatabase.addUser("testuser");
    }

    @Test
    void aNewTokenFindsItsUser() {
        String token = sessions.create(user);
        assertEquals(Optional.of(user), sessions.findUserId(token));
        assertTrue(sessions.findUserId("not-a-real-token").isEmpty());
    }

    @Test
    void onlyTheHashIsStored() {
        String token = sessions.create(user);
        String stored = TestDatabase.text("SELECT token_hash FROM sessions WHERE user_id = ?", user);
        assertNotEquals(token, stored);
        assertTrue(stored.matches("[0-9a-f]{64}"));
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM sessions WHERE token_hash = ?", token));
    }

    @Test
    void tokensAreLongAndDifferentEveryTime() {
        String first = sessions.create(user);
        String second = sessions.create(user);
        assertNotEquals(first, second);
        // 256 bits, base64url without padding
        assertEquals(43, first.length());
    }

    @Test
    void anIdleSessionStopsWorking() {
        String token = sessions.create(user);
        TestDatabase.exec("UPDATE sessions SET last_seen_at = now() - interval '25 hours'");
        assertTrue(sessions.findUserId(token).isEmpty());
    }

    @Test
    void usingASessionKeepsItAlive() {
        String token = sessions.create(user);
        TestDatabase.exec("UPDATE sessions SET last_seen_at = now() - interval '23 hours'");
        assertTrue(sessions.findUserId(token).isPresent());
        assertEquals(1, TestDatabase.number("SELECT count(*) FROM sessions WHERE last_seen_at > now() - interval '1 minute'"));
    }

    @Test
    void aSessionPastItsAbsoluteLifetimeStopsWorkingEvenIfUsed() {
        String token = sessions.create(user);
        TestDatabase.exec("UPDATE sessions SET expires_at = now() - interval '1 second'");
        assertTrue(sessions.findUserId(token).isEmpty());
    }

    @Test
    void logoutDeletesTheSession() {
        String token = sessions.create(user);
        sessions.delete(token);
        assertTrue(sessions.findUserId(token).isEmpty());
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM sessions"));
    }

    @Test
    void cleanupRemovesOnlyDeadSessions() {
        sessions.create(user);
        TestDatabase.exec("UPDATE sessions SET expires_at = now() - interval '1 second'");
        String alive = sessions.create(user);
        sessions.deleteExpired();
        assertEquals(1, TestDatabase.number("SELECT count(*) FROM sessions"));
        assertTrue(sessions.findUserId(alive).isPresent());
    }

    @Test
    void deletingTheUserDeletesTheirSessions() {
        sessions.create(user);
        TestDatabase.exec("DELETE FROM users WHERE id = ?", user);
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM sessions"));
    }
}
