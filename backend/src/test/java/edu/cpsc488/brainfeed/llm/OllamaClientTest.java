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
    void callersCanAskForAShorterReplyButNotALongerOne() throws Exception {
        JsonNode shorter = mapper.readTree(OllamaClient.requestBody("gemma4:e4b", "s", "u", schema, 1024)).get("options");
        assertEquals(1024, shorter.get("num_predict").asInt());
        JsonNode longer = mapper.readTree(OllamaClient.requestBody("gemma4:e4b", "s", "u", schema, 100_000)).get("options");
        assertEquals(OllamaClient.MAX_REPLY_TOKENS, longer.get("num_predict").asInt());
        // 0 or less would mean "no limit" to Ollama
        JsonNode zero = mapper.readTree(OllamaClient.requestBody("gemma4:e4b", "s", "u", schema, 0)).get("options");
        assertEquals(1, zero.get("num_predict").asInt());
    }

    @Test
    void everyRequestSendsTheSameContextSizeAndKeepAlive() throws Exception {
        // a different num_ctx makes Ollama reload the model, which takes ~10 seconds
        JsonNode chat = mapper.readTree(OllamaClient.requestBody("gemma4:e4b", "s", "u", schema, 1024));
        JsonNode warmUp = mapper.readTree(OllamaClient.warmUpBody("gemma4:e4b"));
        assertEquals(OllamaClient.CONTEXT_TOKENS, chat.get("options").get("num_ctx").asInt());
        assertEquals(OllamaClient.CONTEXT_TOKENS, warmUp.get("options").get("num_ctx").asInt());
        assertEquals(OllamaClient.KEEP_ALIVE, chat.get("keep_alive").asText());
        assertEquals(OllamaClient.KEEP_ALIVE, warmUp.get("keep_alive").asText());
        assertEquals(0.3, chat.get("options").get("temperature").asDouble());
    }

    @Test
    void warmUpSendsAnEmptyConversation() throws Exception {
        // an empty conversation only loads the model; it never writes anything
        JsonNode body = mapper.readTree(OllamaClient.warmUpBody("gemma4:e4b"));
        assertEquals("gemma4:e4b", body.get("model").asText());
        assertEquals(0, body.get("messages").size());
        assertFalse(body.get("stream").asBoolean());
    }

    @Test
    void readsTheTimingsFromAReply() throws Exception {
        JsonNode reply = mapper.readTree("""
                {"message": {"content": "{}"}, "done": true, "done_reason": "stop",
                 "total_duration": 13400000000, "load_duration": 50000000,
                 "prompt_eval_count": 516, "prompt_eval_duration": 2100000000,
                 "eval_count": 282, "eval_duration": 11300000000}""");
        OllamaClient.Timing timing = OllamaClient.timing(reply);
        assertEquals(516, timing.promptTokens());
        assertEquals(282, timing.replyTokens());
        assertEquals(11_300_000_000L, timing.replyNanos());
        assertEquals("stop", timing.doneReason());
        assertEquals("load 0.1s, prompt 516 tokens in 2.1s, reply 282 tokens in 11.3s, total 13.4s", timing.describe());
    }

    @Test
    void missingTimingsAreZeroNotAnError() throws Exception {
        OllamaClient.Timing timing = OllamaClient.timing(mapper.readTree("{\"message\": {\"content\": \"{}\"}}"));
        assertEquals(0, timing.promptTokens());
        assertEquals(0, timing.totalNanos());
        assertEquals("", timing.doneReason());
    }

    @Test
    void theTimingLogLineHasNoPromptOrReplyText() throws Exception {
        JsonNode reply = mapper.readTree("{\"message\": {\"content\": \"secret reply\"}, \"eval_count\": 5}");
        String line = OllamaClient.timing(reply).describe();
        assertFalse(line.contains("secret"));
    }

    @Test
    void warmUpFailsWithAnIOExceptionWhenOllamaIsDown() {
        // nothing listens on port 9 here; StudyGenerator logs this instead of crashing startup
        OllamaClient client = new OllamaClient("http://127.0.0.1:9", "gemma4:e4b");
        assertThrows(IOException.class, client::warmUp);
    }

    @Test
    void trailingSlashInTheUrlIsFine() {
        // doesn't throw, and the model name is kept for saving with the summary
        assertEquals("qwen3:4b", new OllamaClient("http://ollama:11434/", "qwen3:4b").model());
    }
}
