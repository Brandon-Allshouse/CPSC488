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

    /**
     * Same as {@link #chat(String, String, JsonNode)}, but the reply stops after maxReplyTokens.
     * Callers that know how long a good reply is pass a tighter cap, so a model stuck repeating
     * itself gives up sooner. Fakes don't need to care about the cap.
     */
    default String chat(String system, String user, JsonNode schema, int maxReplyTokens)
            throws IOException, InterruptedException {
        return chat(system, user, schema);
    }

    /**
     * Loads the model into memory ahead of time, so the first real request doesn't wait for it.
     * Does nothing by default.
     */
    default void warmUp() throws IOException, InterruptedException {
    }
}
