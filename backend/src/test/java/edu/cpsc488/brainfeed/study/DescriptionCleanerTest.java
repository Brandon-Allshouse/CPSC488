package edu.cpsc488.brainfeed.study;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The description cleanup that runs before the prompt. The examples are shaped like real
// descriptions from the feed: a real first paragraph, then links, hashtags and promotion.
class DescriptionCleanerTest {

    static final String TITLE = "What is a Derivative? #calculus #math";
    static final String TOPIC = "Calculus";

    @Test
    void emptyOrMissingDescriptionsStayEmpty() {
        assertEquals("", DescriptionCleaner.clean(null, TITLE, TOPIC, 1000));
        assertEquals("", DescriptionCleaner.clean("   \n  ", TITLE, TOPIC, 1000));
    }

    @Test
    void removesLinksHashtagsHandlesTimestampsAndEmoji() {
        String line = DescriptionCleaner.cleanLine(
                "🔥 0:00 Intro to derivatives https://example.com/x www.test.org t.me/channel #calculus @testuser ✅");
        assertEquals("Intro to derivatives", line);
    }

    @Test
    void removedHashtagsDoNotLeaveAStrayComma() {
        assertEquals("In this 60-second, get a breakdown",
                DescriptionCleaner.cleanLine("In this 60-second #shorts, get a breakdown"));
    }

    @Test
    void keepsTheTextOfChapterMarkers() {
        assertEquals("What a limit is", DescriptionCleaner.cleanLine("1:02:03 What a limit is"));
    }

    @Test
    void dropsPromotionAndSocialLines() {
        List<String> lines = DescriptionCleaner.usefulLines("""
                A derivative measures how fast something changes.
                🔔 Subscribe for more calculus videos!
                Like | Share | Comment below
                Follow me on Instagram: https://instagram.com/testuser
                Instagram
                instagram.com/testuser
                Use code TESTUSER for 10% off
                Business inquiries: test@sru.edu
                ==========
                #calculus #math #derivatives
                """);
        assertEquals(List.of("A derivative measures how fast something changes."), lines);
    }

    @Test
    void keepsRealSentencesThatOnlyMentionASocialSite() {
        // a long line about Twitter's history is content, not a link
        String line = "Twitter was started in 2006 and changed how news spreads around the world.";
        assertEquals(List.of(line), DescriptionCleaner.usefulLines(line));
    }

    @Test
    void headingsOnlyCountWhenTheyAreTheWholeLine() {
        assertEquals(List.of("Natural resources are used up faster than they grow back."),
                DescriptionCleaner.usefulLines("Resources:\nNatural resources are used up faster than they grow back."));
    }

    @Test
    void dropsRepeatedLines() {
        List<String> lines = DescriptionCleaner.usefulLines("""
                Derivatives are slopes.
                derivatives are slopes!
                Integrals are areas.
                """);
        assertEquals(List.of("Derivatives are slopes.", "Integrals are areas."), lines);
    }

    @Test
    void linesWithoutAnEndMarkGetOneSoTheyDontRunTogether() {
        assertEquals(List.of("Calculus.", "Linear algebra."), DescriptionCleaner.usefulLines("Calculus\nLinear algebra"));
    }

    @Test
    void keywordsComeFromTheTitleAndTopicWithoutFillerWords() {
        Set<String> keywords = DescriptionCleaner.keywords("What is a Derivative? Explained in 60 seconds #shorts Calculus");
        assertEquals(Set.of("deriv", "calcu"), keywords);
    }

    @Test
    void stemmingMatchesPluralsAndLongerForms() {
        assertEquals(DescriptionCleaner.stem("derivative"), DescriptionCleaner.stem("derivatives"));
        assertEquals(DescriptionCleaner.stem("evolution"), DescriptionCleaner.stem("evolutionary"));
        assertEquals("brand", DescriptionCleaner.stem("branding"));
    }

    @Test
    void sentencesAboutTheTitleScoreHigherThanPromotion() {
        Set<String> keywords = DescriptionCleaner.keywords(TITLE + " " + TOPIC);
        double onTopic = DescriptionCleaner.score("A derivative is the slope of a curve at one point.", 5, keywords);
        double promotion = DescriptionCleaner.score("Join our community and download our free course today.", 5, keywords);
        assertTrue(onTopic > promotion);
        assertTrue(promotion < 0);
    }

    @Test
    void earlierSentencesGetABonus() {
        Set<String> keywords = Set.of("deriv");
        String sentence = "Slopes tell you how steep a line is.";
        assertTrue(DescriptionCleaner.score(sentence, 0, keywords) > DescriptionCleaner.score(sentence, 9, keywords));
    }

    @Test
    void keepsTheBestSentencesInTheirOriginalOrder() {
        List<String> sentences = List.of(
                "Welcome back to the channel.",
                "Today is a fun one.",
                "We film every week.",
                "Our merch store has new shirts.",
                "A derivative is the slope of a curve at one point.",
                "Calculus has two halves: derivatives and integrals.");
        String kept = DescriptionCleaner.bestSentences(sentences, DescriptionCleaner.keywords(TITLE + " " + TOPIC), 120);
        assertEquals("A derivative is the slope of a curve at one point. Calculus has two halves: derivatives and integrals.",
                kept);
    }

    @Test
    void sentencesPastTheStartNeedToShareAWordWithTheTitle() {
        // plenty of room, but the off-topic seventh sentence still isn't sent
        List<String> sentences = List.of("Slopes are steep or flat.", "Lines have one slope.", "Curves change slope.",
                "Tangent lines touch once.", "Limits come first.", "Rates are speeds of change.", "The weather is nice today.",
                "So every derivative is a rate.");
        String kept = DescriptionCleaner.bestSentences(sentences, Set.of("deriv"), 1000);
        assertFalse(kept.contains("weather"));
        assertTrue(kept.contains("Rates are speeds of change."));
        assertTrue(kept.contains("So every derivative is a rate."));
    }

    @Test
    void neverGoesOverTheBudget() {
        StringBuilder description = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            description.append("Derivative fact number ").append(i).append(" about calculus slopes.\n");
        }
        String cleaned = DescriptionCleaner.clean(description.toString(), TITLE, TOPIC, 500);
        assertTrue(cleaned.length() <= 500, "was " + cleaned.length());
        assertTrue(cleaned.startsWith("Derivative fact number 0"));
    }

    @Test
    void oneHugeSentenceIsStillSentForThePromptToCut() {
        String huge = "Derivatives " + "and more derivatives ".repeat(100);
        assertFalse(DescriptionCleaner.clean(huge, TITLE, TOPIC, 200).isEmpty());
    }

    @Test
    void aTitleWithNoUsefulWordsKeepsTheCleanedText() {
        assertEquals("A derivative is a slope.",
                DescriptionCleaner.clean("A derivative is a slope.\nSubscribe!", "What is this?", "", 1000));
    }

    @Test
    void aRealisticDescriptionShrinksAndKeepsTheLesson() {
        String description = """
                ⚔️ Revolt of 1857 – Was it India's First War of Independence or just a mutiny?
                In this 60-second #shorts, get a crisp and clear breakdown of:
                ✅ Causes of the Revolt
                ✅ Key Leaders like Rani Lakshmibai and Tantia Tope
                👉 Like | Share | Comment "1857" if you're preparing!
                🔔 Subscribe for more History Shorts!
                Welcome to Test Learning – Your Ultimate Resource for Exam Preparation!
                At Test Learning, we are dedicated to empowering the next generation of leaders through our platform.
                Follow US
                Facebook
                facebook.com/profile.php?id=61550078080696
                #ias #upsc #history #facts
                """;
        String cleaned = DescriptionCleaner.clean(description, "Revolt of 1857 Explained in 60 Sec", "Modern History", 1000);
        assertTrue(cleaned.contains("Revolt of 1857"));
        assertTrue(cleaned.contains("Rani Lakshmibai"));
        assertFalse(cleaned.contains("Subscribe"));
        assertFalse(cleaned.contains("facebook"));
        assertFalse(cleaned.contains("#"));
        assertFalse(cleaned.contains("dedicated to empowering"));
        assertFalse(cleaned.contains("Ultimate Resource"));
        assertTrue(cleaned.length() < description.length() / 2);
    }
}
