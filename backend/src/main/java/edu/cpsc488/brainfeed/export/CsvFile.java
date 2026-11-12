package edu.cpsc488.brainfeed.export;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes rows of text to a CSV file that Excel and Google Sheets open safely.
 *
 * <p>Cells are quoted per RFC 4180. A cell starting with = + - @ or a tab/carriage return gets a
 * leading apostrophe, because spreadsheet apps would otherwise run it as a formula. That is the
 * "CSV injection" attack in OWASP's cheat sheet: a reason like {@code =HYPERLINK(...)} typed into
 * the admin page could run on whoever opens the export.
 */
public final class CsvFile {

    private CsvFile() {
    }

    /** One cell, quoted and made formula-safe. Null becomes an empty cell. */
    public static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String safe = value;
        char first = safe.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            safe = "'" + safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    /** One line of cells joined by commas, without the line ending. */
    public static String line(List<String> cells) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(cells.get(i)));
        }
        return out.toString();
    }

    /**
     * Writes the header and rows to {@code file} (UTF-8, CRLF line endings as RFC 4180 says),
     * replacing anything already there.
     *
     * @return how many data rows were written, not counting the header
     * @throws IOException if the file can't be written; the caller decides what the user sees
     */
    public static int write(Path file, List<String> header, List<List<String>> rows) throws IOException {
        // try-with-resources closes (and flushes) the file even if a write fails halfway.
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            // The byte order mark makes Excel read the file as UTF-8, so names with accents show right.
            writer.write('﻿');
            writer.write(line(header));
            writer.write("\r\n");
            for (List<String> row : rows) {
                if (row.size() != header.size()) {
                    throw new IllegalArgumentException("Row has " + row.size() + " cells, header has " + header.size());
                }
                writer.write(line(row));
                writer.write("\r\n");
            }
        }
        return rows.size();
    }
}
