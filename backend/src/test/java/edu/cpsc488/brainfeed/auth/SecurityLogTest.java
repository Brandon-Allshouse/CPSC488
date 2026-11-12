package edu.cpsc488.brainfeed.auth;

import io.javalin.http.Context;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityLogTest {

    // A stand-in request: only ip() and userAgent() are used by SecurityLog.
    private static Context request(String ip, String userAgent) {
        return (Context) Proxy.newProxyInstance(
                Context.class.getClassLoader(),
                new Class<?>[] {Context.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "ip" -> ip;
                    case "userAgent" -> userAgent;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    // slf4j-simple writes to System.err, so swap it out while the log call runs.
    private static String captureLog(Runnable logCall) {
        PrintStream original = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setErr(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            logCall.run();
        } finally {
            System.setErr(original);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void newlinesCantBeUsedToFakeLogEntries() {
        // if an attacker puts \n in a field they could add a made up line to our log
        assertEquals("testuser_LOGIN_SUCCESS user=admin", SecurityLog.clean("testuser\nLOGIN_SUCCESS user=admin"));
    }

    @Test
    void quotesAreReplaced() {
        // the user agent is logged inside quotes, so a quote could end it early
        assertEquals("Mozilla _fake_", SecurityLog.clean("Mozilla \"fake\""));
    }

    @Test
    void nullBecomesADash() {
        assertEquals("-", SecurityLog.clean(null));
    }

    @Test
    void reallyLongValuesGetCutOff() {
        assertEquals(200, SecurityLog.clean("a".repeat(500)).length());
    }

    @Test
    void carriageReturnsAndTabsAreReplacedToo() {
        assertEquals("a_b_c", SecurityLog.clean("a\rb\tc"));
    }

    @Test
    void normalTextIsLeftAlone() {
        assertEquals("Mozilla/5.0 (Windows NT 10.0) café", SecurityLog.clean("Mozilla/5.0 (Windows NT 10.0) café"));
        assertEquals(200, SecurityLog.clean("a".repeat(200)).length());
    }

    @Test
    void aFailureIsOneLineWithTheReasonAndIp() {
        String line = captureLog(() -> SecurityLog.failure("login", request("203.0.113.7", "Mozilla\nevent=fake"), 42L,
                "wrong password"));
        assertTrue(line.contains("event=login outcome=failure user=42 ip=203.0.113.7"), line);
        assertTrue(line.contains("reason=\"wrong password\""), line);
        // the forged second line from the user agent stays on the same line
        assertEquals(1, line.strip().lines().count(), line);
    }

    @Test
    void aSuccessWithNoUserOrUserAgentUsesDashes() {
        String line = captureLog(() -> SecurityLog.success("logout", request("203.0.113.7", null), null));
        assertTrue(line.contains("event=logout outcome=success user=- ip=203.0.113.7 ua=\"-\""), line);
    }

    @Test
    void theDetailIsCleanedLikeEverythingElse() {
        String line = captureLog(() -> SecurityLog.success("admin_ban_user", request("203.0.113.7", "test"), 1L,
                "target=5\"\nevent=fake"));
        assertTrue(line.contains("detail=\"target=5__event=fake\""), line);
        assertEquals(1, line.strip().lines().count(), line);
    }
}
