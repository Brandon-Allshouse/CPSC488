package edu.cpsc488.brainfeed.export;

import edu.cpsc488.brainfeed.admin.AdminRepository.AdminAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the admin audit log (admin_actions) into a CSV file, so admins can keep a copy outside
 * the app or hand it to someone reviewing what moderators did (NIST SP 800-92 log retention).
 */
public final class AuditLogExport {

    static final List<String> HEADER = List.of("id", "time_utc", "admin", "action", "target", "reason", "ip");

    private AuditLogExport() {
    }

    /** One CSV row per action, in the order given. Times are UTC so every reader sees the same value. */
    public static List<List<String>> rows(List<AdminAction> actions) {
        List<List<String>> rows = new ArrayList<>(actions.size());
        for (AdminAction action : actions) {
            rows.add(List.of(
                    Long.toString(action.id()),
                    action.createdAt() == null ? ""
                            : action.createdAt().withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    // The admin's account may have been deleted since (admin_id is set to NULL).
                    action.admin() == null ? "(deleted account)" : action.admin(),
                    action.action(),
                    action.target(),
                    nullToEmpty(action.reason()),
                    nullToEmpty(action.ip())));
        }
        return rows;
    }

    /**
     * Writes the actions to a new temp file in {@code directory} and returns its path. The caller
     * must delete it. Files.createTempFile picks a random name and, on Linux, makes it readable
     * only by the backend's user, since the log contains admin IP addresses.
     */
    public static Path writeTempFile(Path directory, List<AdminAction> actions) throws IOException {
        Path file = Files.createTempFile(directory, "brainfeed-audit-", ".csv");
        try {
            CsvFile.write(file, HEADER, rows(actions));
            return file;
        } catch (IOException | RuntimeException e) {
            // Don't leave half-written files with IP addresses lying around.
            Files.deleteIfExists(file);
            throw e;
        }
    }

    /** Download name like brainfeed-audit-log-2026-11-11.csv. Built by us, never from user input. */
    public static String fileName(LocalDate day) {
        return "brainfeed-audit-log-" + day + ".csv";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
