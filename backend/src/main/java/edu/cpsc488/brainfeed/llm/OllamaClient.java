package edu.cpsc488.brainfeed.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Talks to Ollama (https://ollama.com), which runs a free, open model on our own machine, so
 * summaries cost nothing and no video data leaves the server. In Docker it's the "ollama"
 * service, which only the backend can reach (see docker-compose.yml).
 *
 * <p>Ollama has no login of its own, which is why it must never get a public port.
 *
 * <p>Every reply's timings (model load, prompt and reply token counts and how long each took)
 * are logged, never the prompt or reply text, so slow summaries can be explained from the logs.
 */
public class OllamaClient implements Llm {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    // CPU-only laptops take 10-60 seconds per video, plus loading the model after it's been idle.
    static final Duration TIMEOUT = Duration.ofMinutes(4);
    // Ollama's default is no limit: a model stuck repeating itself keeps going until the timeout,
    // holding up everyone else's requests. Callers can ask for less (summaries use 1,024).
    static final int MAX_REPLY_TOKENS = 2048;
    // Has to be the same in every request, warm-up included: Ollama reloads the whole model
    // (about 10 seconds on a CPU) whenever it changes. We measured 2,048 against 4,096 and the
    // speed per token was the same, and the topic reviewer's catalog prompt needs the room.
    static final int CONTEXT_TOKENS = 4096;
    // How long Ollama keeps the model in memory after a request. Sent with every request so it
    // holds no matter how the ollama container is set up; loading it again takes ~10 seconds.
    static final String KEEP_ALIVE = "30m";

    /**
     * Ollama's timings for one reply (durations in nanoseconds, as Ollama sends them).
     * doneReason is "length" when the reply was cut off by the token cap.
     */
    record Timing(long loadNanos, int promptTokens, long promptNanos, int replyTokens, long replyNanos,
                  long totalNanos, String doneReason) {

        /** One line for the log, in seconds. No prompt or reply text, so it's safe to log. */
        String describe() {
            return String.format(Locale.ROOT,
                    "load %.1fs, prompt %d tokens in %.1fs, reply %d tokens in %.1fs, total %.1fs",
                    loadNanos / 1e9, promptTokens, promptNanos / 1e9, replyTokens, replyNanos / 1e9,
                    totalNanos / 1e9);
        }
    }

    private final URI chatUrl;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** baseUrl comes from OLLAMA_URL in .env, never from a request, so there's no SSRF here. */
    public OllamaClient(String baseUrl, String model) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.chatUrl = URI.create(base + "/api/chat");
        this.model = model;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String chat(String system, String user, JsonNode schema) throws IOException, InterruptedException {
        return chat(system, user, schema, MAX_REPLY_TOKENS);
    }

    /**
     * Sends one non-streaming chat request and logs its timings. Error messages never include the
     * prompt or the reply, so they're safe to log.
     */
    @Override
    public String chat(String system, String user, JsonNode schema, int maxReplyTokens)
            throws IOException, InterruptedException {
        JsonNode body = send(requestBody(model, system, user, schema, maxReplyTokens));
        Timing timing = timing(body);
        if ("length".equals(timing.doneReason())) {
            log.warn("Ollama reply hit the {}-token cap: {}", maxReplyTokens, timing.describe());
        } else {
            log.info("Ollama reply: {}", timing.describe());
        }
        return content(body);
    }

    /**
     * Asks Ollama to load the model now, with the same context size as real requests, so the
     * first person to ask for a summary doesn't wait ~10 seconds for it. An empty conversation
     * loads the model without writing anything.
     */
    @Override
    public void warmUp() throws IOException, InterruptedException {
        // Ollama's reply to an empty conversation has no timings, so time it ourselves.
        long start = System.nanoTime();
        send(warmUpBody(model));
        log.info("Ollama loaded {} in {}s", model,
                String.format(Locale.ROOT, "%.1f", (System.nanoTime() - start) / 1e9));
    }

    private JsonNode send(String requestBody) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(chatUrl)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // Usually means the ollama container isn't running.
            throw new IOException("Ollama request failed: " + e.getClass().getSimpleName());
        }
        JsonNode body;
        try {
            body = mapper.readTree(response.body());
        } catch (IOException e) {
            throw new IOException("Ollama sent back something that isn't JSON (HTTP " + response.statusCode() + ")");
        }
        if (response.statusCode() != 200) {
            // e.g. 404 "model 'qwen3:4b' not found" while ollama-pull is still downloading it
            throw new IOException("Ollama returned HTTP " + response.statusCode() + " (" + errorMessage(body) + ")");
        }
        return body;
    }

    static String requestBody(String model, String system, String user, JsonNode schema) {
        return requestBody(model, system, user, schema, MAX_REPLY_TOKENS);
    }

    static String requestBody(String model, String system, String user, JsonNode schema, int maxReplyTokens) {
        ObjectNode body = baseBody(model);
        // Thinking models (Qwen 3) would spend a minute reasoning first. We don't need it here.
        body.put("think", false);
        // Ollama only lets the model produce JSON matching this schema.
        body.set("format", schema);
        ObjectNode options = (ObjectNode) body.get("options");
        // Low temperature keeps it close to the description instead of making things up.
        options.put("temperature", 0.3);
        // Never above the overall cap, and at least 1, since Ollama reads 0 or less as "no limit".
        options.put("num_predict", Math.clamp(maxReplyTokens, 1, MAX_REPLY_TOKENS));
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);
        return body.toString();
    }

    /** An empty conversation: Ollama only loads the model and keeps it for {@link #KEEP_ALIVE}. */
    static String warmUpBody(String model) {
        ObjectNode body = baseBody(model);
        body.putArray("messages");
        return body.toString();
    }

    // What every request shares. If any of it changed between requests, Ollama would reload.
    private static ObjectNode baseBody(String model) {
        ObjectNode body = new ObjectMapper().createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        body.put("keep_alive", KEEP_ALIVE);
        body.putObject("options").put("num_ctx", CONTEXT_TOKENS);
        return body;
    }

    /** Reads the timing fields from a reply. Missing ones are 0, so an older Ollama still works. */
    static Timing timing(JsonNode body) {
        return new Timing(
                body.path("load_duration").asLong(),
                body.path("prompt_eval_count").asInt(),
                body.path("prompt_eval_duration").asLong(),
                body.path("eval_count").asInt(),
                body.path("eval_duration").asLong(),
                body.path("total_duration").asLong(),
                body.path("done_reason").asText(""));
    }

    static String content(JsonNode body) throws IOException {
        JsonNode content = body.path("message").path("content");
        if (!content.isTextual()) {
            throw new IOException("Ollama's reply had no message content");
        }
        return content.textValue();
    }

    static String errorMessage(JsonNode body) {
        String message = body.path("error").asText("unknown error").replaceAll("\\p{Cntrl}", " ").strip();
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
