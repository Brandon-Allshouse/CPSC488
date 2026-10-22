package edu.cpsc488.brainfeed.saved;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.auth.User;
import edu.cpsc488.brainfeed.feed.Video;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real HTTP requests against the controller, with a fake repository instead of PostgreSQL. */
class SavedVideoControllerTest {
    private static final String VIDEO = "dQw4w9WgXcQ";
    private static final String MISSING_VIDEO = "missing0001";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-22T00:00:00Z");
    private final AtomicReference<User> session = new AtomicReference<>();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private Javalin app;
    private FakeSavedVideos saved;

    @BeforeEach
    void start() {
        saved = new FakeSavedVideos();
        session.set(user(1));
        SavedVideoController controller = new SavedVideoController(saved, ctx -> Optional.ofNullable(session.get()));
        app = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(mapper, false));
            config.startup.showJavalinBanner = false;
            config.routes.exception(ApiException.class, (error, ctx) ->
                    ctx.status(error.status()).json(Map.of("error", error.getMessage())));
            controller.register(config.routes);
        }).start(0);
    }

    @AfterEach
    void stop() {
        client.close();
        if (app != null) app.stop();
    }

    @Test
    void acceptsValidYoutubeId() {
        assertEquals(VIDEO, SavedVideoController.validateYoutubeId(VIDEO));
    }

    @Test
    void rejectsInvalidAndSqlInjectionStyleIds() {
        for (String id : new String[] {"not-valid", "' OR 1=1 --", "", null}) {
            ApiException e = assertThrows(ApiException.class, () -> SavedVideoController.validateYoutubeId(id));
            assertEquals(400, e.status());
        }
    }

    @Test
    void everyEndpointNeedsALogin() throws Exception {
        session.set(null);
        assertEquals(401, list().statusCode());
        assertEquals(401, send("POST", VIDEO).statusCode());
        assertEquals(401, send("DELETE", VIDEO).statusCode());
        assertTrue(saved.rows.isEmpty());
    }

    @Test
    void savesListsAndRemovesAVideo() throws Exception {
        assertEquals(204, send("POST", VIDEO).statusCode());
        JsonNode videos = mapper.readTree(list().body()).get("videos");
        assertEquals(VIDEO, videos.get(0).get("youtubeId").textValue());

        assertEquals(204, send("DELETE", VIDEO).statusCode());
        assertEquals(0, mapper.readTree(list().body()).get("videos").size());
    }

    @Test
    void savingTwiceIsFine() throws Exception {
        assertEquals(204, send("POST", VIDEO).statusCode());
        assertEquals(204, send("POST", VIDEO).statusCode());
        assertEquals(1, saved.rows.size());
    }

    @Test
    void unknownVideoIs404AndBadIdIs400() throws Exception {
        assertEquals(404, send("POST", MISSING_VIDEO).statusCode());
        assertEquals(400, send("POST", "short").statusCode());
        assertTrue(saved.rows.isEmpty());
    }

    @Test
    void oneUserCantSeeOrRemoveAnotherUsersSavedVideos() throws Exception {
        send("POST", VIDEO);
        session.set(user(2));
        assertEquals(0, mapper.readTree(list().body()).get("videos").size());
        send("DELETE", VIDEO);

        session.set(user(1));
        assertEquals(1, mapper.readTree(list().body()).get("videos").size());
    }

    @Test
    void aFullNotebookIs409() throws Exception {
        saved.full = true;
        HttpResponse<String> response = send("POST", VIDEO);
        assertEquals(409, response.statusCode());
        assertTrue(response.body().contains(String.valueOf(SavedVideoRepository.MAX_SAVED_VIDEOS)));
    }

    private HttpResponse<String> list() throws Exception {
        return request("GET", "/api/me/saved-videos");
    }

    private HttpResponse<String> send(String method, String youtubeId) throws Exception {
        return request(method, "/api/me/saved-videos/" + youtubeId);
    }

    private HttpResponse<String> request(String method, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
                .timeout(Duration.ofSeconds(5)).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static User user(long id) {
        return new User(id, "test" + id + "@sru.edu", "testuser" + id, NOW, false);
    }

    // Keeps "userId:youtubeId" rows in memory. Only VIDEO exists in the pretend videos table.
    private static class FakeSavedVideos extends SavedVideoRepository {
        final List<String> rows = new CopyOnWriteArrayList<>();
        volatile boolean full;

        FakeSavedVideos() { super(null); }

        @Override public boolean save(long userId, String youtubeId) {
            if (!Set.of(VIDEO).contains(youtubeId)) return false;
            if (rows.contains(userId + ":" + youtubeId)) return true;
            if (full) throw ApiException.conflict("Your Study notebook can hold at most "
                    + SavedVideoRepository.MAX_SAVED_VIDEOS + " videos.");
            rows.add(userId + ":" + youtubeId);
            return true;
        }

        @Override public void remove(long userId, String youtubeId) {
            rows.remove(userId + ":" + youtubeId);
        }

        @Override public List<Video> findByUser(long userId) {
            return rows.stream().filter(row -> row.startsWith(userId + ":"))
                    .map(row -> new Video(row.substring(row.indexOf(':') + 1), "Test video", "Test channel", 1, NOW))
                    .toList();
        }
    }
}
