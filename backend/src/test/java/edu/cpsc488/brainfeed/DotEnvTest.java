package edu.cpsc488.brainfeed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DotEnvTest {

    @TempDir
    Path folder;

    private Map<String, String> parse(String contents) throws IOException {
        Path file = folder.resolve(".env");
        Files.writeString(file, contents);
        return DotEnv.parse(file);
    }

    @Test
    void readsKeyValueLines() throws IOException {
        Map<String, String> values = parse("DB_USER=brainfeed\nPORT=7070\n");
        assertEquals("brainfeed", values.get("DB_USER"));
        assertEquals("7070", values.get("PORT"));
    }

    @Test
    void skipsCommentsAndBlankLines() throws IOException {
        Map<String, String> values = parse("# a comment\n\nPORT=7070\nnot a setting\n");
        assertEquals(Map.of("PORT", "7070"), values);
    }

    @Test
    void quotesAroundValuesAreRemoved() throws IOException {
        Map<String, String> values = parse("A=\"hello\"\nB='world'\n");
        assertEquals("hello", values.get("A"));
        assertEquals("world", values.get("B"));
    }

    @Test
    void equalsSignsInTheValueAreKept() throws IOException {
        // base64 secrets often end in =
        assertEquals("abc123==", parse("PASSWORD_PEPPER=abc123==\n").get("PASSWORD_PEPPER"));
    }

    @Test
    void blankValueIsAnEmptyString() throws IOException {
        assertEquals("", parse("YOUTUBE_API_KEY=\n").get("YOUTUBE_API_KEY"));
    }

    @Test
    void windowsByteOrderMarkIsIgnored() throws IOException {
        // Notepad sometimes adds an invisible character at the start of the file
        Map<String, String> values = parse("﻿DB_USER=brainfeed\n");
        assertEquals("brainfeed", values.get("DB_USER"));
        assertFalse(values.containsKey("﻿DB_USER"));
    }
}
