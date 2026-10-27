package edu.cpsc488.brainfeed.auth;

import java.time.Duration;

/**
 * Decides when failed logins block further attempts (NIST SP 800-63B §3.2.2, OWASP Authentication
 * Cheat Sheet). Three counters, all over the same window:
 * <ul>
 *   <li><b>Per IP</b> (strict): one computer can't guess passwords for lots of accounts.</li>
 *   <li><b>Per account from one IP</b> (strict): one computer can't keep guessing one account.</li>
 *   <li><b>Per account from anywhere</b> (loose, NIST's cap of 100): stops a guessing attack spread
 *       over many IPs.</li>
 * </ul>
 * There used to be one strict per-account counter, which let anyone who knew your email lock you
 * out indefinitely with 10 wrong passwords every 15 minutes. Now the strict account counter only
 * blocks the IP doing the guessing; locking you out everywhere takes 100 failures, which one IP
 * can't produce (it's blocked at 10).
 */
public class LoginThrottle {

    private final LoginRateLimiter perIp;
    private final LoginRateLimiter perAccountFromIp;
    private final LoginRateLimiter perAccount;
    private final Duration window;

    /** The limits used by the app: 10 / 10 / 100 failures per 15 minutes. */
    public LoginThrottle() {
        this(10, 10, 100, Duration.ofMinutes(15));
    }

    LoginThrottle(int perIp, int perAccountFromIp, int perAccount, Duration window) {
        this.perIp = new LoginRateLimiter(perIp, window);
        this.perAccountFromIp = new LoginRateLimiter(perAccountFromIp, window);
        this.perAccount = new LoginRateLimiter(perAccount, window);
        this.window = window;
    }

    public Duration window() {
        return window;
    }

    /** True if a login for this email from this IP shouldn't even be checked right now. */
    public boolean isBlocked(String ip, String email) {
        return perIp.isLimited(ip) || perAccountFromIp.isLimited(pair(ip, email)) || perAccount.isLimited(email);
    }

    /** A wrong password (or unknown email). */
    public void recordFailure(String ip, String email) {
        perIp.record(ip);
        perAccountFromIp.record(pair(ip, email));
        perAccount.record(email);
    }

    /** Input too big to be a real login: counts against the IP only, since it can't be anyone's account. */
    public void recordJunk(String ip) {
        perIp.record(ip);
    }

    /**
     * The right password clears that account's counters. The IP's count isn't cleared, so an
     * attacker can't reset their limit by logging into their own account between guesses.
     */
    public void recordSuccess(String ip, String email) {
        perAccountFromIp.reset(pair(ip, email));
        perAccount.reset(email);
    }

    // IPs never contain a space (ClientIp only returns parsed addresses), so this can't make two
    // different ip/email pairs look the same.
    private static String pair(String ip, String email) {
        return ip + " " + email;
    }
}
