package edu.cpsc488.brainfeed.topics;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// POST /api/me/topics over real HTTP, with real logins and the real test database. The model is
// a fake that answers nothing (so reviews don't change anything here; see TopicReviewerTest).
class TopicFinderApiTest {

    ApiServer server;
    final List<Integer> fetched = new CopyOnWriteArrayList<>();
    final TopicReviewerTest.FakeLlm llm = new TopicReviewerTest.FakeLlm();

    @BeforeEach
    void setUp() {
        TestDatabase.reset();
        var data = TestDatabase.get();
        AuthController auth = new AuthController(new UserRepository(data),
                new SessionRepository(data, Duration.ofDays(7), Duration.ofHours(24)),
                new PasswordHasher(new byte[32], 2), new PasswordPolicy(password -> false), false);
        AiTopicRepository repository = new AiTopicRepository(data);
        // Reviews would run on the request thread with Runnable::run; a no-op keeps them out of the way.
        TopicReviewer reviewer = new TopicReviewer(repository, llm, task -> { });
        server = new ApiServer(routes -> {
            auth.register(routes);
            new TopicFinderController(new TopicFinder(repository), reviewer, this::fetch, auth).register(routes);
        });
        logIn("testuser");
    }

    private boolean fetch(Collection<Integer> topicIds) {
        fetched.addAll(topicIds);
        return true;
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private void logIn(String username) {
        server.clearCookies();
        HttpResponse<String> response = server.send("POST", "/api/auth/register", "{\"email\":\"" + username
                + "@sru.edu\",\"username\":\"" + username + "\",\"password\":\"quiet lemon river walk\"}");
        assertEquals(201, response.statusCode(), response.body());
    }

    private HttpResponse<String> add(String name) {
        return server.send("POST", "/api/me/topics", "{\"name\":\"" + name + "\"}");
    }

    private long userId(String username) {
        return TestDatabase.number("SELECT id FROM users WHERE username = ?", username);
    }

    @Test
    void guestsGet401AndNothingIsAdded() {
        server.clearCookies();
        assertEquals(401, add("Black Holes").statusCode());
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM topics WHERE source = 'ai'"));
    }

    @Test
    void aNewTopicIsAddedFollowedAndFetchedRightAway() {
        HttpResponse<String> response = add("black holes");
        assertEquals(201, response.statusCode(), response.body());
        JsonNode body = server.json(response);
        JsonNode topic = body.get("topic");
        assertEquals("Black Holes", topic.get("name").asText());
        assertEquals(TestDatabase.topicId("Space"), topic.get("parentId").asInt());
        assertTrue(topic.get("ai").asBoolean());
        assertTrue(body.get("created").asBoolean());
        assertFalse(body.get("similar").asBoolean());
        // the search query is never sent to the browser
        assertFalse(response.body().contains("explained"));

        int id = topic.get("id").asInt();
        assertEquals(1, TestDatabase.number("SELECT count(*) FROM user_interests WHERE user_id = ? AND topic_id = ?",
                userId("testuser"), id));
        assertEquals(List.of(id), fetched);
    }

    @Test
    void anExistingTopicIsPickedNotAdded() {
        HttpResponse<String> response = add("ALGEBRA 2");
        assertEquals(200, response.statusCode());
        JsonNode body = server.json(response);
        assertEquals(TestDatabase.topicId("Algebra 2"), body.get("topic").get("id").asInt());
        assertFalse(body.get("created").asBoolean());
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM topics WHERE source = 'ai'"));
    }

    @Test
    void aTypoPicksTheCloseTopicAndSaysSo() {
        JsonNode body = server.json(add("calculas"));
        assertEquals("Calculus", body.get("topic").get("name").asText());
        assertTrue(body.get("similar").asBoolean());
    }

    @Test
    void asTypedAddsTheSpellingTheUserMeant() {
        // "Generics" is one letter from "Genetics", so it needs a way to say "no, really"
        HttpResponse<String> first = add("Generics");
        assertEquals("Genetics", server.json(first).get("topic").get("name").asText());
        HttpResponse<String> second = server.send("POST", "/api/me/topics", "{\"name\":\"Generics\",\"asTyped\":true}");
        assertEquals(201, second.statusCode());
        assertEquals("Generics", server.json(second).get("topic").get("name").asText());
    }

    @Test
    void badInputIs400NeverA500() {
        List<String> bodies = List.of("not json", "{}", "[]", "null", "{\"name\":null}", "{\"name\":\"\"}",
                "{\"name\":\"a\"}", "{\"name\":42}", "{\"name\":[\"x\"]}", "{\"name\":\"" + "x".repeat(41) + "\"}",
                "{\"name\":\"one two three four\"}", "{\"name\":\"<script>alert(1)</script>\"}",
                "{\"name\":\"http://169.254.169.254/latest\"}", "{\"name\":\"\\u0000null\"}",
                "{\"name\":\"Black Holes\",\"asTyped\":\"yes\"}", "{\"name\":\"Black Holes\",\"key\":\"abc\"}");
        for (String body : bodies) {
            HttpResponse<String> response = server.send("POST", "/api/me/topics", body);
            assertEquals(400, response.statusCode(), body + " -> " + response.body());
        }
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM topics WHERE source = 'ai'"));
    }

    @Test
    void denylistedAndBannedNamesAre409() {
        assertEquals(409, add("p0rn").statusCode());
        TestDatabase.exec("INSERT INTO banned_topic_names (name_lower, reason) VALUES ('black holes', 'test')");
        HttpResponse<String> banned = add("Black Hole");
        assertEquals(409, banned.statusCode());
        assertEquals("That topic can't be added.", server.json(banned).get("error").asText());
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM topics WHERE source = 'ai'"));
    }

    @Test
    void parallelAddsOfTheSameTopicMakeOnlyOne() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<HttpResponse<String>>> responses = new ArrayList<>();
        try {
            for (int i = 0; i < 12; i++) {
                // a mix of spellings that all mean the same topic
                String name = i % 3 == 0 ? "Comet Tails" : i % 3 == 1 ? "comet tail" : "COMET TAILS";
                responses.add(pool.submit(() -> add(name)));
            }
            int created = 0;
            for (Future<HttpResponse<String>> response : responses) {
                int status = response.get().statusCode();
                assertTrue(status == 200 || status == 201, response.get().body());
                created += status == 201 ? 1 : 0;
            }
            assertEquals(1, created);
        } finally {
            pool.shutdown();
        }
        assertEquals(1, TestDatabase.number("SELECT count(*) FROM topics WHERE source = 'ai'"));
    }

    @Test
    void eachUserCanAddTenNewTopicsADay() {
        // names far enough apart that none of them counts as a typo of another
        List<String> names = List.of("Knitting", "Sourdough", "Beekeeping", "Juggling", "Woodworking",
                "Birdwatching", "Calligraphy Pens", "Skateboarding", "Gardening", "Fishing Knots");
        assertEquals(TopicFinder.LIMITS.perUserPerDay(), names.size());
        for (String name : names) {
            assertEquals(201, add(name).statusCode(), name);
        }
        HttpResponse<String> limited = add("One More Topic");
        assertEquals(429, limited.statusCode());
        assertTrue(server.json(limited).get("error").asText().contains("Try again tomorrow"));
        // picking topics we already have still works
        assertEquals(200, add("Math").statusCode());
    }

    @Test
    void tooManyRequestsInAnHourAre429() {
        for (int i = 0; i < TopicFinder.REQUESTS_PER_HOUR; i++) {
            assertEquals(200, add("Math").statusCode());
        }
        assertEquals(429, add("Math").statusCode());
        // another account isn't affected
        logIn("otheruser");
        assertEquals(200, add("Math").statusCode());
    }

    @Test
    void writesNeedTheCsrfHeaders() {
        // another site's page posting with the user's cookie: App.configure refuses it first
        HttpResponse<String> crossSite = server.send(server.builder("/api/me/topics")
                .header("Content-Type", "application/json")
                .header("Origin", "https://evil.example")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Black Holes\"}")));
        assertEquals(403, crossSite.statusCode());
        // a plain form post (no JSON content type) is refused too
        HttpResponse<String> form = server.send(server.builder("/api/me/topics")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("name=Black+Holes")));
        assertEquals(415, form.statusCode());
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM topics WHERE source = 'ai'"));
    }
}
