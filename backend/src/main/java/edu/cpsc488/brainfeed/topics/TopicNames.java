package edu.cpsc488.brainfeed.topics;

import edu.cpsc488.brainfeed.llm.UntrustedText;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cleans up and checks a topic name someone typed. The name goes straight into the shared catalog
 * that everyone sees and into a YouTube search, so it's held to a tight format: 1 to 3 words, 2 to
 * 40 characters, letters and digits in any language plus spaces and {@code & + ' - . #} (so
 * "C++", "C#" and "Node.js" work), starting with a letter or digit.
 */
final class TopicNames {

    static final int MIN_LENGTH = 2;
    static final int MAX_LENGTH = 40;
    static final int MAX_WORDS = 3;

    // Tighter than TopicParser.NAME: no ( ) / : , since a person typing one to three words never
    // needs them. No < > " ` either, so a name can't look like markup or close a prompt tag.
    private static final Pattern ALLOWED = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} &+'.#-]*$");
    // Zero-width spaces, joiners, direction marks: invisible, so "Alge​bra" would look
    // like "Algebra" but be a different name.
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cf}]");
    // Control characters other than tabs and line breaks (which just become spaces). A NUL byte
    // in a name is never a typo, so it's refused instead of quietly removed.
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\t\\n\\r]]");
    // Small words stay lowercase in titles ("Lord of the Flies"), except as the first word.
    private static final Set<String> SMALL_WORDS = Set.of("a", "an", "and", "as", "at", "by", "for", "in", "of",
            "on", "or", "the", "to", "vs", "with");

    private static final String SYMBOLS = "Use letters, numbers and spaces (and & + ' - . # if you need them).";

    /** A name that passed, or why it didn't (a message for the user). */
    record Checked(String name, String problem) {
        boolean ok() {
            return problem == null;
        }
    }

    private TopicNames() {
    }

    /**
     * Normalizes what was typed (NFKC, so full-width letters and ligatures become plain ones;
     * invisible characters removed; spaces collapsed; title case) and checks it.
     */
    static Checked check(String typed) {
        if (typed == null) {
            return new Checked(null, "Type a topic, like Black Holes.");
        }
        // Long input is refused before the work below, so it can't be used to waste CPU.
        if (typed.length() > MAX_LENGTH * 4) {
            return new Checked(null, "Keep it under " + MAX_LENGTH + " characters.");
        }
        if (CONTROL.matcher(typed).find()) {
            return new Checked(null, SYMBOLS);
        }
        String text = Normalizer.normalize(typed, Normalizer.Form.NFKC);
        text = UntrustedText.oneLine(INVISIBLE.matcher(text).replaceAll(""));
        // Curly apostrophes from phone keyboards: "Newton’s Laws"
        text = text.replace('’', '\'').replace('‘', '\'');
        if (text.isEmpty()) {
            return new Checked(null, "Type a topic, like Black Holes.");
        }
        if (text.length() < MIN_LENGTH) {
            return new Checked(null, "Type at least " + MIN_LENGTH + " characters.");
        }
        if (text.length() > MAX_LENGTH) {
            return new Checked(null, "Keep it under " + MAX_LENGTH + " characters.");
        }
        if (text.split(" ").length > MAX_WORDS) {
            return new Checked(null, "Use one to three words, like Black Holes.");
        }
        if (!ALLOWED.matcher(text).matches()) {
            return new Checked(null, SYMBOLS);
        }
        // "42" or "2 3" isn't a topic anyone means to add.
        if (text.codePoints().noneMatch(Character::isLetter)) {
            return new Checked(null, "Type a topic with some letters in it, like Algebra 2.");
        }
        return new Checked(capitalize(text), null);
    }

    /**
     * Title case for words typed in lowercase ("black holes" becomes "Black Holes"). Words with
     * a capital in them are kept as typed, so "AP Biology", "iPhone" and "SQL" stay right. Long
     * input in all caps ("BLACK HOLES") is treated as lowercase, since that's caps lock, not an
     * acronym.
     */
    static String capitalize(String text) {
        long letters = text.codePoints().filter(Character::isLetter).count();
        boolean shouting = letters > 5 && text.equals(text.toUpperCase(Locale.ROOT));
        String[] words = (shouting ? text.toLowerCase(Locale.ROOT) : text).split(" ");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (!word.equals(word.toLowerCase(Locale.ROOT))) {
                out.add(word);
            } else if (i > 0 && SMALL_WORDS.contains(word)) {
                out.add(word);
            } else {
                out.add(upperFirst(word));
            }
        }
        return String.join(" ", out);
    }

    // "pre-algebra" becomes "Pre-Algebra", like the seeded name
    private static String upperFirst(String word) {
        StringBuilder out = new StringBuilder(word.length());
        boolean start = true;
        for (int i = 0; i < word.length(); ) {
            int cp = word.codePointAt(i);
            out.appendCodePoint(start ? Character.toTitleCase(cp) : cp);
            start = cp == '-';
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    /**
     * How names are compared when matching: lowercase, "&" as "and", hyphens as spaces and other
     * punctuation dropped, so "Pre Algebra" finds "Pre-Algebra" and "Data Structures & Algorithms"
     * finds "Data Structures and Algorithms".
     */
    static String matchKey(String name) {
        String text = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace("&", " and ").replace('-', ' ').replace('/', ' ')
                .replaceAll("[^\\p{L}\\p{N} +#]", "");
        return UntrustedText.oneLine(text);
    }

    /** {@link #matchKey} with each word made singular, so "Black Hole" and "Black Holes" are equal. */
    static String singularKey(String name) {
        List<String> words = new ArrayList<>();
        for (String word : matchKey(name).split(" ")) {
            words.add(singular(word));
        }
        return String.join(" ", words);
    }

    /**
     * A rough English singular, good enough for comparing names: "galaxies" to "galaxy",
     * "boxes" to "box", "holes" to "hole". Leaves "physics", "mathematics"-like words ending in
     * "ics", and words ending in "ss", "us" or "is" alone ("chess", "virus", "analysis").
     */
    static String singular(String word) {
        if (word.length() <= 3 || !word.endsWith("s")) {
            return word;
        }
        if (word.endsWith("ss") || word.endsWith("us") || word.endsWith("is") || word.endsWith("ics")) {
            return word;
        }
        if (word.endsWith("ies") && word.length() > 4) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes") || word.endsWith("sses")) {
            return word.substring(0, word.length() - 2);
        }
        return word.substring(0, word.length() - 1);
    }

    /**
     * The lowercase forms to look up in banned_topic_names, so a removed "Black Holes" also
     * blocks "black hole".
     */
    static List<String> banKeys(String name) {
        Set<String> keys = new LinkedHashSet<>();
        String lower = TopicParser.key(name);
        keys.add(lower);
        keys.add(singularKey(name));
        keys.add(matchKey(name));
        keys.add(lower + "s");
        if (lower.endsWith("s")) {
            keys.add(lower.substring(0, lower.length() - 1));
        }
        return List.copyOf(keys);
    }
}
