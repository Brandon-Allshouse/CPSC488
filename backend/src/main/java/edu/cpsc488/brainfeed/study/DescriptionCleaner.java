package edu.cpsc488.brainfeed.study;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shrinks a YouTube description down to the part that's about the subject, before it goes into
 * the prompt. Most descriptions are half links, hashtags, "subscribe" lines, gear lists and
 * sponsor codes. On a CPU every prompt token costs time (about 5 ms each with gemma4:e4b), and the
 * junk also distracts a small model, so we drop it:
 * <ol>
 *   <li>Each line loses its links, hashtags, @handles, timestamps and emoji.</li>
 *   <li>Lines that are only promotion (subscribe, follow us, sponsor, affiliate, gear) or social
 *       links, lines with hardly any letters, and repeated lines are dropped.</li>
 *   <li>Each sentence left is scored by how many words it shares with the title and topic, with
 *       a bonus for being near the top (descriptions usually start with what the video is about)
 *       and a penalty for channel-promotion words. Past the first few sentences, ones that share
 *       no word with the title or topic are dropped. The best ones that fit in the budget are
 *       kept, in their original order so they still read well.</li>
 * </ol>
 * This only picks which of the uploader's sentences to keep; it doesn't make the text trusted.
 * {@link StudyPrompt} still cleans and caps the result like before.
 */
final class DescriptionCleaner {

    private static final Pattern LINE_BREAKS = Pattern.compile("\\R");
    // http links, www. links and bare ones like t.me/name or instagram.com/name
    private static final Pattern LINKS = Pattern.compile(
            "(?i)(https?://\\S+|www\\.\\S+|\\b[\\w-]+(\\.[\\w-]+)*\\.(com|org|net|io|me|ly|gg|tv|in|co|edu|app|link|to)\\b(/\\S*)?)");
    private static final Pattern HASHTAGS = Pattern.compile("#[\\p{L}\\p{N}_]+");
    private static final Pattern HANDLES = Pattern.compile("(?<![\\p{L}\\p{N}])@[\\w.]+");
    // 0:00, 12:34, 1:02:03 (chapter markers); the chapter's title is kept
    private static final Pattern TIMESTAMPS = Pattern.compile("\\b\\d{1,2}:\\d{2}(:\\d{2})?\\b");
    // emoji and other pictographs, plus the invisible joiners and selectors emoji are built from
    private static final Pattern SYMBOLS = Pattern.compile("[\\p{So}\\p{Sk}\\p{Cs}\\x{FE0F}\\x{200D}\\x{20E3}]");
    // bullets and separators at the start of a line
    private static final Pattern LEADING_BULLETS = Pattern.compile("^[\\s\\-–—•*·|>=_~+]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern SPACE_BEFORE_MARK = Pattern.compile("\\s+([,.;:!?])");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");
    private static final Pattern NOT_LETTERS = Pattern.compile("[^\\p{L}\\p{N}]+");

    // A line with one of these is promotion, however long it is.
    private static final List<String> PROMOTION = List.of(
            "subscribe", "like and share", "like, share", "like | share", "hit the bell", "notification bell",
            "patreon", "merch", "sponsor", "affiliate", "use code", "promo code", "discount code",
            "business inquir", "all rights reserved", "copyright", "music by", "follow us", "follow me",
            "connect with me", "my setup", "gear i use", "newsletter", "commission", "link in bio",
            "links below", "click the link", "buy me a coffee", "comment below");
    // A short line with one of these is a social link whose URL was removed ("Instagram:").
    private static final List<String> SOCIAL = List.of(
            "facebook", "instagram", "twitter", "tiktok", "linkedin", "telegram", "discord", "pinterest",
            "pintrest", "snapchat", "reddit", "twitch", "whatsapp", "spotify", "podcast", "website", "email",
            "e-mail");
    // Descriptions usually open with what the video is about, so these are kept even when they
    // don't repeat the title's words (a list of "key leaders" names none of them). 3 cut real
    // content in our samples; past 6 it was nearly always the channel's own advertising.
    static final int LEAD_SENTENCES = 6;
    // "Short" for SOCIAL: enough for "Follow on Instagram" but not a real sentence.
    private static final int SOCIAL_MAX_WORDS = 5;
    // Footer headings, only when they're the whole line, so "Natural resources" is kept.
    private static final Set<String> HEADINGS = Set.of(
            "aboutme", "aboutus", "resources", "credits", "chapters", "timestamps", "othervideos",
            "relatedvideos", "playlist", "contact", "contactme", "contactus", "links", "socials");
    // Channel-promotion words: a sentence full of these is about the channel, not the subject.
    private static final Set<String> PROMOTION_WORDS = Set.of(
            "our", "we", "join", "community", "platform", "course", "courses", "mentorship", "free",
            "download", "channel", "enroll", "subscribers", "website", "aspirants", "success", "mission",
            "welcome", "ultimate");
    // Words in titles that say nothing about the subject.
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "with", "what", "how", "why", "who", "when", "where", "which", "this", "that",
            "from", "your", "you", "are", "was", "were", "its", "can", "does", "into", "about", "than",
            "explained", "explain", "explains", "minute", "minutes", "seconds", "shorts", "short", "part",
            "lesson", "video", "tutorial", "learn", "learning", "made", "simple", "easy", "basics", "basic",
            "guide", "full", "best", "top", "new", "finally", "really", "every", "everything", "need", "know");

    private DescriptionCleaner() {
    }

    /**
     * The description without junk, cut down to at most budget characters by keeping the
     * sentences that best match the title and topic. Empty if nothing useful is left.
     */
    static String clean(String description, String title, String topic, int budget) {
        if (description == null || description.isBlank()) {
            return "";
        }
        List<String> lines = usefulLines(description);
        Set<String> keywords = keywords(title + " " + topic);
        if (keywords.isEmpty()) {
            // A title like "Watch this!" gives nothing to rank by, so keep it all; StudyPrompt
            // cuts it to the budget.
            return String.join(" ", lines);
        }
        return bestSentences(sentences(lines), keywords, budget);
    }

    /** Steps 1 and 2: each line cleaned, then promotion, footers and repeats dropped. */
    static List<String> usefulLines(String description) {
        List<String> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : LINE_BREAKS.split(description)) {
            String line = cleanLine(raw);
            if (line.isEmpty() || isJunk(line)) {
                continue;
            }
            // the same line twice (a repeated call to action, or the title again) adds nothing
            if (!seen.add(NOT_LETTERS.matcher(line.toLowerCase(Locale.ROOT)).replaceAll(""))) {
                continue;
            }
            kept.add(endsSentence(line));
        }
        return kept;
    }

    static String cleanLine(String line) {
        String cleaned = LINKS.matcher(line).replaceAll(" ");
        cleaned = HASHTAGS.matcher(cleaned).replaceAll(" ");
        cleaned = HANDLES.matcher(cleaned).replaceAll(" ");
        cleaned = TIMESTAMPS.matcher(cleaned).replaceAll(" ");
        cleaned = SYMBOLS.matcher(cleaned).replaceAll(" ");
        cleaned = SPACES.matcher(cleaned).replaceAll(" ").strip();
        // "In this 60-second #shorts, get" became "In this 60-second , get"
        cleaned = SPACE_BEFORE_MARK.matcher(cleaned).replaceAll("$1");
        cleaned = LEADING_BULLETS.matcher(cleaned).replaceAll("");
        // what's left after removing a link, like "Instagram:" or "Website -", loses its dangling mark
        return cleaned.replaceAll("[\\s:\\-–—|]+$", "").strip();
    }

    static boolean isJunk(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (PROMOTION.stream().anyMatch(lower::contains)) {
            return true;
        }
        int words = line.split(" ").length;
        if (words <= SOCIAL_MAX_WORDS && SOCIAL.stream().anyMatch(lower::contains)) {
            return true;
        }
        if (HEADINGS.contains(NOT_LETTERS.matcher(lower).replaceAll(""))) {
            return true;
        }
        // "=====", "---", "1." and other lines with hardly any letters
        long letters = line.codePoints().filter(Character::isLetter).count();
        return letters < 3 || letters * 2 < line.length();
    }

    // Lines joined into a paragraph need an end mark, or "Calculus" and the next line run together.
    private static String endsSentence(String line) {
        char last = line.charAt(line.length() - 1);
        return ".!?:;".indexOf(last) >= 0 ? line : line + ".";
    }

    private static List<String> sentences(List<String> lines) {
        List<String> sentences = new ArrayList<>();
        for (String line : lines) {
            for (String sentence : SENTENCE_END.split(line)) {
                if (!sentence.isBlank()) {
                    sentences.add(sentence.strip());
                }
            }
        }
        return sentences;
    }

    /**
     * Step 3: keeps the highest-scoring sentences that fit in budget, in their original order.
     * Past the first {@link #LEAD_SENTENCES}, a sentence has to share a word with the title or
     * topic, and one scoring below zero is never kept: that's the channel's own advertising
     * ("Welcome to our channel, the ultimate resource..."), which only distracts the model.
     */
    static String bestSentences(List<String> sentences, Set<String> keywords, int budget) {
        List<Integer> byScore = new ArrayList<>();
        double[] scores = new double[sentences.size()];
        for (int i = 0; i < sentences.size(); i++) {
            scores[i] = score(sentences.get(i), i, keywords);
            byScore.add(i);
        }
        // best first; on a tie the earlier sentence wins
        byScore.sort(Comparator.<Integer>comparingDouble(i -> -scores[i]).thenComparing(i -> i));
        Set<Integer> chosen = new HashSet<>();
        int used = 0;
        for (int i : byScore) {
            int length = sentences.get(i).length() + (chosen.isEmpty() ? 0 : 1);
            boolean relevant = i < LEAD_SENTENCES || sharesKeyword(sentences.get(i), keywords);
            if (relevant && scores[i] >= 0 && used + length <= budget) {
                chosen.add(i);
                used += length;
            }
        }
        if (chosen.isEmpty()) {
            // one huge sentence: StudyPrompt cuts it to the budget, which beats sending nothing
            return sentences.isEmpty() ? "" : sentences.get(byScore.get(0));
        }
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            if (chosen.contains(i)) {
                kept.add(sentences.get(i));
            }
        }
        return String.join(" ", kept);
    }

    private static boolean sharesKeyword(String sentence, Set<String> keywords) {
        return words(sentence).stream().anyMatch(word -> keywords.contains(stem(word)));
    }

    /**
     * 3 points per title or topic word the sentence shares, up to 2 points for being near the
     * top, minus 1 per channel-promotion word (at most 3) and 1 for a fragment under 4 words.
     */
    static double score(String sentence, int position, Set<String> keywords) {
        List<String> words = words(sentence);
        Set<String> hits = new HashSet<>();
        int promotion = 0;
        for (String word : words) {
            if (keywords.contains(stem(word))) {
                hits.add(stem(word));
            }
            if (PROMOTION_WORDS.contains(word)) {
                promotion++;
            }
        }
        double score = 3.0 * hits.size() + 2.0 / (1 + position / 3.0) - Math.min(promotion, 3);
        return words.size() < 4 ? score - 1 : score;
    }

    /** The title and topic's meaningful words, stemmed, so "derivatives" matches "derivative". */
    static Set<String> keywords(String text) {
        Set<String> keywords = new LinkedHashSet<>();
        for (String word : words(text)) {
            if (word.length() >= 3 && !STOPWORDS.contains(word) && word.chars().anyMatch(Character::isLetter)) {
                keywords.add(stem(word));
            }
        }
        return keywords;
    }

    private static List<String> words(String text) {
        String letters = NOT_LETTERS.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
        return letters.isEmpty() ? List.of() : List.of(letters.split(" "));
    }

    // Crude but enough here: the first 5 letters, so evolution/evolutionary and brand/branding match.
    static String stem(String word) {
        return word.length() <= 5 ? word : word.substring(0, 5);
    }
}
