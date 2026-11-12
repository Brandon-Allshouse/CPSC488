package edu.cpsc488.brainfeed.topics;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// The review can move topics, make subjects and remove topics, so only tidy replies get through.
class TopicParserTest {

    static String review(boolean harmful, String subject, String query) {
        return "{\"harmful\": " + harmful + ", \"subject\": \"" + subject + "\", \"searchQuery\": \"" + query + "\"}";
    }

    @Test
    void readsAGoodReview() {
        assertEquals(Optional.of(new TopicParser.Review(false, "Space", "black holes explained")),
                TopicParser.parse(review(false, "Space", "black holes explained")));
        assertEquals(true, TopicParser.parse(review(true, "Space", "x explained")).orElseThrow().harmful());
    }

    @Test
    void brokenRepliesAreEmpty() {
        assertEquals(Optional.empty(), TopicParser.parse("not json"));
        assertEquals(Optional.empty(), TopicParser.parse(null));
        assertEquals(Optional.empty(), TopicParser.parse("[]"));
        assertEquals(Optional.empty(), TopicParser.parse("{\"subject\": \"Space\"}"));
    }

    @Test
    void harmfulMustBeARealTrueOrFalse() {
        // it decides whether a topic is removed, so a string or number is never guessed at
        assertEquals(Optional.empty(), TopicParser.parse("{\"harmful\": \"false\", \"subject\": \"Space\", \"searchQuery\": \"x y z\"}"));
        assertEquals(Optional.empty(), TopicParser.parse("{\"harmful\": 0, \"subject\": \"Space\", \"searchQuery\": \"x y z\"}"));
    }

    @Test
    void badSubjectsAndQueriesAreDroppedNotFixed() {
        TopicParser.Review markup = TopicParser.parse(review(false, "<b>Space</b>", "<script>")).orElseThrow();
        assertNull(markup.subject());
        assertNull(markup.searchQuery());
        TopicParser.Review tooLong = TopicParser.parse(review(false, "a".repeat(31), "b".repeat(81))).orElseThrow();
        assertNull(tooLong.subject());
        assertNull(tooLong.searchQuery());
        // a subject is a broad field, not a sentence
        assertNull(TopicParser.parse(review(false, "The Study Of Many Things", "x explained")).orElseThrow().subject());
    }

    @Test
    void skipsAThinkingBlockBeforeTheJson() {
        assertEquals("Space", TopicParser.parse("<think>hmm</think>" + review(false, "Space", "stars explained"))
                .orElseThrow().subject());
    }

    @Test
    void queryFallsBackToNameExplained() {
        assertEquals("Black Holes explained", TopicParser.query(null, "Black Holes"));
    }

    @Test
    void keyIgnoresCaseAndSpacing() {
        assertEquals("black holes", TopicParser.key("  Black   HOLES "));
    }
}
