package edu.cpsc488.brainfeed.topics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cpsc488.brainfeed.llm.UntrustedText;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Checks the model's review of a topic. A subject it names may become a new subject everyone
 * sees, and its search query goes to YouTube, so both are held to a tight format: letters,
 * digits, spaces and a few punctuation marks, starting with a letter or digit, and short.
 * Anything else is dropped rather than fixed up.
 */
final class TopicParser {

    /**
     * The model's review, cleaned. subject and searchQuery are null when the model wrote
     * something unusable; the topic then just stays where it is.
     */
    record Review(boolean harmful, String subject, String searchQuery) {
    }

    static final int MAX_SUBJECT = 30;
    static final int MAX_QUERY = 80;
    static final int MAX_SUBJECT_WORDS = 3;

    // Letters and digits in any language, plus spaces and & ' . , + # ( ) / : -
    // No < > " ` so a name can't look like markup or break out of our prompt's tags later.
    static final Pattern NAME = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} &'.,+#()/:-]*$");
    static final Pattern QUERY = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} &'.,+#:/-]{2,}$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TopicParser() {
    }

    /**
     * The review in the model's reply, or empty if the JSON is broken or "harmful" isn't a real
     * true/false. That field decides whether a topic is removed, so it's never guessed.
     */
    static Optional<Review> parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json == null ? "" : UntrustedText.stripThinking(json));
        } catch (IOException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject() || !root.path("harmful").isBoolean()) {
            return Optional.empty();
        }
        String subject = name(root.get("subject"), MAX_SUBJECT);
        if (subject != null && subject.split(" ").length > MAX_SUBJECT_WORDS) {
            subject = null;
        }
        String text = UntrustedText.field(root.get("searchQuery"), MAX_QUERY);
        String query = text != null && QUERY.matcher(text).matches() ? text : null;
        return Optional.of(new Review(root.get("harmful").booleanValue(), subject, query));
    }

    /** A cleaned topic or subject name, or null if it doesn't fit the format. */
    static String name(JsonNode node, int maxLength) {
        String text = UntrustedText.field(node, maxLength);
        return text != null && NAME.matcher(text).matches() ? text : null;
    }

    /** The given search, or "<name> explained" if it's unusable (null included). */
    static String query(JsonNode node, String name) {
        String text = UntrustedText.field(node, MAX_QUERY);
        if (text != null && QUERY.matcher(text).matches()) {
            return text;
        }
        String fallback = name + " explained";
        return QUERY.matcher(fallback).matches() ? fallback : name;
    }

    /** How names are compared for uniqueness: ignoring case and spacing, like the database's lower(name) index. */
    static String key(String name) {
        return UntrustedText.oneLine(name).toLowerCase(Locale.ROOT);
    }
}
