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
    void windowIsWhatWePassedIn() {
        LoginRateLimiter limiter = new LoginRateLimiter(10, Duration.ofMinutes(15));
        assertEquals(Duration.ofMinutes(15), limiter.window());
    }

    @Test
    void lotsOfDifferentKeysStillWork() {
        // goes past the cleanup threshold to make sure cleanup doesn't break anything
        LoginRateLimiter limiter = new LoginRateLimiter(1, Duration.ofMinutes(15));
        for (int i = 0; i < 10_050; i++) {
            limiter.record("email:user" + i + "@sru.edu");
        }
        assertTrue(limiter.isLimited("email:user10049@sru.edu"));
        assertFalse(limiter.isLimited("email:test@sru.edu"));
    }
}
