package edu.cpsc488.brainfeed.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginRateLimiterTest {

    @Test
    void locksOutAfterThreeFailedLogins() {
        LoginRateLimiter limiter = new LoginRateLimiter(3, Duration.ofMinutes(15));
        String key = "email:test@sru.edu";

        for (int i = 0; i < 3; i++) {
            assertFalse(limiter.isLimited(key));
            limiter.record(key);
        }
        assertTrue(limiter.isLimited(key));

        // someone else shouldn't get locked out because of this one
        assertFalse(limiter.isLimited("email:other@sru.edu"));
    }

    @Test
    void successfulLoginResetsTheCount() {
        LoginRateLimiter limiter = new LoginRateLimiter(1, Duration.ofMinutes(15));
        limiter.record("testuser");
        assertTrue(limiter.isLimited("testuser"));

        limiter.reset("testuser");
        assertFalse(limiter.isLimited("testuser"));
    }

    @Test
    void lockoutWearsOffAfterTheWindow() throws InterruptedException {
        // tiny window so the test doesn't have to wait 15 minutes
        LoginRateLimiter limiter = new LoginRateLimiter(1, Duration.ofMillis(50));
        limiter.record("testuser");
        assertTrue(limiter.isLimited("testuser"));

        Thread.sleep(80);
        assertFalse(limiter.isLimited("testuser"));
    }

    @Test
    void newlinesCantBeUsedToFakeLogEntries() {
        // if an attacker puts \n in a field they could add a made up line to our log
        assertEquals("testuser_LOGIN_SUCCESS user=admin", SecurityLog.clean("testuser\nLOGIN_SUCCESS user=admin"));
        assertEquals("-", SecurityLog.clean(null));
    }
}
