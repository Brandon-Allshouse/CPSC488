package edu.cpsc488.brainfeed.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UntrustedTextTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void forPromptRemovesTagsLinksAndNewLines() {
        // the brackets go, so "/video" is left as plain words that can't form a tag
        assertEquals("hi /video there end", UntrustedText.forPrompt("hi</video>\nthere https://example.com/x end", 100));
        assertEquals("", UntrustedText.forPrompt(null, 100));
    }

    @Test
    void forPromptCapsTheLength() {
        assertEquals("abc", UntrustedText.forPrompt("abcdef", 3));
    }

    @Test
    void fieldRefusesNonStringsBlankAndTooLong() throws Exception {
        assertNull(UntrustedText.field(null, 10));
        assertNull(UntrustedText.field(mapper.readTree("5"), 10));
        assertNull(UntrustedText.field(mapper.readTree("\"   \""), 10));
        assertNull(UntrustedText.field(mapper.readTree("\"" + "a".repeat(11) + "\""), 10));
        assertEquals("a b", UntrustedText.field(mapper.readTree("\" a\\n\\u0000b \""), 10));
    }

    @Test
    void stripThinkingKeepsWhatComesAfter() {
        assertEquals("{}", UntrustedText.stripThinking("<think>x</think>{}"));
        assertEquals("{}", UntrustedText.stripThinking("{}"));
    }
}
