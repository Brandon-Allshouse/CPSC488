package edu.cpsc488.brainfeed.topics;

import edu.cpsc488.brainfeed.feed.Topic;

import java.util.List;
import java.util.Optional;

/**
 * Finds the catalog topic someone meant when they typed a name, so "black hole", "Pre Algebra"
 * and "calculas" pick the topics we already have instead of adding near-copies. Tried in order:
 * <ol>
 *   <li>the same name, ignoring case, spacing and punctuation ({@link TopicNames#matchKey})</li>
 *   <li>the same name once words are made singular ("Black Hole" for "Black Holes")</li>
 *   <li>a small typo: an edit distance of 1 for names of 5 to 8 characters, 2 from 9 on, and
 *       none for 4 or fewer, since short words are too easy to confuse ("Java" and "Lava"). The
 *       first letter and every digit must match, so "Algebra 3" never becomes "Algebra 2".</li>
 * </ol>
 * A typo match can still be wrong ("Generics" is one letter from "Genetics"), so it's reported
 * as {@link Kind#SIMILAR} and the user can add their own spelling instead.
 */
final class TopicMatcher {

    enum Kind {
        /** the same name, maybe in a different case or plural */
        EXACT,
        /** a likely typo of a catalog name */
        SIMILAR
    }

    record Match(Topic topic, Kind kind) {
    }

    private TopicMatcher() {
    }

    /** The best catalog match for {@code name}. With {@code allowTypos} false, only {@link Kind#EXACT}. */
    static Optional<Match> find(String name, List<Topic> catalog, boolean allowTypos) {
        String key = TopicNames.matchKey(name);
        for (Topic topic : catalog) {
            if (TopicNames.matchKey(topic.name()).equals(key)) {
                return Optional.of(new Match(topic, Kind.EXACT));
            }
        }
        String singular = TopicNames.singularKey(name);
        for (Topic topic : catalog) {
            if (samePlural(key, singular, topic.name())) {
                return Optional.of(new Match(topic, Kind.EXACT));
            }
        }
        if (!allowTypos) {
            return Optional.empty();
        }
        Topic best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Topic topic : catalog) {
            String otherKey = TopicNames.matchKey(topic.name());
            int allowed = allowedDistance(Math.max(key.length(), otherKey.length()));
            if (allowed == 0 || !sameStart(key, otherKey) || !digits(key).equals(digits(otherKey))) {
                continue;
            }
            // Both as typed and made singular, so "calculas" (whose singular is "calcula") still
            // finds "Calculus".
            int distance = Math.min(distance(key, otherKey, allowed),
                    distance(singular, TopicNames.singularKey(topic.name()), allowed));
            if (distance <= allowed && distance < bestDistance) {
                best = topic;
                bestDistance = distance;
            }
        }
        return best == null ? Optional.empty() : Optional.of(new Match(best, Kind.SIMILAR));
    }

    // "Black Hole" and "Black Holes", and "Genetic" and "Genetics" (the singular rule leaves
    // words ending in "ics" alone, so that one needs the plain "add an s" check).
    private static boolean samePlural(String key, String singular, String otherName) {
        String otherKey = TopicNames.matchKey(otherName);
        return TopicNames.singularKey(otherName).equals(singular)
                || otherKey.equals(key + "s") || key.equals(otherKey + "s");
    }

    /** How many edits still count as a typo, for a name this long. */
    static int allowedDistance(int length) {
        if (length <= 4) {
            return 0;
        }
        return length <= 8 ? 1 : 2;
    }

    private static boolean sameStart(String a, String b) {
        return !a.isEmpty() && !b.isEmpty() && a.charAt(0) == b.charAt(0);
    }

    private static String digits(String text) {
        return text.replaceAll("\\D", "");
    }

    /**
     * Levenshtein distance (insert, delete or change one character), giving up early once it's
     * past {@code limit}, since we only care whether it's within the limit. Catalog names are at
     * most 50 characters, so this stays cheap even for 2,000 topics.
     */
    static int distance(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) {
            return limit + 1;
        }
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            int rowMin = current[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
                rowMin = Math.min(rowMin, current[j]);
            }
            if (rowMin > limit) {
                return limit + 1;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
