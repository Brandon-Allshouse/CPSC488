package edu.cpsc488.brainfeed.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cpsc488.brainfeed.ApiException;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real HTTP requests against the controller, with the fake database and model from StudyGeneratorTest.
class StudyControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private final StudyGeneratorTest.FakeRepository repository = new StudyGeneratorTest.FakeRepository();
    private final Deque<Runnable> jobs = new ArrayDeque<>();
    private Javalin app;

    @BeforeEach
    void start() {
        StudyGenerator generator = new StudyGenerator(repository, new StudyGeneratorTest.FakeLlm(),
                id -> Optional.empty(), jobs::add);
        StudyController controller = new StudyController(generator);
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
        app.stop();
    }

    private HttpResponse<String> get(String youtubeId) throws Exception {
        URI uri = URI.create("http://localhost:" + app.port() + "/api/videos/" + youtubeId + "/study");
        return client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void pendingThenReadyWithTheQuiz() throws Exception {
        HttpResponse<String> first = get(StudyGeneratorTest.VIDEO);
        assertEquals(200, first.statusCode());
        assertEquals("pending", mapper.readTree(first.body()).get("status").asText());

        jobs.forEach(Runnable::run);
        JsonNode ready = mapper.readTree(get(StudyGeneratorTest.VIDEO).body());
        assertEquals("ready", ready.get("status").asText());
        assertEquals("The video explains derivatives.", ready.get("summary").asText());
        JsonNode question = ready.get("questions").get(0);
        assertEquals("What does a derivative measure?", question.get("question").asText());
        assertEquals(4, question.get("options").size());
        assertEquals(1, question.get("answerIndex").asInt());
        assertTrue(question.has("explanation"));
    }

    @Test
    void pendingHasNoSummaryField() throws Exception {
        JsonNode pending = mapper.readTree(get(StudyGeneratorTest.VIDEO).body());
        assertFalse(pending.has("summary"));
        assertFalse(pending.has("questions"));
    }

    @Test
    void badVideoIdsAreRejected() throws Exception {
        for (String bad : List.of("short", "has%20space1", "waytoolongvideoid", "..%2F..%2Fetc")) {
            assertEquals(400, get(bad).statusCode(), bad);
        }
        assertTrue(jobs.isEmpty());
    }

    @Test
    void videosWeDoNotHaveAre404() throws Exception {
        HttpResponse<String> response = get("unknown0001");
        assertEquals(404, response.statusCode());
        assertEquals("Video not found.", mapper.readTree(response.body()).get("error").asText());
    }

    @Test
    void unavailableWhenTheDailyLimitIsUsedUp() throws Exception {
        repository.writtenToday = StudyGenerator.DAILY_LIMIT;
        assertEquals("unavailable", mapper.readTree(get(StudyGeneratorTest.VIDEO).body()).get("status").asText());
    }
}
