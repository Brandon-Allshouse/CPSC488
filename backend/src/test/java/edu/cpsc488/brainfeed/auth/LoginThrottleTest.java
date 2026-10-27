package edu.cpsc488.brainfeed.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginThrottleTest {

    private static final String VICTIM = "test@sru.edu";
    private static final String ATTACKER_IP = "203.0.113.7";
    private static final String VICTIM_IP = "198.51.100.20";

    // Small numbers so the tests stay short: 3 per IP, 3 per account from one IP, 6 per account.
    private static LoginThrottle throttle() {
        return new LoginThrottle(3, 3, 6, Duration.ofMinutes(15));
    }

    private static void fail(LoginThrottle throttle, String ip, String email, int times) {
        for (int i = 0; i < times; i++) {
            throttle.recordFailure(ip, email);
        }
    }

    @Test
    void blocksAnIpThatKeepsGuessingOneAccount() {
        LoginThrottle throttle = throttle();
        fail(throttle, ATTACKER_IP, VICTIM, 3);
        assertTrue(throttle.isBlocked(ATTACKER_IP, VICTIM));
    }

    @Test
    void someoneGuessingYourPasswordDoesNotLockYouOut() {
        // this was the bug: wrong passwords from one computer used to block the account everywhere
        LoginThrottle throttle = throttle();
        fail(throttle, ATTACKER_IP, VICTIM, 3);
        assertFalse(throttle.isBlocked(VICTIM_IP, VICTIM));
    }

    @Test
    void blocksAnIpTryingLotsOfDifferentAccounts() {
        LoginThrottle throttle = throttle();
        throttle.recordFailure(ATTACKER_IP, "test@sru.edu");
        throttle.recordFailure(ATTACKER_IP, "other@sru.edu");
        throttle.recordFailure(ATTACKER_IP, "third@sru.edu");
        assertTrue(throttle.isBlocked(ATTACKER_IP, "fourth@sru.edu"));
    }

    @Test
    void stillBlocksAnAccountUnderAttackFromManyIps() {
        LoginThrottle throttle = throttle();
        fail(throttle, "203.0.113.1", VICTIM, 2);
        fail(throttle, "203.0.113.2", VICTIM, 2);
        assertFalse(throttle.isBlocked(VICTIM_IP, VICTIM));

        fail(throttle, "203.0.113.3", VICTIM, 2);
        // 6 failures overall, so now the account is blocked from everywhere
        assertTrue(throttle.isBlocked(VICTIM_IP, VICTIM));
        // other accounts are fine
        assertFalse(throttle.isBlocked(VICTIM_IP, "other@sru.edu"));
    }

    @Test
    void theRightPasswordClearsTheAccountButNotTheIp() {
        LoginThrottle throttle = throttle();
        fail(throttle, ATTACKER_IP, "other@sru.edu", 2);
        fail(throttle, ATTACKER_IP, VICTIM, 1);
        assertTrue(throttle.isBlocked(ATTACKER_IP, "third@sru.edu"));

        // logging into their own account doesn't give the attacker more guesses
        throttle.recordSuccess(ATTACKER_IP, VICTIM);
        assertTrue(throttle.isBlocked(ATTACKER_IP, VICTIM));
    }

    @Test
    void theRightPasswordResetsTheAccountCount() {
        LoginThrottle throttle = throttle();
        fail(throttle, "203.0.113.1", VICTIM, 2);
        fail(throttle, "203.0.113.2", VICTIM, 2);
        fail(throttle, VICTIM_IP, VICTIM, 1);

        throttle.recordSuccess(VICTIM_IP, VICTIM);
        // 1 more from a new IP would have made 6 without the reset
        throttle.recordFailure("203.0.113.3", VICTIM);
        assertFalse(throttle.isBlocked("203.0.113.4", VICTIM));
    }

    @Test
    void junkInputOnlyCountsAgainstTheIp() {
        LoginThrottle throttle = throttle();
        throttle.recordJunk(ATTACKER_IP);
        throttle.recordJunk(ATTACKER_IP);
        throttle.recordJunk(ATTACKER_IP);
        assertTrue(throttle.isBlocked(ATTACKER_IP, VICTIM));
        assertFalse(throttle.isBlocked(VICTIM_IP, VICTIM));
    }

    @Test
    void theAppUsesFifteenMinutes() {
        assertEquals(Duration.ofMinutes(15), new LoginThrottle().window());
    }
}
