package edu.cpsc488.brainfeed.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * A language model we can send a conversation to. {@link OllamaClient} in the app, fakes in tests.
 * Whatever it returns is untrusted: callers check every field before saving or showing it.
 */
public interface Llm {
    /** The model's name, saved next to what it wrote. */
    String model();

    /** Sends the conversation and returns the model's reply, which should be JSON matching the schema. */
    String chat(String system, String user, JsonNode schema) throws IOException, InterruptedException;
}
