package edu.cpsc488.brainfeed.topics;

import edu.cpsc488.brainfeed.feed.Topic;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SubjectGuesserTest {

    static final List<Topic> CATALOG = List.of(
            new Topic(1, "Math", null, false),
            new Topic(2, "Space", null, false),
            new Topic(3, "Chemistry", null, false),
            new Topic(4, "Programming", null, false),
            new Topic(5, "Explore", null, false),
            new Topic(10, "Chemical Reactions", 3, false),
            new Topic(11, "Baking Bread", 5, true));

    private static String guess(String name) {
        return SubjectGuesser.guess(name, CATALOG).map(Topic::name).orElse(null);
    }

    @Test
    void keywordsPickTheSubject() {
        assertEquals("Space", guess("Black Holes"));
        assertEquals("Space", guess("Mars"));
        assertEquals("Math", guess("Matrices"));
        assertEquals("Programming", guess("Rust"));
        assertEquals("Programming", guess("C++"));
    }

    @Test
    void aWordSharedWithAnExistingTopicPicksItsSubject() {
        assertEquals("Chemistry", guess("Organic Reactions"));
    }

    @Test
    void topicsInExploreDoNotCount() {
        // "Bread" is in Baking Bread, but that one was never placed, so it says nothing
        assertEquals("Explore", guess("Sourdough Bread"));
    }

    @Test
    void anythingElseGoesToExplore() {
        assertEquals("Explore", guess("Knitting"));
    }

    @Test
    void onlySubjectsInTheCatalogAreUsed() {
        // History is in the keyword list but not in this catalog
        assertEquals("Explore", guess("Roman Empire"));
        assertNull(SubjectGuesser.guess("Knitting", List.of(new Topic(1, "Math", null, false))).orElse(null));
        assertEquals(Optional.empty(), SubjectGuesser.guess("Knitting", List.of()));
    }

    @Test
    void noKeywordBelongsToTwoSubjects() {
        // otherwise the guess would depend on map order
        Map<String, String> seen = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : SubjectGuesser.KEYWORDS.entrySet()) {
            for (String word : entry.getValue()) {
                String before = seen.put(word, entry.getKey());
                assertNull(before, word + " is in " + before + " and " + entry.getKey());
            }
        }
    }
}
