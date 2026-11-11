package edu.cpsc488.brainfeed.topics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Topic names go into the catalog everyone sees, so only tidy, short names should get through.
class TopicParserTest {

    private static String reply(String... topics) {
        return "{\"topics\": [" + String.join(",", topics) + "]}";
    }

    private static String topic(String subject, String name, String query) {
        return "{\"subject\": \"" + subject + "\", \"name\": \"" + name + "\", \"searchQuery\": \"" + query + "\"}";
    }

    @Test
    void readsGoodTopics() {
        List<TopicParser.Idea> ideas = TopicParser.parse(reply(
                topic("Space", "Black Holes", "black holes explained"),
                topic("Music", "Guitar Chords", "guitar chords for beginners"))).orElseThrow();
        assertEquals(List.of(
                new TopicParser.Idea("Space", "Black Holes", "black holes explained"),
                new TopicParser.Idea("Music", "Guitar Chords", "guitar chords for beginners")), ideas);
    }

    @Test
    void anEmptyListIsFine() {
        // that's how the model says no, e.g. for a request that isn't something people learn
        assertEquals(Optional.of(List.of()), TopicParser.parse(reply()));
    }

    @Test
    void brokenRepliesAreEmpty() {
        assertEquals(Optional.empty(), TopicParser.parse("not json"));
        assertEquals(Optional.empty(), TopicParser.parse(null));
        assertEquals(Optional.empty(), TopicParser.parse("{\"topics\": \"Black Holes\"}"));
        assertEquals(Optional.empty(), TopicParser.parse("[]"));
    }

    @Test
    void dropsNamesThatLookLikeMarkupOrAreTooLong() {
        List<TopicParser.Idea> ideas = TopicParser.parse(reply(
                topic("Space", "<script>alert(1)</script>", "x explained"),
                topic("Space", "Black \\\"Holes\\\"", "x explained"),
                topic("Space", "a".repeat(TopicParser.MAX_NAME + 1), "x explained"),
                topic("<b>Space</b>", "Comets", "comets explained"),
                topic("Space", "-Dashes first", "x explained"),
                topic("Space", "Nebulae", "nebulae explained"))).orElseThrow();
        assertEquals(List.of("Nebulae"), ideas.stream().map(TopicParser.Idea::name).toList());
    }

    @Test
    void allowsNamesInOtherLanguagesAndCommonPunctuation() {
        List<TopicParser.Idea> ideas = TopicParser.parse(reply(
                topic("Languages", "Español Básico", "spanish basics"),
                topic("Programming", "C++ & C#", "c++ basics"),
                topic("History", "World War I (1914-1918)", "ww1 explained"))).orElseThrow();
        assertEquals(3, ideas.size());
    }

    @Test
    void examAndCertificationNamesKeepTheirRealSpelling() {
        // a user asked for "security+ certification" and needs exactly this topic, not a general one
        List<TopicParser.Idea> ideas = TopicParser.parse(reply(
                topic("Cybersecurity", "CompTIA Security+", "CompTIA Security+ exam prep"),
                topic("Programming", "AWS Cloud Practitioner", "AWS Cloud Practitioner exam prep"),
                topic("Health", "MCAT Exam Prep", "MCAT exam prep"))).orElseThrow();
        assertEquals(List.of("CompTIA Security+", "AWS Cloud Practitioner", "MCAT Exam Prep"),
                ideas.stream().map(TopicParser.Idea::name).toList());
        assertEquals("CompTIA Security+ exam prep", ideas.get(0).searchQuery());
    }

    @Test
    void controlCharactersAndSpacesAreCleanedUp() {
        String messy = "{\"topics\": [{\"subject\": \" Space \", \"name\": \"Black\\n\\tHoles\", \"searchQuery\": \"black holes\"}]}";
        assertEquals("Black Holes", TopicParser.parse(messy).orElseThrow().get(0).name());
    }

    @Test
    void duplicatesCountOnce() {
        List<TopicParser.Idea> ideas = TopicParser.parse(reply(
                topic("Space", "Black Holes", "a explained"),
                topic("Physics", "black holes", "b explained"))).orElseThrow();
        assertEquals(1, ideas.size());
    }

    @Test
    void keepsAtMostFive() {
        String[] six = new String[6];
        for (int i = 0; i < six.length; i++) {
            six[i] = topic("Space", "Topic " + i, "topic explained");
        }
        assertEquals(TopicParser.MAX_IDEAS, TopicParser.parse(reply(six)).orElseThrow().size());
    }

    @Test
    void aBadSearchQueryFallsBackToTheName() {
        TopicParser.Idea idea = TopicParser.parse(reply(topic("Space", "Comets", "<img>"))).orElseThrow().get(0);
        assertEquals("Comets explained", idea.searchQuery());
        TopicParser.Idea missing = TopicParser.parse("{\"topics\": [{\"subject\": \"Space\", \"name\": \"Comets\"}]}")
                .orElseThrow().get(0);
        assertEquals("Comets explained", missing.searchQuery());
    }

    @Test
    void namesAreComparedIgnoringCaseAndSpaces() {
        assertEquals(TopicParser.key("Algebra 2"), TopicParser.key("  algebra   2 "));
    }

    @Test
    void skipsThinkingBeforeTheJson() {
        assertTrue(TopicParser.parse("<think>hmm</think>" + reply(topic("Space", "Comets", "comets"))).isPresent());
    }
}
