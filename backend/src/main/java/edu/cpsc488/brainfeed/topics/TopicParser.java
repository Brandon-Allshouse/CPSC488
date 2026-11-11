package edu.cpsc488.brainfeed.topics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cpsc488.brainfeed.llm.UntrustedText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks the topics the LLM proposed. Names end up in the shared catalog that everyone sees, so
 * they're held to a tight format: letters, digits, spaces and a few punctuation marks, starting
 * with a letter or digit, and short. Anything else is dropped rather than fixed up.
 */
final class TopicParser {

    /** One proposed topic, cleaned. Not yet compared with the catalog (TopicSuggestions does that). */
    record Idea(String subject, String name, String searchQuery) {
    }

    static final int MAX_NAME = 40;
    static final int MAX_SUBJECT = 30;
    static final int MAX_QUERY = 80;
    static final int MAX_IDEAS = 5;

    // Letters and digits in any language, plus spaces and & ' . , + # ( ) / : -
    // No < > " ` so a name can't look like markup or break out of our prompt's tags later.
    static final Pattern NAME = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} &'.,+#()/:-]*$");
    static final Pattern QUERY = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} &'.,+#:/-]{2,}$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TopicParser() {
    }

    /** The usable ideas in the model's reply, or empty if the JSON is broken. */
    static Optional<List<Idea>> parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json == null ? "" : UntrustedText.stripThinking(json));
        } catch (IOException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject() || !root.path("topics").isArray()) {
            return Optional.empty();
        }
        List<Idea> ideas = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : root.path("topics")) {
            String name = name(item.get("name"), MAX_NAME);
            String subject = name(item.get("subject"), MAX_SUBJECT);
            if (name == null || subject == null || !seen.add(key(name))) {
                continue;
            }
            ideas.add(new Idea(subject, name, query(item.get("searchQuery"), name)));
            if (ideas.size() == MAX_IDEAS) {
                break;
            }
        }
        return Optional.of(ideas);
    }

    /** A cleaned topic or subject name, or null if it doesn't fit the format. */
    static String name(JsonNode node, int maxLength) {
        String text = UntrustedText.field(node, maxLength);
        return text != null && NAME.matcher(text).matches() ? text : null;
    }

    /** The model's YouTube search, or "<name> explained" if it wrote something unusable. */
    static String query(JsonNode node, String name) {
        String text = UntrustedText.field(node, MAX_QUERY);
        if (text != null && QUERY.matcher(text).matches()) {
            return text;
        }
        String fallback = name + " explained";
        return QUERY.matcher(fallback).matches() ? fallback : name;
    }

    /** How names are compared: ignoring case and spacing, like the database's lower(name) index. */
    static String key(String name) {
        return UntrustedText.oneLine(name).toLowerCase(Locale.ROOT);
    }
}
