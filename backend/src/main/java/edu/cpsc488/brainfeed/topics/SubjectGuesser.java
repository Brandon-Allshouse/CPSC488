package edu.cpsc488.brainfeed.topics;

import edu.cpsc488.brainfeed.feed.Topic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Picks a subject for a new topic in microseconds, so adding doesn't wait on the LLM. It's a
 * first guess: {@link TopicReviewer} moves the topic later if the model knows better. In order:
 * <ol>
 *   <li>a word from the name in the keyword list below ("black holes" has "hole", so Space)</li>
 *   <li>a word shared with a topic or subject we have ("Organic Reactions" shares "reaction"
 *       with Chemical Reactions, so Chemistry)</li>
 *   <li>otherwise {@link #FALLBACK}</li>
 * </ol>
 * Only subjects that exist in the catalog are returned.
 */
final class SubjectGuesser {

    /** The catch-all subject from V15. */
    static final String FALLBACK = "Explore";

    // Words that say which seeded subject a topic belongs to, compared in singular form. Not
    // meant to be complete, just enough that common topics land in the right place right away.
    static final Map<String, Set<String>> KEYWORDS = Map.ofEntries(
            Map.entry("Space", Set.of("planet", "star", "galaxy", "hole", "nebula", "comet", "asteroid", "moon",
                    "mar", "jupiter", "saturn", "astronomy", "astrophysics", "rocket", "orbit", "nasa", "telescope",
                    "universe", "cosmos", "exoplanet", "supernova", "eclipse")),
            Map.entry("Math", Set.of("math", "algebra", "calculus", "geometry", "equation", "fraction", "matrix",
                    "matrice", "probability", "number", "integral", "derivative", "vector", "proof", "topology")),
            Map.entry("Programming", Set.of("coding", "code", "python", "java", "javascript", "typescript", "rust",
                    "c++", "c#", "react", "html", "css", "sql", "linux", "git", "api", "software", "algorithm",
                    "programming", "docker", "kubernete", "aws", "cloud", "ai", "network", "computer", "hacking")),
            Map.entry("Physics", Set.of("physics", "quantum", "gravity", "force", "energy", "motion", "optics",
                    "magnet", "particle", "nuclear", "atomic")),
            Map.entry("Chemistry", Set.of("chemistry", "chemical", "molecule", "element", "acid", "reaction",
                    "polymer", "compound", "bond")),
            Map.entry("Biology", Set.of("biology", "cell", "dna", "gene", "virus", "bacteria", "animal", "plant",
                    "insect", "bird", "mammal", "dinosaur", "shark", "anatomy", "brain", "evolution", "zoology",
                    "botany", "neuroscience")),
            Map.entry("History", Set.of("history", "war", "empire", "ancient", "medieval", "revolution", "dynasty",
                    "civilization", "roman", "greek", "egypt", "viking", "renaissance", "president")),
            Map.entry("Economics", Set.of("economics", "economy", "finance", "money", "stock", "investing", "tax",
                    "budget", "inflation", "crypto", "bitcoin", "trade")),
            Map.entry("Psychology", Set.of("psychology", "behavior", "emotion", "memory", "habit", "motivation",
                    "anxiety", "therapy", "personality")),
            Map.entry("Geography", Set.of("geography", "country", "map", "continent", "capital", "culture", "city")),
            Map.entry("Earth Science", Set.of("geology", "volcano", "earthquake", "rock", "mineral", "weather",
                    "climate", "ocean", "glacier", "tornado", "hurricane")),
            Map.entry("English", Set.of("english", "grammar", "writing", "essay", "literature", "poetry", "novel",
                    "shakespeare", "vocabulary", "reading")),
            Map.entry("Languages", Set.of("spanish", "french", "german", "japanese", "chinese", "mandarin", "korean",
                    "italian", "portuguese", "russian", "arabic", "hindi", "latin", "language", "sign")),
            Map.entry("Art", Set.of("art", "drawing", "painting", "sketching", "sculpture", "photography",
                    "design", "animation", "calligraphy", "pottery", "origami")),
            Map.entry("Music", Set.of("music", "guitar", "piano", "drum", "violin", "singing", "song", "chord",
                    "ukulele", "bass", "composing")),
            Map.entry("Health", Set.of("health", "nutrition", "fitness", "exercise", "diet", "sleep", "yoga",
                    "workout", "meditation", "medicine", "running")),
            Map.entry("Philosophy", Set.of("philosophy", "ethics", "logic", "stoicism", "existentialism", "plato",
                    "aristotle", "socrate")),
            Map.entry("Government", Set.of("government", "politics", "law", "constitution", "election", "civics",
                    "court", "democracy")),
            Map.entry("Engineering", Set.of("engineering", "robot", "robotics", "electronics", "electronic", "circuit", "bridge",
                    "mechanics", "arduino", "cad", "3d")),
            Map.entry("Business", Set.of("business", "marketing", "startup", "sale", "management", "accounting",
                    "leadership", "entrepreneurship")));

    // Too common to say anything about the subject.
    private static final Set<String> STOP_WORDS = Set.of("and", "the", "for", "with", "basic", "intro",
            "introduction", "advanced", "how", "what", "learn", "learning", "beginner", "101");

    private SubjectGuesser() {
    }

    /** The subject a new topic called {@code name} should start under, or empty if there's none to use. */
    static Optional<Topic> guess(String name, List<Topic> catalog) {
        Map<String, Topic> subjects = new HashMap<>();
        Map<Integer, Topic> byId = new HashMap<>();
        for (Topic topic : catalog) {
            byId.put(topic.id(), topic);
            if (topic.parentId() == null) {
                subjects.put(topic.name(), topic);
            }
        }
        String[] words = TopicNames.singularKey(name).split(" ");
        for (String word : words) {
            for (Map.Entry<String, Set<String>> entry : KEYWORDS.entrySet()) {
                if (entry.getValue().contains(word) && subjects.containsKey(entry.getKey())) {
                    return Optional.of(subjects.get(entry.getKey()));
                }
            }
        }
        for (String word : words) {
            if (word.length() < 3 || STOP_WORDS.contains(word)) {
                continue;
            }
            for (Topic topic : catalog) {
                if (FALLBACK.equals(topic.name()) || !Set.of(TopicNames.singularKey(topic.name()).split(" ")).contains(word)) {
                    continue;
                }
                Topic subject = topic.parentId() == null ? topic : byId.get(topic.parentId());
                // Topics in Explore were never placed, so they don't tell us anything.
                if (subject != null && !FALLBACK.equals(subject.name())) {
                    return Optional.of(subject);
                }
            }
        }
        return Optional.ofNullable(subjects.get(FALLBACK));
    }
}
