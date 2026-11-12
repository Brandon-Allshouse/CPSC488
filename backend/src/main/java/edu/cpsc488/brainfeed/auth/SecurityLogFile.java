package edu.cpsc488.brainfeed.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the security log (see {@link SecurityLog}) in a file as well as the console, so admins can
 * read it on the admin page and it survives restarts (OWASP Logging Cheat Sheet: logs must be
 * stored somewhere they can be reviewed).
 *
 * <p>One event per line, with tab-separated fields:
 * {@code time event outcome user ip detail}. {@link SecurityLog#clean} has already removed tabs,
 * newlines and other control characters from every value, so user input can't add fake lines or
 * columns.
 *
 * <p>When the file passes {@code maxBytes} it's renamed to {@code security.log.1} (replacing the
 * previous one) and a new file is started, so the log can't fill the disk.
 */
public final class SecurityLogFile {

    private static final Logger log = LoggerFactory.getLogger(SecurityLogFile.class);
    private static final int FIELDS = 6;

    /** One line of the file. {@code user}, {@code ip} and {@code detail} are "-" when unknown. */
    public record Entry(String time, String event, String outcome, String user, String ip, String detail) {
    }

    private final Path path;
    private final long maxBytes;

    public SecurityLogFile(Path path, long maxBytes) {
        this.path = path;
        this.maxBytes = maxBytes;
    }

    /**
     * Adds one event to the end of the file, creating the file and its folder if needed. A failure
     * to write is reported on the console but never thrown: a full disk mustn't stop people logging in.
     */
    public synchronized void append(String event, String outcome, String user, String ip, String detail) {
        String line = String.join("\t", Instant.now().toString(), event, outcome, user, ip, detail) + "\n";
        try {
            Path folder = path.toAbsolutePath().getParent();
            if (folder != null) {
                Files.createDirectories(folder);
            }
            rotateIfFull();
            Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Could not write to the security log file {}: {}", path, e.getMessage());
        }
    }

    /**
     * The newest {@code limit} events, newest first. Lines that don't have the expected fields
     * (e.g. edited by hand) are skipped. An empty list if nothing has been logged yet.
     */
    public synchronized List<Entry> readRecent(int limit) {
        List<Entry> entries = new ArrayList<>();
        if (limit <= 0 || !Files.exists(path)) {
            return entries;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not read the security log file {}: {}", path, e.getMessage());
            return entries;
        }
        // Walk backwards from the end of the file so the newest events come first.
        for (int i = lines.size() - 1; i >= 0 && entries.size() < limit; i--) {
            String[] fields = lines.get(i).split("\t", -1);
            if (fields.length == FIELDS) {
                entries.add(new Entry(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5]));
            }
        }
        return entries;
    }

    private void rotateIfFull() throws IOException {
        if (Files.exists(path) && Files.size(path) >= maxBytes) {
            Path previous = path.resolveSibling(path.getFileName() + ".1");
            Files.move(path, previous, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}