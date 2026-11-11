package edu.cpsc488.brainfeed.auth;

import edu.cpsc488.brainfeed.ApiServer;
import edu.cpsc488.brainfeed.db.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Sign up, log in and out over real HTTP against the real test database (SECURITY.md, NIST SP 800-63B).
class AuthApiTest {

    static final String PASSWORD = "quiet lemon river walk";
    static final String REGISTER = "{\"email\":\"test@sru.edu\",\"username\":\"testuser\",\"password\":\"" + PASSWORD + "\"}";
    static final String LOGIN = "{\"email\":\"test@sru.edu\",\"password\":\"" + PASSWORD + "\"}";
    static final String WRONG = "{\"email\":\"test@sru.edu\",\"password\":\"not the right password\"}";

    ApiServer server;

    static AuthController auth(boolean https) {
        var data = TestDatabase.get();
        // breach check off: tests shouldn't call Have I Been Pwned
        return new AuthController(new UserRepository(data),
                new SessionRepository(data, Duration.ofDays(7), Duration.ofHours(24)),
                new PasswordHasher(new byte[32], 2), new PasswordPolicy(password -> false), https);
    }

    @BeforeEach
    void setUp() {
        TestDatabase.reset();
        AuthController auth = auth(false);
        server = new ApiServer(auth::register);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    String sessionCookie(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("brainfeed_session=") || c.startsWith("__Host-brainfeed_session="))
                .findFirst().orElse("");
    }

    @Test
    void signUpLogsYouInWithAHardenedCookie() {
        HttpResponse<String> response = server.send("POST", "/api/auth/register", REGISTER);
        assertEquals(201, response.statusCode());
        assertEquals("testuser", server.json(response).get("user").get("username").asText());
        String cookie = sessionCookie(response);
        assertTrue(cookie.contains("HttpOnly"), cookie);
        assertTrue(cookie.contains("SameSite=Strict"), cookie);
        assertTrue(cookie.contains("Path=/"), cookie);
        assertEquals(200, server.get("/api/auth/me").statusCode());
    }

    @Test
    void theStoredPasswordIsAnArgon2idHash() {
        server.send("POST", "/api/auth/register", REGISTER);
        String hash = TestDatabase.text("SELECT password_hash FROM users");
        assertTrue(hash.startsWith("$argon2id$"));
        assertFalse(hash.contains(PASSWORD));
    }

    @Test
    void responsesNeverIncludeThePasswordHashOrIps() {
        HttpResponse<String> response = server.send("POST", "/api/auth/register", REGISTER);
        assertFalse(response.body().contains("argon2"));
        assertFalse(response.body().toLowerCase().contains("ip"));
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameMessage() {
        server.send("POST", "/api/auth/register", REGISTER);
        server.clearCookies();
        HttpResponse<String> wrong = server.send("POST", "/api/auth/login", WRONG);
        HttpResponse<String> unknown = server.send("POST", "/api/auth/login",
                "{\"email\":\"nobody@sru.edu\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(401, wrong.statusCode());
        assertEquals(wrong.statusCode(), unknown.statusCode());
        assertEquals(wrong.body(), unknown.body());
    }

    @Test
    void everyLoginGetsANewSessionToken() {
        String first = sessionCookie(server.send("POST", "/api/auth/register", REGISTER));
        String second = sessionCookie(server.send("POST", "/api/auth/login", LOGIN));
        assertNotEquals(first, second);
        // and the old session is gone (prevents session fixation)
        assertEquals(1, TestDatabase.number("SELECT count(*) FROM sessions"));
    }

    @Test
    void logoutEndsTheSessionOnTheServer() {
        server.send("POST", "/api/auth/register", REGISTER);
        assertEquals(204, server.send("POST", "/api/auth/logout", null).statusCode());
        assertEquals(401, server.get("/api/auth/me").statusCode());
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM sessions"));
    }

    @Test
    void tooManyWrongPasswordsFromOneIpAreThrottled() {
        server.send("POST", "/api/auth/register", REGISTER);
        server.clearCookies();
        for (int i = 0; i < 10; i++) {
            assertEquals(401, server.send("POST", "/api/auth/login", WRONG).statusCode());
        }
        assertEquals(429, server.send("POST", "/api/auth/login", WRONG).statusCode());
        // even the right password waits now, so guessing can't continue
        assertEquals(429, server.send("POST", "/api/auth/login", LOGIN).statusCode());
    }

    @Test
    void aBannedAccountIsOnlyToldAfterTheRightPassword() {
        server.send("POST", "/api/auth/register", REGISTER);
        server.clearCookies();
        TestDatabase.exec("UPDATE users SET banned_at = now(), ban_reason = 'spam'");
        HttpResponse<String> wrong = server.send("POST", "/api/auth/login", WRONG);
        assertEquals(401, wrong.statusCode());
        assertFalse(wrong.body().toLowerCase().contains("suspended"));
        assertEquals(403, server.send("POST", "/api/auth/login", LOGIN).statusCode());
    }

    @Test
    void anOldCookieStopsWorkingOnceBanned() {
        server.send("POST", "/api/auth/register", REGISTER);
        TestDatabase.exec("UPDATE users SET banned_at = now(), ban_reason = 'spam'");
        assertEquals(401, server.get("/api/auth/me").statusCode());
    }

    @Test
    void shortPasswordsAreRefused() {
        HttpResponse<String> response = server.send("POST", "/api/auth/register",
                "{\"email\":\"test@sru.edu\",\"username\":\"testuser\",\"password\":\"too short\"}");
        assertEquals(400, response.statusCode());
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM users"));
    }

    @Test
    void passwordsWithYourEmailOrUsernameInThemAreRefused() {
        // NIST SP 800-63B: block context-specific passwords
        HttpResponse<String> response = server.send("POST", "/api/auth/register",
                "{\"email\":\"test@sru.edu\",\"username\":\"testuser\",\"password\":\"simple test password\"}");
        assertEquals(400, response.statusCode());
    }

    @Test
    void takenEmailsAndUsernamesAre409() {
        server.send("POST", "/api/auth/register", REGISTER);
        server.clearCookies();
        assertEquals(409, server.send("POST", "/api/auth/register", REGISTER).statusCode());
    }

    @Test
    void behindHttpsTheCookieIsSecureAndHostPrefixed() {
        server.close();
        server = new ApiServer(true, auth(true)::register);
        String cookie = sessionCookie(server.send("POST", "/api/auth/register", REGISTER));
        assertTrue(cookie.startsWith("__Host-brainfeed_session="), cookie);
        assertTrue(cookie.contains("Secure"), cookie);
    }

    @Test
    void aMadeUpCookieIsJustNotLoggedIn() {
        HttpResponse<String> response = server.send(server.builder("/api/auth/me")
                .header("Cookie", "brainfeed_session=" + "a".repeat(43)).GET());
        assertEquals(401, response.statusCode());
    }
}
