package edu.cpsc488.brainfeed.topics;

import edu.cpsc488.brainfeed.feed.Topic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicMatcherTest {

    static final List<Topic> CATALOG = List.of(
            new Topic(1, "Math", null, false),
            new Topic(2, "Space", null, false),
            new Topic(3, "Algebra 2", 1, false),
            new Topic(4, "Pre-Algebra", 1, false),
            new Topic(5, "Calculus", 1, false),
            new Topic(6, "Black Holes", 2, true),
            new Topic(7, "Genetics", 2, false),
            new Topic(8, "Java", 2, false),
            new Topic(9, "Data Structures and Algorithms", 2, false));

    private static Optional<TopicMatcher.Match> find(String name) {
        return TopicMatcher.find(name, CATALOG, true);
    }

    private static void matches(String typed, int id, TopicMatcher.Kind kind) {
        Optional<TopicMatcher.Match> match = find(typed);
        assertTrue(match.isPresent(), typed + " should match");
        assertEquals(id, match.get().topic().id(), typed);
        assertEquals(kind, match.get().kind(), typed);
    }

    private static void noMatch(String typed) {
        assertEquals(Optional.empty(), find(typed), typed + " should not match");
    }

    @Test
    void sameNameInAnyCase() {
        matches("algebra 2", 3, TopicMatcher.Kind.EXACT);
        matches("MATH", 1, TopicMatcher.Kind.EXACT);
    }

    @Test
    void punctuationAndAmpersandsDoNotMatter() {
        matches("Pre Algebra", 4, TopicMatcher.Kind.EXACT);
        matches("data structures & algorithms", 9, TopicMatcher.Kind.EXACT);
    }

    @Test
    void singularAndPluralAreTheSameTopic() {
        matches("black hole", 6, TopicMatcher.Kind.EXACT);
        matches("Genetic", 7, TopicMatcher.Kind.EXACT);
    }

    @Test
    void smallTyposStillFindTheTopic() {
        matches("calculas", 5, TopicMatcher.Kind.SIMILAR);
        matches("blak holes", 6, TopicMatcher.Kind.SIMILAR);
    }

    @Test
    void shortWordsNeedToBeExact() {
        // one letter off, but four letters is too short to guess
        noMatch("Lava");
        noMatch("Jave");
    }

    @Test
    void differentNumbersAreDifferentTopics() {
        noMatch("Algebra 3");
        noMatch("Algebra");
    }

    @Test
    void theFirstLetterHasToMatch() {
        noMatch("Kalculus");
    }

    @Test
    void unrelatedNamesDoNotMatch() {
        noMatch("Cooking");
        noMatch("Holes");
    }

    @Test
    void typoMatchingCanBeTurnedOff() {
        assertEquals(Optional.empty(), TopicMatcher.find("calculas", CATALOG, false));
        // plurals still count as the same name
        assertTrue(TopicMatcher.find("black hole", CATALOG, false).isPresent());
    }

    @Test
    void allowedDistanceGrowsWithLength() {
        assertEquals(0, TopicMatcher.allowedDistance(4));
        assertEquals(1, TopicMatcher.allowedDistance(5));
        assertEquals(1, TopicMatcher.allowedDistance(8));
        assertEquals(2, TopicMatcher.allowedDistance(9));
    }

    @Test
    void distanceCountsEdits() {
        assertEquals(0, TopicMatcher.distance("calculus", "calculus", 2));
        assertEquals(1, TopicMatcher.distance("calculas", "calculus", 2));
        assertEquals(2, TopicMatcher.distance("kitten", "sittin", 2));
        // gives up past the limit
        assertEquals(3, TopicMatcher.distance("abcdef", "uvwxyz", 2));
    }
}
