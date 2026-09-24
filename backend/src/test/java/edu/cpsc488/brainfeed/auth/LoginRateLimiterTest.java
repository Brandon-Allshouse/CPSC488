package edu.cpsc488.brainfeed.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginRateLimiterTest {

    @Test
    void blocksAfterMaxAttempts() {
        LoginRateLimiter limiter = new LoginRateLimiter(3, Duration.ofMinutes(15));
        for (int i = 0; i < 3; i++) {
            assertFalse(limiter.isLimited("email:a@b.co"));
            limiter.record("email:a@b.co");
        }
        assertTrue(limiter.isLimited("email:a@b.co"));
        assertFalse(limiter.isLimited("email:other@b.co"));
    }

    @Test
    void resetClearsAttempts() {
        LoginRateLimiter limiter = new LoginRateLimiter(1, Duration.ofMinutes(15));
        limiter.record("k");
        assertTrue(limiter.isLimited("k"));
        limiter.reset("k");
        assertFalse(limiter.isLimited("k"));
    }

    @Test
    void attemptsExpireAfterWindow() throws InterruptedException {
        LoginRateLimiter limiter = new LoginRateLimiter(1, Duration.ofMillis(50));
        limiter.record("k");
        assertTrue(limiter.isLimited("k"));
        Thread.sleep(80);
        assertFalse(limiter.isLimited("k"));
    }

    @Test
    void securityLogStripsControlCharacters() {
        assertEquals("evil_fake log line", SecurityLog.clean("evil\nfake log line"));
        assertEquals("-", SecurityLog.clean(null));
    }
}
