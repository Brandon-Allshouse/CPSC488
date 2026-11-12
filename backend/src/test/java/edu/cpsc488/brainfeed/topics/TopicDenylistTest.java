package edu.cpsc488.brainfeed.topics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicDenylistTest {

    @Test
    void blocksObviousWords() {
        assertTrue(TopicDenylist.blocks("Porn"));
        assertTrue(TopicDenylist.blocks("Free Porn Videos"));
        assertTrue(TopicDenylist.blocks("NSFW Art"));
        assertTrue(TopicDenylist.blocks("How To Kill"));
    }

    @Test
    void spellingTricksDoNotGetPastIt() {
        assertTrue(TopicDenylist.blocks("P0RN"));
        assertTrue(TopicDenylist.blocks("p.o.r.n"));
        assertTrue(TopicDenylist.blocks("p o r n"));
        assertTrue(TopicDenylist.blocks("Pórn"));
        assertTrue(TopicDenylist.blocks("S3x Tips"));
    }

    @Test
    void realTopicsWithLookAlikeWordsAreFine() {
        assertFalse(TopicDenylist.blocks("Sussex History"));
        assertFalse(TopicDenylist.blocks("Essex"));
        assertFalse(TopicDenylist.blocks("Fire Retardants"));
        assertFalse(TopicDenylist.blocks("Nazi Germany"));
        assertFalse(TopicDenylist.blocks("Naked Eye Astronomy"));
        assertFalse(TopicDenylist.blocks("Suicide Prevention"));
        assertFalse(TopicDenylist.blocks("Black Holes"));
        assertFalse(TopicDenylist.blocks("Algebra 2"));
    }

    @Test
    void foldUndoesCaseAccentsAndDigits() {
        assertEquals("porn", TopicDenylist.fold("P0RN"));
        assertEquals("cafe", TopicDenylist.fold("Café"));
        assertEquals("black holes", TopicDenylist.fold("Black  Holes"));
    }
}
