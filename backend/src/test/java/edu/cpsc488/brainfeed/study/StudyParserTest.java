package edu.cpsc488.brainfeed.study;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The model's output is untrusted, so these check that only clean, answerable questions get through.
class StudyParserTest {

    private static final String GOOD_QUESTION = """
            {"question": "What does a derivative measure?",
             "options": ["Total change", "Rate of change at one instant", "Average change", "Area under a curve"],
             "explanation": "A derivative is the instantaneous rate of change.",
             "answer": "Rate of change at one instant"}""";

    private static String reply(String... questions) {
        return "{\"summary\": \"The video explains derivatives.\", \"questions\": [" + String.join(",", questions) + "]}";
    }

    @Test
    void readsAGoodReply() {
        StudyMaterial material = StudyParser.parse(reply(GOOD_QUESTION, GOOD_QUESTION)).orElseThrow();
        assertEquals("The video explains derivatives.", material.summary());
        assertEquals(2, material.questions().size());
        StudyMaterial.Question question = material.questions().get(0);
        assertEquals("What does a derivative measure?", question.question());
        assertEquals(4, question.options().size());
        // the answer is turned into its position in the options
        assertEquals(1, question.answerIndex());
    }

    @Test
    void dropsAQuestionWhoseAnswerIsNotOneOfTheOptions() {
        String wrongKey = GOOD_QUESTION.replace("\"answer\": \"Rate of change at one instant\"", "\"answer\": \"Speed\"");
        StudyMaterial material = StudyParser.parse(reply(wrongKey, GOOD_QUESTION)).orElseThrow();
        assertEquals(1, material.questions().size());
    }

    @Test
    void answerMatchIgnoresCaseAndExtraSpaces() {
        String sloppy = GOOD_QUESTION.replace("\"answer\": \"Rate of change at one instant\"", "\"answer\": \"  rate of change at ONE instant \"");
        assertEquals(1, StudyParser.parse(reply(sloppy)).orElseThrow().questions().get(0).answerIndex());
    }

    @Test
    void dropsQuestionsWithTheWrongNumberOfOptions() {
        String three = GOOD_QUESTION.replace("\"Area under a curve\"", "").replace("\"Average change\",", "\"Average change\"");
        assertEquals(Optional.empty(), StudyParser.parse(reply(three)));
    }

    @Test
    void dropsQuestionsWithTwoOptionsThatSayTheSameThing() {
        String repeated = GOOD_QUESTION.replace("\"Total change\"", "\"average CHANGE\"");
        assertEquals(Optional.empty(), StudyParser.parse(reply(repeated)));
    }

    @Test
    void rejectsBrokenJsonAndMissingSummary() {
        assertEquals(Optional.empty(), StudyParser.parse("not json"));
        assertEquals(Optional.empty(), StudyParser.parse(""));
        assertEquals(Optional.empty(), StudyParser.parse(null));
        assertEquals(Optional.empty(), StudyParser.parse("[]"));
        assertEquals(Optional.empty(), StudyParser.parse("{\"questions\": [" + GOOD_QUESTION + "]}"));
        assertEquals(Optional.empty(), StudyParser.parse("{\"summary\": 5, \"questions\": [" + GOOD_QUESTION + "]}"));
    }

    @Test
    void needsAtLeastOneGoodQuestion() {
        assertEquals(Optional.empty(), StudyParser.parse(reply()));
    }

    @Test
    void keepsAtMostThreeQuestions() {
        String four = reply(GOOD_QUESTION, GOOD_QUESTION, GOOD_QUESTION, GOOD_QUESTION);
        assertEquals(3, StudyParser.parse(four).orElseThrow().questions().size());
    }

    @Test
    void rejectsTextThatIsTooLong() {
        String longSummary = "{\"summary\": \"" + "a".repeat(StudyParser.MAX_SUMMARY + 1) + "\", \"questions\": [" + GOOD_QUESTION + "]}";
        assertEquals(Optional.empty(), StudyParser.parse(longSummary));
    }

    @Test
    void removesControlCharactersAndExtraWhitespace() {
        String messy = "{\"summary\": \"Line one\\n\\nline\\u0007 two  \", \"questions\": [" + GOOD_QUESTION + "]}";
        assertEquals("Line one line two", StudyParser.parse(messy).orElseThrow().summary());
    }

    @Test
    void rejectsASummaryThatJustRepeatsOurInstructions() {
        // this really happened with qwen3:4b during testing
        String echo = "{\"summary\": \"The summary should be 2-3 plain sentences that teach the main idea of the video's topic.\", "
                + "\"questions\": [" + GOOD_QUESTION + "]}";
        assertEquals(Optional.empty(), StudyParser.parse(echo));
    }

    @Test
    void normalSummariesAreNotMistakenForAnEcho() {
        assertTrue(StudyParser.parse(reply(GOOD_QUESTION)).isPresent());
        String topical = "{\"summary\": \"Stars make their own light through nuclear fusion, while planets only reflect it.\", "
                + "\"questions\": [" + GOOD_QUESTION + "]}";
        assertTrue(StudyParser.parse(topical).isPresent());
    }

    @Test
    void stripsLetterLabelsFromOptionsAndTheQuestion() {
        // gemma4:e4b wrote it like this during testing
        String labeled = """
                {"question": "Which is an evolutionary force? A) Mutation B) Natural Selection C) Climate D) Weather",
                 "options": ["A) Mutation", "B) Natural Selection", "C) Climate", "D) Weather"],
                 "explanation": "Natural selection is one.", "answer": "B) Natural Selection"}""";
        StudyMaterial.Question question = StudyParser.parse(reply(labeled)).orElseThrow().questions().get(0);
        assertEquals("Which is an evolutionary force?", question.question());
        assertEquals(List.of("Mutation", "Natural Selection", "Climate", "Weather"), question.options());
        assertEquals(1, question.answerIndex());
    }

    @Test
    void leavesOptionsAloneWhenOnlySomeStartLikeALabel() {
        String one = GOOD_QUESTION.replace("\"Total change\"", "\"A. Total change\"");
        assertEquals("A. Total change", StudyParser.parse(reply(one)).orElseThrow().questions().get(0).options().get(0));
    }

    @Test
    void skipsThinkingBeforeTheJson() {
        String thinking = "<think>The user wants a quiz {not json}</think>\n" + reply(GOOD_QUESTION);
        assertTrue(StudyParser.parse(thinking).isPresent());
    }

    @Test
    void optionsComeBackInTheSameOrder() {
        List<String> options = StudyParser.parse(reply(GOOD_QUESTION)).orElseThrow().questions().get(0).options();
        assertEquals(List.of("Total change", "Rate of change at one instant", "Average change", "Area under a curve"), options);
    }
}
