package edu.cpsc488.brainfeed.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Tests writing the security log to a file and reading it back for the admin page.
class SecurityLogFileTest {

    @TempDir
    Path folder;

    @Test
    void readsNothingBeforeAnythingIsLogged() {
        SecurityLogFile file = new SecurityLogFile(folder.resolve("security.log"), 1_000_000);
        assertTrue(file.readRecent(10).isEmpty());
    }

    @Test
    void createsTheFolderAndFileOnFirstWrite() {
        Path path = folder.resolve("logs").resolve("security.log");
        new SecurityLogFile(path, 1_000_000).append("login", "success", "5", "10.0.0.1", "-");
        assertTrue(Files.exists(path));
    }

    @Test
    void readsEventsBackNewestFirst() {
        SecurityLogFile file = new SecurityLogFile(folder.resolve("security.log"), 1_000_000);
        file.append("login", "failure", "-", "10.0.0.1", "wrong password");
        file.append("login", "success", "5", "10.0.0.1", "-");

        List<SecurityLogFile.Entry> events = file.readRecent(10);
        assertEquals(2, events.size());
        assertEquals("success", events.get(0).outcome());
        assertEquals("5", events.get(0).user());
        assertEquals("failure", events.get(1).outcome());
        assertEquals("wrong password", events.get(1).detail());
        assertEquals("10.0.0.1", events.get(1).ip());
    }

    @Test
    void returnsOnlyTheNewestWhenThereAreMore() {
        SecurityLogFile file = new SecurityLogFile(folder.resolve("security.log"), 1_000_000);
        for (int i = 1; i <= 5; i++) {
            file.append("login", "success", String.valueOf(i), "10.0.0.1", "-");
        }
        List<SecurityLogFile.Entry> events = file.readRecent(2);
        assertEquals(List.of("5", "4"), events.stream().map(SecurityLogFile.Entry::user).toList());
    }

    @Test
    void skipsLinesThatWereEditedByHand() throws IOException {
        Path path = folder.resolve("security.log");
        SecurityLogFile file = new SecurityLogFile(path, 1_000_000);
        file.append("login", "success", "5", "10.0.0.1", "-");
        Files.writeString(path, "not a real log line\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        assertEquals(1, file.readRecent(10).size());
    }

    @Test
    void startsANewFileWhenTheOldOneIsFull() {
        Path path = folder.resolve("security.log");
        SecurityLogFile file = new SecurityLogFile(path, 100);
        for (int i = 0; i < 5; i++) {
            file.append("login", "success", String.valueOf(i), "10.0.0.1", "some detail text");
        }
        assertTrue(Files.exists(folder.resolve("security.log.1")));
        // the current file only has what was written since it was started
        assertTrue(file.readRecent(100).size() < 5);
    }

    @Test
    void cleanedValuesCantAddFakeLinesOrColumns() {
        // SecurityLog.clean runs before append; tabs and newlines would break the file's format
        SecurityLogFile file = new SecurityLogFile(folder.resolve("security.log"), 1_000_000);
        file.append("login", "failure", "-", "10.0.0.1", SecurityLog.clean("bad\tinput\nlogin\tsuccess"));

        List<SecurityLogFile.Entry> events = file.readRecent(10);
        assertEquals(1, events.size());
        assertEquals("bad_input_login_success", events.get(0).detail());
    }
}