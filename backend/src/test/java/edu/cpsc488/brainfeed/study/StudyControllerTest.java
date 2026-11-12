package edu.cpsc488.brainfeed.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.auth.AuthController;
import edu.cpsc488.brainfeed.auth.PasswordHasher;
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
        // No database: guests never touch the user or session tables, and nothing is recorded for them.
        AuthController auth = new AuthController(null, null, new PasswordHasher(new byte[32], 2), null, false);
        StudyController controller = new StudyController(generator, repository, auth, null);
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

    private HttpResponse<String> answer(String youtubeId, String json) throws Exception {
        URI uri = URI.create("http://localhost:" + app.port() + "/api/videos/" + youtubeId + "/study/answers");
        return client.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void quizReady() {
        repository.saved.put(StudyGeneratorTest.VIDEO, new StudyMaterial("Derivatives measure change.", List.of(
                new StudyMaterial.Question("What does a derivative measure?", List.of("Area", "Rate of change", "Volume", "Mass"),
                        1, "A derivative is the rate of change."))));
    }

    @Test
    void guestsCanCheckAnAnswer() throws Exception {
        quizReady();
        JsonNode right = mapper.readTree(answer(StudyGeneratorTest.VIDEO, "{\"question\":0,\"choice\":1}").body());
        assertTrue(right.get("correct").asBoolean());
        assertEquals(1, right.get("answerIndex").asInt());
        assertEquals("A derivative is the rate of change.", right.get("explanation").asText());
        assertFalse(right.get("signedIn").asBoolean());
        assertFalse(right.get("counted").asBoolean());
        assertEquals(0, right.get("pointsEarned").asInt());

        JsonNode wrong = mapper.readTree(answer(StudyGeneratorTest.VIDEO, "{\"question\":0,\"choice\":3}").body());
        assertFalse(wrong.get("correct").asBoolean());
        assertEquals(1, wrong.get("answerIndex").asInt());
    }

    @Test
    void badAnswersAre400() throws Exception {
        quizReady();
        for (String bad : List.of("{\"question\":1,\"choice\":0}", "{\"question\":3,\"choice\":0}",
                "{\"question\":-1,\"choice\":0}", "{\"question\":0,\"choice\":4}", "{\"question\":0,\"choice\":-1}",
                "{\"question\":0}", "{\"choice\":1}", "{\"question\":\"0\",\"choice\":1}", "{\"question\":0,\"choice\":1.5}",
                "{\"question\":null,\"choice\":1}", "{\"question\":0,\"choice\":99999999999}", "[]", "not json")) {
            assertEquals(400, answer(StudyGeneratorTest.VIDEO, bad).statusCode(), bad);
        }
        assertEquals(400, answer("short", "{\"question\":0,\"choice\":1}").statusCode());
    }

    @Test
    void answeringAVideoWithNoQuizIs404() throws Exception {
        HttpResponse<String> response = answer(StudyGeneratorTest.VIDEO, "{\"question\":0,\"choice\":1}");
        assertEquals(404, response.statusCode());
        // and checking an answer never starts writing a quiz
        assertTrue(jobs.isEmpty());
    }

    @Test
    void oneComputerCantSendEndlessAnswers() throws Exception {
        quizReady();
        for (int i = 0; i < StudyController.ANSWERS_PER_IP_PER_MINUTE; i++) {
            assertEquals(200, answer(StudyGeneratorTest.VIDEO, "{\"question\":0,\"choice\":1}").statusCode());
        }
        assertEquals(429, answer(StudyGeneratorTest.VIDEO, "{\"question\":0,\"choice\":1}").statusCode());
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
        // the answer only comes back after answering (SECURITY.md gap 15)
        assertFalse(question.has("answerIndex"));
        assertFalse(question.has("explanation"));
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
    void oneComputerCantStartEndlessNewSummaries() throws Exception {
        // the controller passes the caller's IP, so the per-IP limit applies over HTTP too
        for (int i = 0; i < StudyGenerator.NEW_PER_IP_PER_HOUR; i++) {
            String id = String.format("video%06d", i + 100);
            repository.videos.put(id, new StudyRepository.VideoInfo("Title", "Test Channel", "Math"));
            assertEquals("pending", mapper.readTree(get(id).body()).get("status").asText());
            jobs.forEach(Runnable::run);
            jobs.clear();
        }
        assertEquals("busy", mapper.readTree(get(StudyGeneratorTest.VIDEO).body()).get("status").asText());
    }

    @Test
    void statusNamesAreLowercase() {
        assertEquals(Map.of("status", "busy"), StudyController.body(StudyGenerator.Result.of(StudyGenerator.Status.BUSY)));
        assertEquals(Map.of("status", "unavailable"),
                StudyController.body(StudyGenerator.Result.of(StudyGenerator.Status.UNAVAILABLE)));
    }

    @Test
    void unavailableWhenTheDailyLimitIsUsedUp() throws Exception {
        repository.writtenToday = StudyGenerator.DAILY_LIMIT;
        assertEquals("unavailable", mapper.readTree(get(StudyGeneratorTest.VIDEO).body()).get("status").asText());
    }
}
