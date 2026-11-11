package edu.cpsc488.brainfeed.history;

import edu.cpsc488.brainfeed.ApiServer;
import edu.cpsc488.brainfeed.auth.AuthController;
import edu.cpsc488.brainfeed.auth.PasswordHasher;
import edu.cpsc488.brainfeed.auth.PasswordPolicy;
import edu.cpsc488.brainfeed.auth.SessionRepository;
import edu.cpsc488.brainfeed.auth.UserRepository;
import edu.cpsc488.brainfeed.db.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

// The watch history endpoints over real HTTP, with real logins, against the test database.
class WatchHistoryControllerTest {

    ApiServer server;

    @BeforeEach
    void setUp() {
        TestDatabase.reset();
        var data = TestDatabase.get();
        AuthController auth = new AuthController(new UserRepository(data),
                new SessionRepository(data, Duration.ofDays(7), Duration.ofHours(24)),
                new PasswordHasher(new byte[32], 2), new PasswordPolicy(password -> false), false);
        WatchHistoryController history = new WatchHistoryController(
                new WatchHistoryRepository(data, WatchHistoryController.LIMIT), auth);
        server = new ApiServer(routes -> {
            auth.register(routes);
            history.register(routes);
        });
        int math = TestDatabase.topicId("Math");
        TestDatabase.addVideo("video000001", math);
        TestDatabase.addVideo("video000002", math);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    void signUp(String username) {
        server.clearCookies();
        server.send("POST", "/api/auth/register", "{\"email\":\"" + username + "@sru.edu\",\"username\":\"" + username
                + "\",\"password\":\"simple test password\"}");
    }

    @Test
    void keepsTheLast25Videos() {
        // the requirement for the Watch history page; the README and SECURITY.md say 25 too
        assertEquals(25, WatchHistoryController.LIMIT);
    }

    @Test
    void guestsGet401Everywhere() {
        assertEquals(401, server.get("/api/me/watch-history").statusCode());
        assertEquals(401, server.send("PUT", "/api/me/watch-history/video000001", null).statusCode());
        assertEquals(401, server.send("DELETE", "/api/me/watch-history/video000001", null).statusCode());
        assertEquals(401, server.send("DELETE", "/api/me/watch-history", null).statusCode());
    }

    @Test
    void recordListRemoveAndClear() {
        signUp("testuser");
        assertEquals(204, server.send("PUT", "/api/me/watch-history/video000001", null).statusCode());
        assertEquals(204, server.send("PUT", "/api/me/watch-history/video000002", null).statusCode());
        var list = server.json(server.get("/api/me/watch-history"));
        assertEquals(2, list.get("videos").size());
        assertEquals(25, list.get("limit").asInt());
        assertEquals(204, server.send("DELETE", "/api/me/watch-history/video000001", null).statusCode());
        assertEquals(404, server.send("DELETE", "/api/me/watch-history/video000001", null).statusCode());
        assertEquals(204, server.send("DELETE", "/api/me/watch-history", null).statusCode());
        assertEquals(0, server.json(server.get("/api/me/watch-history")).get("videos").size());
    }

    @Test
    void badAndUnknownVideoIds() {
        signUp("testuser");
        assertEquals(400, server.send("PUT", "/api/me/watch-history/not-an-id", null).statusCode());
        assertEquals(404, server.send("PUT", "/api/me/watch-history/unknown0001", null).statusCode());
    }

    @Test
    void historyIsPrivate() {
        signUp("testuser");
        server.send("PUT", "/api/me/watch-history/video000001", null);
        signUp("otheruser");
        assertEquals(0, server.json(server.get("/api/me/watch-history")).get("videos").size());
        // and clearing theirs leaves mine alone
        server.send("DELETE", "/api/me/watch-history", null);
        assertEquals(1, TestDatabase.number("SELECT count(*) FROM watch_history"));
    }
}
