package edu.cpsc488.brainfeed.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cpsc488.brainfeed.llm.UntrustedText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks what the LLM wrote before we save it. The schema in {@link StudyPrompt} makes the shape
 * likely but not guaranteed, and small models still slip up, so we treat the output like any
 * other untrusted input: every field is cleaned and length-checked, and a question is only kept if
 * its answer matches one of its options exactly. A quiz with a wrong answer key is worse than none.
 */
final class StudyParser {

    static final int MAX_SUMMARY = 1000;
    static final int MAX_QUESTION = 300;
    static final int MAX_OPTION = 200;
    static final int MAX_EXPLANATION = 500;
    static final int OPTIONS = 4;
    static final int MAX_QUESTIONS = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // "A) ", "b. ", "3: " at the start of an option
    private static final Pattern OPTION_LABEL = Pattern.compile("^[A-Da-d1-4][).:]\\s+");
    // " A) Mutation B) Natural Selection ..." tacked onto the end of a question
    private static final Pattern LISTED_OPTIONS = Pattern.compile("\\s+[Aa1][).:]\\s.*[Bb2][).:]\\s.*$");

    private StudyParser() {
    }

    /**
     * Turns the model's JSON into study material. Bad questions are dropped. Returns empty when
     * the JSON is broken, the summary is missing, or no question survived.
     */
    static Optional<StudyMaterial> parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json == null ? "" : stripThinking(json));
        } catch (IOException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        String summary = text(root.get("summary"), MAX_SUMMARY);
        if (summary == null || echoesInstructions(summary)) {
            return Optional.empty();
        }
        List<StudyMaterial.Question> questions = new ArrayList<>();
        for (JsonNode item : root.path("questions")) {
            question(item).ifPresent(questions::add);
            if (questions.size() == MAX_QUESTIONS) {
                break;
            }
        }
        return questions.isEmpty() ? Optional.empty() : Optional.of(new StudyMaterial(summary, List.copyOf(questions)));
    }

    private static Optional<StudyMaterial.Question> question(JsonNode item) {
        String question = text(item.get("question"), MAX_QUESTION);
        String explanation = text(item.get("explanation"), MAX_EXPLANATION);
        String answer = text(item.get("answer"), MAX_OPTION);
        JsonNode rawOptions = item.get("options");
        if (question == null || explanation == null || answer == null
                || rawOptions == null || !rawOptions.isArray() || rawOptions.size() != OPTIONS) {
            return Optional.empty();
        }
        List<String> cleaned = new ArrayList<>();
        for (JsonNode rawOption : rawOptions) {
            String option = text(rawOption, MAX_OPTION);
            if (option == null) {
                return Optional.empty();
            }
            cleaned.add(option);
        }
        // Gemma sometimes writes "A) Mutation" and repeats the list in the question. The page
        // shows its own buttons, so the labels only get in the way. Only stripped when every
        // option has one, so an option that really starts with "A. " is left alone.
        if (cleaned.stream().allMatch(option -> OPTION_LABEL.matcher(option).find())) {
            cleaned.replaceAll(option -> OPTION_LABEL.matcher(option).replaceFirst(""));
            answer = OPTION_LABEL.matcher(answer).replaceFirst("");
            question = LISTED_OPTIONS.matcher(question).replaceFirst("");
        }
        List<String> options = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int answerIndex = -1;
        for (String option : cleaned) {
            // two options that read the same would make the question unanswerable
            if (option.isEmpty() || !seen.add(option.toLowerCase(Locale.ROOT))) {
                return Optional.empty();
            }
            if (option.equalsIgnoreCase(answer)) {
                answerIndex = options.size();
            }
            options.add(option);
        }
        if (answerIndex < 0) {
            return Optional.empty();
        }
        return Optional.of(new StudyMaterial.Question(question, List.copyOf(options), answerIndex, explanation));
    }

    /** A cleaned, non-blank string of at most maxLength characters, or null if it isn't one. */
    static String text(JsonNode node, int maxLength) {
        return UntrustedText.field(node, maxLength);
    }

    /**
     * True if the text repeats a run of {@link #ECHO_WORDS} words from our instructions. Small
     * models sometimes answer "The summary should be 2-3 plain sentences..." instead of writing
     * one; the same check catches a video that talked the model into reciting its prompt.
     */
    static boolean echoesInstructions(String text) {
        String[] words = words(text);
        for (int i = 0; i + ECHO_WORDS <= words.length; i++) {
            if (INSTRUCTION_RUNS.contains(String.join(" ", Arrays.copyOfRange(words, i, i + ECHO_WORDS)))) {
                return true;
            }
        }
        return false;
    }

    // Long enough that normal sentences about a topic don't match by chance.
    static final int ECHO_WORDS = 6;
    private static final Set<String> INSTRUCTION_RUNS = runs(StudyPrompt.SYSTEM);

    private static Set<String> runs(String text) {
        String[] words = words(text);
        Set<String> runs = new HashSet<>();
        for (int i = 0; i + ECHO_WORDS <= words.length; i++) {
            runs.add(String.join(" ", Arrays.copyOfRange(words, i, i + ECHO_WORDS)));
        }
        return runs;
    }

    private static String[] words(String text) {
        String letters = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
        return letters.isEmpty() ? new String[0] : letters.split(" ");
    }

    static String stripThinking(String text) {
        return UntrustedText.stripThinking(text);
    }
}
