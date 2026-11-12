package edu.cpsc488.brainfeed.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Checks what we send Ollama and how we read its replies, without a running Ollama.
class OllamaClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode schema = mapper.createObjectNode().put("type", "object");

    @Test
    void requestHasTheModelBothMessagesAndTheSchema() throws Exception {
        JsonNode body = mapper.readTree(OllamaClient.requestBody("qwen3:4b", "system text", "user text", schema));
        assertEquals("qwen3:4b", body.get("model").asText());
        assertFalse(body.get("stream").asBoolean());
        assertFalse(body.get("think").asBoolean());
        assertEquals(schema, body.get("format"));
        assertEquals("system", body.get("messages").get(0).get("role").asText());
        assertEquals("system text", body.get("messages").get(0).get("content").asText());
        assertEquals("user", body.get("messages").get(1).get("role").asText());
        assertEquals("user text", body.get("messages").get(1).get("content").asText());
    }

    @Test
    void repliesHaveALengthLimit() throws Exception {
        // without num_predict a model that starts repeating itself runs until the 4-minute timeout
        JsonNode options = mapper.readTree(OllamaClient.requestBody("qwen3:4b", "s", "u", schema)).get("options");
        assertEquals(OllamaClient.MAX_REPLY_TOKENS, options.get("num_predict").asInt());
        assertTrue(options.get("num_predict").asInt() > 0);
        assertTrue(options.get("num_predict").asInt() < options.get("num_ctx").asInt());
    }

    @Test
    void videoTextIsJsonEscapedNotPastedIntoTheRequest() throws Exception {
        String sneaky = "\", \"model\": \"other\"";
        JsonNode body = mapper.readTree(OllamaClient.requestBody("qwen3:4b", "system text", sneaky, schema));
        assertEquals("qwen3:4b", body.get("model").asText());
        assertEquals(sneaky, body.get("messages").get(1).get("content").asText());
    }

    @Test
    void readsTheMessageContent() throws Exception {
        JsonNode reply = mapper.readTree("{\"message\": {\"role\": \"assistant\", \"content\": \"{\\\"summary\\\": \\\"hi\\\"}\"}, \"done\": true}");
        assertEquals("{\"summary\": \"hi\"}", OllamaClient.content(reply));
    }

    @Test
    void aReplyWithNoContentIsAnError() throws Exception {
        assertThrows(IOException.class, () -> OllamaClient.content(mapper.readTree("{\"done\": true}")));
    }

    @Test
    void errorMessagesAreCleanedAndShort() throws Exception {
        JsonNode error = mapper.readTree("{\"error\": \"model 'qwen3:4b' not found\\n" + "x".repeat(500) + "\"}");
        String message = OllamaClient.errorMessage(error);
        assertTrue(message.startsWith("model 'qwen3:4b' not found"));
        assertFalse(message.contains("\n"));
        assertTrue(message.length() <= 200);
        assertEquals("unknown error", OllamaClient.errorMessage(mapper.readTree("{}")));
    }

    @Test
    void trailingSlashInTheUrlIsFine() {
        // doesn't throw, and the model name is kept for saving with the summary
        assertEquals("qwen3:4b", new OllamaClient("http://ollama:11434/", "qwen3:4b").model());
    }
}
