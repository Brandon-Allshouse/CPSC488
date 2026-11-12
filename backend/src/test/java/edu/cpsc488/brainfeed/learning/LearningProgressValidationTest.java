package edu.cpsc488.brainfeed.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cpsc488.brainfeed.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Input checks also run without a network listener; the HTTP tests cover request parsing separately. */
class LearningProgressValidationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void acceptsExactlyOneSupportedStatusField() throws Exception {
        for (String status : List.of("planned", "in_progress", "completed")) {
            assertEquals(status, LearningProgressController.readStatus(mapper.readTree("{\"status\":\"" + status + "\"}")));
        }
    }

    @Test void rejectsClientSelectedOwnersTimestampsAndNonTextStatuses() throws Exception {
        for (String body : List.of("null", "[]", "{}", "{\"status\":null}", "{\"status\":true}",
                "{\"status\":1}", "{\"status\":{\"completed\":true}}", "{\"status\":[\"completed\"]}",
                "{\"status\":\"completed\",\"userId\":2}", "{\"status\":\"completed\",\"completedAt\":\"2000-01-01\"}")) {
            var value = mapper.readTree(body);
            assertEquals(400, assertThrows(ApiException.class, () -> LearningProgressController.readStatus(value)).status(), body);
        }
        assertThrows(ApiException.class, () -> LearningProgressController.readStatus(null));
    }

    @Test void refusesUnsupportedAndOverlongStatuses() throws Exception {
        for (String status : List.of("Completed", "done", " completed", "completed ", "x".repeat(16000))) {
            var value = mapper.createObjectNode().put("status", status);
            assertEquals(400, assertThrows(ApiException.class, () -> LearningProgressController.readStatus(value)).status());
        }
    }

    @Test void videoIdsCannotInjectSqlOrPaths() {
        for (String id : List.of("bad", "video00000001", "video00000/", "video00000'", "video00000\n", "<script>bad")) {
            assertEquals(400, assertThrows(ApiException.class, () -> LearningProgressController.validateVideoId(id)).status(), id);
        }
        assertThrows(ApiException.class, () -> LearningProgressController.validateVideoId(null));
        assertEquals("video000001", LearningProgressController.validateVideoId("video000001"));
        assertEquals("aB_0-123456", LearningProgressController.validateVideoId("aB_0-123456"));
    }
}
