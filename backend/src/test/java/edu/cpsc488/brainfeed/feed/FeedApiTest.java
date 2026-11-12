package edu.cpsc488.brainfeed.feed;

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

import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

// The feed, interests and feedback endpoints over real HTTP, so bad input is checked to be a 400
// (never a 500) all the way through, not just in the helper methods.
class FeedApiTest {

    static final String REGISTER = "{\"email\":\"test@sru.edu\",\"username\":\"testuser\",\"password\":\"quiet lemon river walk\"}";

    ApiServer server;
    int math;

    @BeforeEach
    void setUp() {
        TestDatabase.reset();
        var data = TestDatabase.get();
        // breach check off: tests shouldn't call Have I Been Pwned
        AuthController auth = new AuthController(new UserRepository(data),
                new SessionRepository(data, Duration.ofDays(7), Duration.ofHours(24)),
                new PasswordHasher(new byte[32], 2), new PasswordPolicy(password -> false), false);
        FeedController feed = new FeedController(new TopicRepository(data), new VideoRepository(data), auth,
                topicIds -> false);
        server = new ApiServer(routes -> {
            auth.register(routes);
            feed.register(routes);
        });
        math = TestDatabase.topicId("Math");
        TestDatabase.addVideo("video000001", math);
        server.send("POST", "/api/auth/register", REGISTER);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void savingAndReadingInterests() {
        HttpResponse<String> saved = server.send("PUT", "/api/me/interests", "{\"topicIds\":[" + math + "," + math + "]}");
        assertEquals(200, saved.statusCode());
        assertEquals("[" + math + "]", server.json(saved).get("topicIds").toString());
        assertEquals("[" + math + "]", server.json(server.get("/api/me/interests")).get("topicIds").toString());
    }

    @Test
    void badInterestsAre400() {
        assertEquals(400, server.send("PUT", "/api/me/interests", "{\"topicIds\":[999999]}").statusCode());
        assertEquals(400, server.send("PUT", "/api/me/interests", "{\"topicIds\":[]}").statusCode());
        assertEquals(400, server.send("PUT", "/api/me/interests", "{\"topicIds\":[null]}").statusCode());
        assertEquals(400, server.send("PUT", "/api/me/interests", "{\"topicIds\":\"math\"}").statusCode());
        assertEquals(400, server.send("PUT", "/api/me/interests", "{\"topicIds\":[99999999999]}").statusCode());
        // a user id in the body is refused, not used
        assertEquals(400, server.send("PUT", "/api/me/interests", "{\"topicIds\":[" + math + "],\"userId\":1}").statusCode());
    }

    @Test
    void theFeedServesVideosAndRejectsBadQueries() {
        HttpResponse<String> page = server.get("/api/feed?topics=" + math + "&seed=7&page=0");
        assertEquals(200, page.statusCode());
        assertEquals("video000001", server.json(page).get("videos").get(0).get("youtubeId").asText());
        assertEquals(400, server.get("/api/feed").statusCode());
        assertEquals(400, server.get("/api/feed?topics=abc").statusCode());
        assertEquals(400, server.get("/api/feed?topics=" + math + "&page=-1").statusCode());
        assertEquals(400, server.get("/api/feed?topics=999999").statusCode());
    }

    @Test
    void likingAndUnliking() {
        assertEquals(204, server.send("PUT", "/api/me/video-feedback/video000001", "{\"feedback\":\"liked\"}").statusCode());
        HttpResponse<String> lists = server.get("/api/me/video-feedback");
        assertEquals("video000001", server.json(lists).get("likedVideos").get(0).get("youtubeId").asText());
        assertEquals(204, server.send("DELETE", "/api/me/video-feedback/video000001", null).statusCode());
        assertEquals(0, server.json(server.get("/api/me/video-feedback")).get("likedVideos").size());
    }

    @Test
    void badFeedbackIs400() {
        assertEquals(400, server.send("PUT", "/api/me/video-feedback/video000001", "{\"feedback\":\"love\"}").statusCode());
        assertEquals(400, server.send("PUT", "/api/me/video-feedback/not-an-id", "{\"feedback\":\"liked\"}").statusCode());
        assertEquals(400, server.send("PUT", "/api/me/video-feedback/video000001", "{}").statusCode());
        // a video we don't have can't be liked
        HttpResponse<String> unknown = server.send("PUT", "/api/me/video-feedback/video000009", "{\"feedback\":\"liked\"}");
        assertEquals(400, unknown.statusCode());
        assertFalse(unknown.body().contains("SQL"));
    }
}
