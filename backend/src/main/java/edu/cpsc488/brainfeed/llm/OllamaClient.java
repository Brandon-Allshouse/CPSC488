package edu.cpsc488.brainfeed.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Talks to Ollama (https://ollama.com), which runs a free, open model on our own machine, so
 * summaries cost nothing and no video data leaves the server. In Docker it's the "ollama"
 * service, which only the backend can reach (see docker-compose.yml).
 *
 * <p>Ollama has no login of its own, which is why it must never get a public port.
 */
public class OllamaClient implements Llm {

    // CPU-only laptops take 10-60 seconds per video, plus loading the model after it's been idle.
    static final Duration TIMEOUT = Duration.ofMinutes(4);

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
        HttpRequest request = HttpRequest.newBuilder(chatUrl)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(model, system, user, schema)))
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
        return content(body);
    }

    static String requestBody(String model, String system, String user, JsonNode schema) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        // Thinking models (Qwen 3) would spend a minute reasoning first. We don't need it here.
        body.put("think", false);
        // Ollama only lets the model produce JSON matching this schema.
        body.set("format", schema);
        ObjectNode options = body.putObject("options");
        // Low temperature keeps it close to the description instead of making things up.
        options.put("temperature", 0.3);
        options.put("num_ctx", 4096);
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);
        return body.toString();
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
