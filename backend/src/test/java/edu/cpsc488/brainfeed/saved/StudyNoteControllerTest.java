package edu.cpsc488.brainfeed.saved;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real HTTP/JSON/controller checks with a repository test double. PostgreSQL persistence is verified separately. */
class StudyNoteControllerTest {
    private static final String VIDEO = "video000001";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-22T00:00:00Z");
    private final AtomicReference<User> session = new AtomicReference<>();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private Javalin app;
    private FakeNotes notes;

    @BeforeEach
    void start() {
        notes = new FakeNotes();
        notes.saved.add("1:" + VIDEO);
        session.set(user(1));
        StudyNoteController controller = new StudyNoteController(notes, context -> Optional.ofNullable(session.get()));
        app = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(mapper, false));
            config.startup.showJavalinBanner = false;
            config.routes.exception(ApiException.class, (error, context) ->
                    context.status(error.status()).json(Map.of("error", error.getMessage())));
            controller.register(config.routes);
        }).start(0);
    }

    @AfterEach
    void stop() {
        client.close();
        if (app != null) app.stop();
    }

    @Test
    void requiresASessionForReadingAndWriting() throws Exception {
        session.set(null);
        assertEquals(401, get().statusCode());
        assertEquals(401, put("{\"note\":\"Private\"}").statusCode());
        assertTrue(notes.values.isEmpty());
    }

    @Test
    void savesAndReadsMultilineTextForTheSessionOwner() throws Exception {
        assertEquals(200, put(mapper.writeValueAsString(Map.of("note", "SYN\nSYN/ACK\nACK"))).statusCode());
        JsonNode response = mapper.readTree(get().body());
        assertEquals("SYN\nSYN/ACK\nACK", response.get("notes").get(0).get("note").textValue());
    }

    @Test
    void anotherUserCannotReadOrChangeThatNoteWithoutTheirOwnNotebookEntry() throws Exception {
        put("{\"note\":\"Owner one\"}");
        session.set(user(2));
        assertEquals(0, mapper.readTree(get().body()).get("notes").size());
        assertEquals(404, put("{\"note\":\"Overwrite\"}").statusCode());
        session.set(user(1));
        assertEquals("Owner one", mapper.readTree(get().body()).get("notes").get(0).get("note").textValue());
    }

    @Test
    void usersWhoSaveTheSameVideoKeepIndependentNotes() throws Exception {
        notes.saved.add("2:" + VIDEO);
        put("{\"note\":\"Owner one\"}");
        session.set(user(2));
        put("{\"note\":\"Owner two\"}");
        assertEquals("Owner two", mapper.readTree(get().body()).get("notes").get(0).get("note").textValue());
        session.set(user(1));
        assertEquals("Owner one", mapper.readTree(get().body()).get("notes").get(0).get("note").textValue());
    }

    @Test
    void refusesNonTextNotesAndClientSelectedUserIds() throws Exception {
        for (String body : List.of("{}", "null", "[]", "{\"note\":null}", "{\"note\":12}", "{\"note\":\"x\",\"userId\":2}")) {
            assertEquals(400, put(body).statusCode(), body);
        }
    }

    @Test
    void enforcesTheLengthLimitAndRejectsNullCharacters() throws Exception {
        assertEquals(200, put(mapper.writeValueAsString(Map.of("note", "x".repeat(2000)))).statusCode());
        assertEquals(400, put(mapper.writeValueAsString(Map.of("note", "x".repeat(2001)))).statusCode());
        assertEquals(400, put(mapper.writeValueAsString(Map.of("note", "bad\0text"))).statusCode());
        assertThrows(ApiException.class, () -> StudyNoteController.checkNote(null));
    }

    @Test
    void blankTextClearsTheNoteWithoutRemovingTheSavedVideo() throws Exception {
        put("{\"note\":\"Revision notes\"}");
        assertEquals(200, put("{\"note\":\"   \"}").statusCode());
        assertEquals("", mapper.readTree(get().body()).get("notes").get(0).get("note").textValue());
        assertTrue(notes.saved.contains("1:" + VIDEO));
    }

    private HttpResponse<String> get() throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/api/me/study-notes"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/api/me/study-notes/" + VIDEO))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static User user(long id) {
        return new User(id, "user" + id + "@example.com", "user" + id, NOW, false);
    }

    private static class FakeNotes extends StudyNoteRepository {
        final Set<String> saved = ConcurrentHashMap.newKeySet();
        final Map<String, StudyNote> values = new ConcurrentHashMap<>();
        FakeNotes() { super(null); }
        @Override public List<StudyNote> list(long userId) {
            return values.entrySet().stream().filter(entry -> entry.getKey().startsWith(userId + ":"))
                    .map(Map.Entry::getValue).toList();
        }
        @Override public Optional<StudyNote> save(long userId, String youtubeId, String note) {
            String key = userId + ":" + youtubeId;
            if (!saved.contains(key)) return Optional.empty();
            StudyNote result = new StudyNote(youtubeId, note, NOW);
            values.put(key, result);
            return Optional.of(result);
        }
    }
}
