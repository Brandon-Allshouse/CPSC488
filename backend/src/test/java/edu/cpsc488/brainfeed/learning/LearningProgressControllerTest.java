package edu.cpsc488.brainfeed.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.cpsc488.brainfeed.App;
import edu.cpsc488.brainfeed.auth.User;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/JSON boundaries; repository persistence and constraints have separate integration coverage. */
class LearningProgressControllerTest {
    private static final String VIDEO = "video000001";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-05T12:00:00Z");
    private final AtomicReference<User> session = new AtomicReference<>();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final HttpClient client = HttpClient.newHttpClient();
    private final Map<Long, LearningProgress> values = new ConcurrentHashMap<>();
    private Javalin app;

    @BeforeEach void start() {
        session.set(user(1));
        var repository = new LearningProgressRepository(null) {
            @Override public List<LearningProgress> list(long userId) {
                return values.containsKey(userId) ? List.of(values.get(userId)) : List.of();
            }
            @Override public boolean saveStatus(long userId, String youtubeId, String status) {
                if (userId > 2 || !VIDEO.equals(youtubeId)) return false;
                values.put(userId, new LearningProgress(VIDEO, "Private video", "Creator", status,
                        status.equals("completed") ? NOW : null, null, NOW));
                return true;
            }
            @Override public boolean markReviewed(long userId, String youtubeId) {
                return VIDEO.equals(youtubeId) && values.containsKey(userId) && values.get(userId).status().equals("completed");
            }
        };
        var controller = new LearningProgressController(new LearningProgressService(repository),
                context -> Optional.ofNullable(session.get()));
        app = Javalin.create(config -> {
            App.configure(config, false, Set.of("http://localhost:5173"));
            controller.register(config.routes);
        }).start(0);
    }

    @AfterEach void stop() {
        client.close();
        if (app != null) app.stop();
    }

    @Test void allEndpointsRequireAuthentication() throws Exception {
        session.set(null);
        assertEquals(401, call("GET", "", "").statusCode());
        assertEquals(401, call("GET", "/export", "").statusCode());
        assertEquals(401, call("PUT", "/" + VIDEO, "{\"status\":\"completed\"}").statusCode());
        assertEquals(401, call("POST", "/" + VIDEO + "/review", "").statusCode());
        assertTrue(values.isEmpty());
    }

    @Test void savesAndReturnsGuiReadyTotals() throws Exception {
        assertEquals(204, call("PUT", "/" + VIDEO, "{\"status\":\"completed\"}").statusCode());
        var result = mapper.readTree(call("GET", "", "").body());
        assertEquals(1, result.path("summary").path("completed").asInt());
        assertEquals(100, result.path("summary").path("completionPercent").asInt());
        assertEquals(VIDEO, result.path("items").get(0).path("progress").path("youtubeId").asText());
    }

    @Test void isolatesUsersEvenWhenTheySaveTheSameVideo() throws Exception {
        call("PUT", "/" + VIDEO, "{\"status\":\"completed\"}");
        session.set(user(2));
        assertEquals(0, mapper.readTree(call("GET", "", "").body()).path("summary").path("total").asInt());
        call("PUT", "/" + VIDEO, "{\"status\":\"in_progress\"}");
        assertEquals("completed", values.get(1L).status());
        assertEquals("in_progress", values.get(2L).status());
    }

    @Test void rejectsOwnerSpoofingMalformedJsonUnknownFieldsAndWrongTypes() throws Exception {
        for (String body : List.of("{", "null", "[]", "{}", "{\"status\":null}", "{\"status\":1}",
                "{\"status\":\"completed\",\"userId\":2}", "{\"status\":\"finished\"}",
                "{\"status\":\"completed\",\"completedAt\":\"2000-01-01\"}")) {
            assertEquals(400, call("PUT", "/" + VIDEO, body).statusCode(), body);
        }
        assertTrue(values.isEmpty());
    }

    @Test void rejectsInvalidVideoIdsForBothWriteRoutes() throws Exception {
        assertEquals(400, call("PUT", "/bad", "{\"status\":\"completed\"}").statusCode());
        assertEquals(400, call("POST", "/bad/review", "").statusCode());
    }

    @Test void unownedAndMissingVideosReturnTheSameNotFound() throws Exception {
        session.set(user(3));
        assertEquals(404, call("PUT", "/" + VIDEO, "{\"status\":\"completed\"}").statusCode());
        assertEquals(404, call("PUT", "/video999999", "{\"status\":\"completed\"}").statusCode());
        assertEquals(404, call("POST", "/" + VIDEO + "/review", "").statusCode());
    }

    @Test void onlyCompletedNotebookVideosCanBeReviewedAndNoFieldsAreAccepted() throws Exception {
        call("PUT", "/" + VIDEO, "{\"status\":\"planned\"}");
        assertEquals(404, call("POST", "/" + VIDEO + "/review", "").statusCode());
        call("PUT", "/" + VIDEO, "{\"status\":\"completed\"}");
        assertEquals(204, call("POST", "/" + VIDEO + "/review", "").statusCode());
        assertEquals(400, call("POST", "/" + VIDEO + "/review", "{\"userId\":2}").statusCode());
    }

    @Test void exportsOnlyTheSessionOwnersPlanWithPrivateDownloadHeaders() throws Exception {
        call("PUT", "/" + VIDEO, "{\"status\":\"completed\"}");
        var response = call("GET", "/export", "");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("text/csv"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("attachment; filename=\"brainfeed-learning-progress.csv\"",
                response.headers().firstValue("Content-Disposition").orElseThrow());
        assertTrue(response.body().contains("Private video"));
        session.set(user(2));
        var other = call("GET", "/export", "");
        assertEquals(200, other.statusCode());
        assertFalse(other.body().contains("Private video"));
    }

    @Test void inheritsCrossSiteWriteAndBodySizeProtections() throws Exception {
        String url = "http://localhost:" + app.port() + "/api/me/learning-progress/" + VIDEO;
        var crossSite = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .header("Origin", "https://outside.example")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"status\":\"completed\"}")).build();
        assertEquals(403, client.send(crossSite, HttpResponse.BodyHandlers.ofString()).statusCode());
        var form = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("completed")).build();
        assertEquals(415, client.send(form, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(413, call("PUT", "/" + VIDEO, "{\"status\":\"" + "x".repeat(17000) + "\"}").statusCode());
        assertTrue(values.isEmpty());
    }

    private HttpResponse<String> call(String method, String suffix, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/api/me/learning-progress" + suffix))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static User user(long id) { return new User(id, "user" + id + "@example.com", "user" + id, NOW, false); }
}
