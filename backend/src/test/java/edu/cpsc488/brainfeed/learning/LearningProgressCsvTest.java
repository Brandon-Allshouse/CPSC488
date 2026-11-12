package edu.cpsc488.brainfeed.learning;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LearningProgressCsvTest {
    @Test void emptyPlansHaveAHeaderAndUtf8ByteOrderMark() {
        byte[] csv = LearningProgressCsv.encode(snapshot(List.of()));
        assertEquals(0xef, csv[0] & 0xff);
        assertEquals(0xbb, csv[1] & 0xff);
        assertEquals(0xbf, csv[2] & 0xff);
        String text = new String(csv, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("\ufeff\"Video ID\",\"Title\""));
        assertEquals(1, text.split("\r\n").length);
    }

    @Test void quotesUnicodeCommasAndDoubleQuotesWhileKeepingDatesAndReviewState() {
        var now = OffsetDateTime.parse("2026-11-12T10:00:00Z");
        var progress = new LearningProgress("video000001", "Café, \"Java\"", "Learning", "completed", now, null, now);
        var item = new LearningProgressService.Item(progress, now.plusDays(7), false);
        String text = new String(LearningProgressCsv.encode(snapshot(List.of(item))), StandardCharsets.UTF_8);
        assertTrue(text.contains("\"Café, \"\"Java\"\"\""));
        assertTrue(text.contains("2026-11-19T10:00Z"));
        assertTrue(text.contains("https://www.youtube.com/watch?v=video000001"));
        assertTrue(text.contains("\"false\""));
    }

    @Test void preventsFormulasIncludingLeadingWhitespaceAndEscapesLineBreaks() {
        for (String dangerous : List.of("=1+1", "+cmd", "-42", "@sum", "  =1+1", "\n=1+1", "\ttext", "\rtext")) {
            assertTrue(LearningProgressCsv.cell(dangerous).startsWith("\"'"), dangerous);
        }
        assertEquals("\"ordinary\"", LearningProgressCsv.cell("ordinary"));
        assertEquals("\"line\nnext\"", LearningProgressCsv.cell("line\nnext"));
        assertEquals("\"\"", LearningProgressCsv.cell(null));
    }

    @Test void writerFailuresPropagateInsteadOfProducingAnApparentlyCompleteFile() {
        Writer broken = new Writer() {
            @Override public void write(char[] chars, int offset, int count) throws IOException { throw new IOException("disk full"); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        assertThrows(IOException.class, () -> LearningProgressCsv.write(broken, snapshot(List.of())));
    }

    private static LearningProgressService.Snapshot snapshot(List<LearningProgressService.Item> items) {
        return new LearningProgressService.Snapshot(items, LearningProgressService.summarize(items), 7);
    }
}
