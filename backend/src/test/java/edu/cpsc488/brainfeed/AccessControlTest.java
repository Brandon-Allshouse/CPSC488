package edu.cpsc488.brainfeed;

import edu.cpsc488.brainfeed.admin.AdminController;
import edu.cpsc488.brainfeed.admin.AdminRepository;
import edu.cpsc488.brainfeed.auth.AuthController;
import edu.cpsc488.brainfeed.auth.PasswordHasher;
import edu.cpsc488.brainfeed.auth.PasswordPolicy;
import edu.cpsc488.brainfeed.auth.SessionRepository;
import edu.cpsc488.brainfeed.auth.UserRepository;
import edu.cpsc488.brainfeed.db.TestDatabase;
import edu.cpsc488.brainfeed.feed.FeedController;
import edu.cpsc488.brainfeed.feed.PlaylistController;
import edu.cpsc488.brainfeed.feed.PlaylistRepository;
import edu.cpsc488.brainfeed.feed.TopicRepository;
import edu.cpsc488.brainfeed.feed.VideoRepository;
import edu.cpsc488.brainfeed.history.WatchHistoryController;
import edu.cpsc488.brainfeed.history.WatchHistoryRepository;
import edu.cpsc488.brainfeed.saved.SavedVideoController;
import edu.cpsc488.brainfeed.saved.SavedVideoRepository;
import edu.cpsc488.brainfeed.saved.StudyNoteController;
import edu.cpsc488.brainfeed.saved.StudyNoteRepository;
import edu.cpsc488.brainfeed.topics.AiTopicRepository;
import edu.cpsc488.brainfeed.topics.TopicSuggestionController;
import edu.cpsc488.brainfeed.topics.TopicSuggestions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// OWASP Top 10 A01: every private or admin route, wired like App.java, refuses the wrong person.
// Add new /api/me or /api/admin routes to the lists below.
class AccessControlTest {

    // [method, path, body]
    static final List<String[]> USER_ROUTES = List.of(
            new String[]{"GET", "/api/me/interests", null},
            new String[]{"PUT", "/api/me/interests", "{\"topicIds\":[1]}"},
            new String[]{"GET", "/api/me/video-feedback", null},
            new String[]{"PUT", "/api/me/video-feedback/video000001", "{\"feedback\":\"liked\"}"},
            new String[]{"DELETE", "/api/me/video-feedback/video000001", null},
            new String[]{"GET", "/api/me/playlists", null},
            new String[]{"POST", "/api/me/playlists", "{\"name\":\"Study\"}"},
            new String[]{"DELETE", "/api/me/playlists/1", null},
            new String[]{"PUT", "/api/me/playlists/1/videos/video000001", null},
            new String[]{"DELETE", "/api/me/playlists/1/videos/video000001", null},
            new String[]{"GET", "/api/me/saved-videos", null},
            new String[]{"POST", "/api/me/saved-videos/video000001", null},
            new String[]{"DELETE", "/api/me/saved-videos/video000001", null},
            new String[]{"GET", "/api/me/study-notes", null},
            new String[]{"PUT", "/api/me/study-notes/video000001", "{\"note\":\"x\"}"},
            new String[]{"GET", "/api/me/watch-history", null},
            new String[]{"PUT", "/api/me/watch-history/video000001", null},
            new String[]{"DELETE", "/api/me/watch-history/video000001", null},
            new String[]{"DELETE", "/api/me/watch-history", null},
            new String[]{"POST", "/api/me/topic-suggestions", "{\"text\":\"space\"}"},
            new String[]{"POST", "/api/me/topics", "{\"key\":\"abc\"}"},
            new String[]{"GET", "/api/auth/me", null});

    static final List<String[]> ADMIN_ROUTES = List.of(
            new String[]{"GET", "/api/admin/stats", null},
            new String[]{"GET", "/api/admin/users", null},
            new String[]{"PUT", "/api/admin/users/1/ban", "{\"reason\":\"x\"}"},
            new String[]{"DELETE", "/api/admin/users/1/ban", null},
            new String[]{"GET", "/api/admin/videos", null},
            new String[]{"GET", "/api/admin/banned-videos", null},
            new String[]{"PUT", "/api/admin/banned-videos/video000001", "{\"reason\":\"x\"}"},
            new String[]{"DELETE", "/api/admin/banned-videos/video000001", null},
            new String[]{"GET", "/api/admin/actions", null},
            new String[]{"GET", "/api/admin/security-log", null},
            new String[]{"GET", "/api/admin/topics", null},
            new String[]{"DELETE", "/api/admin/topics/1", "{\"reason\":\"x\"}"});

    ApiServer server;

    @BeforeEach
    void setUp() {
        TestDatabase.reset();
        var data = TestDatabase.get();
        AuthController auth = new AuthController(new UserRepository(data),
                new SessionRepository(data, Duration.ofDays(7), Duration.ofHours(24)),
                new PasswordHasher(new byte[32], 2), new PasswordPolicy(password -> false), false);
        TopicRepository topics = new TopicRepository(data);
        VideoRepository videos = new VideoRepository(data);
        server = new ApiServer(routes -> {
            auth.register(routes);
            new FeedController(topics, videos, auth, ids -> false).register(routes);
            new SavedVideoController(new SavedVideoRepository(data), auth).register(routes);
            new StudyNoteController(new StudyNoteRepository(data), auth).register(routes);
            new AdminController(new AdminRepository(data), auth).register(routes);
            new WatchHistoryController(new WatchHistoryRepository(data, WatchHistoryController.LIMIT), auth).register(routes);
            new PlaylistController(new PlaylistRepository(data), auth).register(routes);
            new TopicSuggestionController(new TopicSuggestions(new AiTopicRepository(data), null), auth).register(routes);
        });
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void guestsGet401OnEveryPrivateAndAdminRoute() {
        for (List<String[]> routes : List.of(USER_ROUTES, ADMIN_ROUTES)) {
            for (String[] route : routes) {
                assertEquals(401, server.send(route[0], route[1], route[2]).statusCode(), route[0] + " " + route[1]);
            }
        }
        // nothing was written along the way
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM admin_actions"));
    }

    @Test
    void signedInNonAdminsGet403OnEveryAdminRoute() {
        server.send("POST", "/api/auth/register",
                "{\"email\":\"test@sru.edu\",\"username\":\"testuser\",\"password\":\"quiet lemon river walk\"}");
        for (String[] route : ADMIN_ROUTES) {
            assertEquals(403, server.send(route[0], route[1], route[2]).statusCode(), route[0] + " " + route[1]);
        }
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM admin_actions"));
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM users WHERE banned_at IS NOT NULL"));
    }

    @Test
    void theOnlyPublicRoutesAreTheOnesMeantToBe() {
        // topics and the feed are public on purpose, so guests can browse (SECURITY.md)
        assertEquals(200, server.get("/api/topics").statusCode());
        assertEquals(200, server.get("/api/feed?topics=1&seed=1&page=0").statusCode());
    }
}
