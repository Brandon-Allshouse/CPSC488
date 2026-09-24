package edu.cpsc488.brainfeed.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts attempts per key (e.g. {@code "ip:1.2.3.4"} or {@code "email:a@b.co"}) over a sliding
 * time window, to slow down password guessing and sign-up spam. Usage: call {@link #isLimited}
 * before the action, then {@link #record} for each attempt that should count (for logins, only
 * failed ones).
 *
 * <p>Counts live in memory, so they reset when the backend restarts and aren't shared if we ever
 * run more than one backend. That's fine for now; see "Known gaps" in SECURITY.md.
 */
public class LoginRateLimiter {

    // An attacker could send attempts for millions of different emails to fill up memory. Once
    // this many keys are tracked, stale ones are swept out before new ones are added.
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final int maxAttempts;
    private final Duration window;
    private final ConcurrentHashMap<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();

    public LoginRateLimiter(int maxAttempts, Duration window) {
        this.maxAttempts = maxAttempts;
        this.window = window;
    }

    public Duration window() {
        return window;
    }

    public boolean isLimited(String key) {
        Deque<Instant> times = attempts.get(key);
        if (times == null) {
            return false;
        }
        synchronized (times) {
            prune(times);
            return times.size() >= maxAttempts;
        }
    }

    public void record(String key) {
        if (attempts.size() > CLEANUP_THRESHOLD) {
            cleanup();
        }
        Deque<Instant> times = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            prune(times);
            times.addLast(Instant.now());
        }
    }

    public void reset(String key) {
        attempts.remove(key);
    }

    /** Drops timestamps older than the window. Callers must hold the deque's lock. */
    private void prune(Deque<Instant> times) {
        Instant cutoff = Instant.now().minus(window);
        while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
            times.removeFirst();
        }
    }

    private void cleanup() {
        attempts.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                prune(entry.getValue());
                return entry.getValue().isEmpty();
            }
        });
    }
}
