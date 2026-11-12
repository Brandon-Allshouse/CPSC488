package edu.cpsc488.brainfeed.learning;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** A bounded, UTF-8 download of the session owner's whole notebook, independent of UI filters. */
public final class LearningProgressCsv {

    static final List<String> HEADER = List.of("Video ID", "Title", "Creator", "Status",
            "Completed at (UTC offset)", "Last reviewed at (UTC offset)",
            "Review due at (UTC offset)", "Due for review", "Watch URL");

    private LearningProgressCsv() {
    }

    public static byte[] encode(LearningProgressService.Snapshot snapshot) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Writer writer = new OutputStreamWriter(bytes, StandardCharsets.UTF_8)) {
            write(writer, snapshot);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create learning progress download.", exception);
        }
        return bytes.toByteArray();
    }

    /** Writer-based file output also makes quoting, Unicode and failed writes independently testable. */
    static void write(Writer writer, LearningProgressService.Snapshot snapshot) throws IOException {
        writer.write('\ufeff'); // Makes Excel recognize the UTF-8 encoding.
        writeRow(writer, HEADER);
        for (LearningProgressService.Item item : snapshot.items()) {
            LearningProgress progress = item.progress();
            writeRow(writer, List.of(progress.youtubeId(), progress.title(), progress.creator(), progress.status(),
                    progress.completedAt() == null ? "" : progress.completedAt().toString(),
                    progress.reviewedAt() == null ? "" : progress.reviewedAt().toString(),
                    item.reviewDueAt() == null ? "" : item.reviewDueAt().toString(),
                    Boolean.toString(item.dueForReview()), "https://www.youtube.com/watch?v=" + progress.youtubeId()));
        }
    }

    private static void writeRow(Writer writer, List<String> cells) throws IOException {
        for (int index = 0; index < cells.size(); index++) {
            if (index != 0) {
                writer.write(',');
            }
            writer.write(cell(cells.get(index)));
        }
        writer.write("\r\n");
    }

    /** Quotes commas/newlines and blocks spreadsheet formulas, including after leading whitespace. */
    static String cell(String value) {
        String safe = value == null ? "" : value;
        String leading = safe.stripLeading();
        if ((!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0)
                || (!safe.isEmpty() && "\t\r\n".indexOf(safe.charAt(0)) >= 0)) {
            safe = "'" + safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
