package edu.cpsc488.brainfeed.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CsvFileTest {

    @TempDir
    Path dir;

    @Test
    void plainTextIsQuoted() {
        assertEquals("\"testuser\"", CsvFile.cell("testuser"));
    }

    @Test
    void nullAndEmptyBecomeEmptyCells() {
        assertEquals("", CsvFile.cell(null));
        assertEquals("", CsvFile.cell(""));
    }

    @Test
    void quotesInsideACellAreDoubled() {
        assertEquals("\"said \"\"hi\"\"\"", CsvFile.cell("said \"hi\""));
    }

    @Test
    void commasAndNewlinesStayInsideOneCell() {
        assertEquals("\"a,b\nc\"", CsvFile.cell("a,b\nc"));
    }

    @Test
    void formulaStartersGetAnApostrophe() {
        // OWASP CSV injection: Excel would run these instead of showing them
        assertEquals("\"'=HYPERLINK(\"\"x\"\")\"", CsvFile.cell("=HYPERLINK(\"x\")"));
        assertEquals("\"'+1\"", CsvFile.cell("+1"));
        assertEquals("\"'-1\"", CsvFile.cell("-1"));
        assertEquals("\"'@SUM(A1)\"", CsvFile.cell("@SUM(A1)"));
        assertEquals("\"'\tx\"", CsvFile.cell("\tx"));
        assertEquals("\"'\rx\"", CsvFile.cell("\rx"));
    }

    @Test
    void formulaCharactersInTheMiddleAreLeftAlone() {
        assertEquals("\"a=b\"", CsvFile.cell("a=b"));
    }

    @Test
    void lineJoinsCellsWithCommas() {
        assertEquals("\"a\",,\"c\"", CsvFile.line(List.of("a", "", "c")));
    }

    @Test
    void writeMakesAUtf8FileWithHeaderAndRows() throws Exception {
        Path file = dir.resolve("out.csv");
        int written = CsvFile.write(file, List.of("name", "note"),
                List.of(List.of("José", "first"), List.of("testuser", "second")));

        assertEquals(2, written);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals("﻿\"name\",\"note\"\r\n\"José\",\"first\"\r\n\"testuser\",\"second\"\r\n", text);
    }

    @Test
    void writeReplacesAnOldFile() throws Exception {
        Path file = dir.resolve("out.csv");
        Files.writeString(file, "old contents that are much longer than the new file");
        CsvFile.write(file, List.of("a"), List.of());
        assertEquals("﻿\"a\"\r\n", Files.readString(file));
    }

    @Test
    void rowsWithTheWrongNumberOfCellsAreRejected() {
        Path file = dir.resolve("out.csv");
        assertThrows(IllegalArgumentException.class,
                () -> CsvFile.write(file, List.of("a", "b"), List.of(List.of("only one"))));
    }

    @Test
    void writingIntoAMissingFolderThrowsIOException() {
        Path file = dir.resolve("missing").resolve("out.csv");
        assertThrows(java.io.IOException.class, () -> CsvFile.write(file, List.of("a"), List.of()));
    }
}
