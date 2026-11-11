package edu.cpsc488.brainfeed.topics;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.auth.User;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real HTTP requests against the controller, with the fakes from TopicSuggestionsTest.
class TopicSuggestionControllerTest {

    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicReference<User> session = new AtomicReference<>();
    private final TopicSuggestionsTest.FakeRepository repository = new TopicSuggestionsTest.FakeRepository();
    private final TopicSuggestionsTest.FakeLlm llm = new TopicSuggestionsTest.FakeLlm();
    private Javalin app;

    @BeforeEach
    void start() {
        session.set(new User(1, "test@sru.edu", "testuser", OffsetDateTime.parse("2026-11-10T00:00:00Z"), false));
        TopicSuggestionController controller = new TopicSuggestionController(
                new TopicSuggestions(repository, llm), ctx -> Optional.ofNullable(session.get()));
        app = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(mapper, false));
            config.startup.showJavalinBanner = false;
            config.routes.exception(ApiException.class, (e, ctx) -> ctx.status(e.status()).json(Map.of("error", e.getMessage())));
            controller.register(config.routes);
        }).start(0);
    }

    @AfterEach
    void stop() {
        client.close();
        app.stop();
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void bothEndpointsNeedALogin() throws Exception {
        session.set(null);
        assertEquals(401, post("/api/me/topic-suggestions", "{\"text\":\"space\"}").statusCode());
        assertEquals(401, post("/api/me/topics", "{\"key\":\"abc\"}").statusCode());
        assertTrue(llm.prompts.isEmpty());
    }

    @Test
    void suggestThenAdd() throws Exception {
        llm.replies.add(TopicSuggestionsTest.reply(TopicSuggestionsTest.topic("Space", "Black Holes")));
        JsonNode result = mapper.readTree(post("/api/me/topic-suggestions", "{\"text\":\"black holes\"}").body());
        assertEquals(0, result.get("matches").size());
        JsonNode idea = result.get("ideas").get(0);
        assertEquals("Black Holes", idea.get("name").asText());
        assertEquals("Space", idea.get("subject").asText());

        HttpResponse<String> added = post("/api/me/topics", "{\"key\":\"" + idea.get("key").asText() + "\"}");
        assertEquals(201, added.statusCode());
        JsonNode topic = mapper.readTree(added.body()).get("topics").get(0);
        assertEquals("Black Holes", topic.get("name").asText());
        assertTrue(topic.get("ai").asBoolean());
    }

    @Test
    void addingSomethingThatAlreadyExistsIs200Not201() throws Exception {
        llm.replies.add(TopicSuggestionsTest.reply(TopicSuggestionsTest.topic("Space", "Black Holes")));
        String key = mapper.readTree(post("/api/me/topic-suggestions", "{\"text\":\"black holes\"}").body())
                .get("ideas").get(0).get("key").asText();
        repository.nextOutcome = AiTopicRepository.Outcome.EXISTS;
        assertEquals(200, post("/api/me/topics", "{\"key\":\"" + key + "\"}").statusCode());
    }

    @Test
    void aClientCannotSendItsOwnTopicName() throws Exception {
        // only "key" is accepted; extra fields are rejected rather than ignored
        assertEquals(400, post("/api/me/topics", "{\"key\":\"abc\",\"name\":\"HACKED\"}").statusCode());
        assertEquals(400, post("/api/me/topic-suggestions", "{\"text\":\"x\",\"model\":\"other\"}").statusCode());
        assertTrue(repository.addCalls.isEmpty());
    }

    @Test
    void badBodiesAre400() throws Exception {
        assertEquals(400, post("/api/me/topic-suggestions", "not json").statusCode());
        assertEquals(400, post("/api/me/topic-suggestions", "{\"text\":\"\"}").statusCode());
        assertEquals(400, post("/api/me/topic-suggestions", "{}").statusCode());
    }
}
