package edu.cpsc488.brainfeed.topics;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * A fixed list of words that are never a BrainFeed topic, checked before anything is added. It's
 * the fast first line: a new topic is in the catalog for everyone right away, and the LLM review
 * ({@link TopicReviewer}) only gets to it seconds later, or never if Ollama is off.
 *
 * <p>It's deliberately short and obvious (porn, slurs, "how to kill"). Anything that needs
 * judgment ("Suicide Prevention" and "Serial Killers" are real things people study) is left to
 * the review and to admins. Common tricks are undone first: case, accents, look-alike digits
 * ("p0rn") and spaces or dots between letters ("p.o.r.n").
 */
final class TopicDenylist {

    // Blocked when they're a whole word, so "Sussex" and "Fire Retardants" are fine. Words that
    // are also history or science ("Nazi Germany", "Al Gore", "Naked Eye Astronomy") aren't here.
    private static final Set<String> WORDS = Set.of(
            "sex", "sexy", "porn", "porno", "nudes", "nsfw", "xxx", "erotic", "erotica", "fetish", "hentai",
            "milf", "bdsm", "onlyfans", "sexting", "camgirl", "retard", "retards", "kike", "kikes", "tranny");

    // Blocked anywhere, even inside another word or with the spaces taken out, because they don't
    // show up inside real words.
    private static final Set<String> ANYWHERE = Set.of(
            "porn", "hentai", "onlyfans", "nigger", "nigga", "faggot",
            "howtokill", "killpeople", "makeabomb", "bombmaking", "pipebomb", "buydrugs", "suicidemethod");

    private TopicDenylist() {
    }

    /** True if the name is on the list. */
    static boolean blocks(String name) {
        String text = fold(name);
        for (String word : text.split(" ")) {
            if (WORDS.contains(word)) {
                return true;
            }
        }
        String squashed = text.replace(" ", "");
        for (String bad : ANYWHERE) {
            if (squashed.contains(bad)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lowercase, accents removed, look-alike digits and symbols turned into letters, anything
     * else between letters dropped. "P0RN" and "p.o.r.n" both become "porn".
     */
    static String fold(String name) {
        String text = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            out.append(switch (c) {
                case '0' -> 'o';
                case '1', '!' -> 'i';
                case '3' -> 'e';
                case '4', '@' -> 'a';
                case '5', '$' -> 's';
                case '7' -> 't';
                case ' ', '-' -> ' ';
                default -> Character.isLetter(c) ? c : '\0';
            });
        }
        return out.toString().replace("\0", "").replaceAll(" +", " ").strip();
    }
}
