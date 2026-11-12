package edu.cpsc488.brainfeed.export;

import edu.cpsc488.brainfeed.ApiServer;
import edu.cpsc488.brainfeed.admin.AdminRepository;
import edu.cpsc488.brainfeed.auth.AuthController;
import edu.cpsc488.brainfeed.auth.PasswordHasher;
import edu.cpsc488.brainfeed.auth.PasswordPolicy;
import edu.cpsc488.brainfeed.auth.SessionRepository;
import edu.cpsc488.brainfeed.auth.UserRepository;
import edu.cpsc488.brainfeed.db.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// API test: real routes, real database (brainfeed_test), real temp files.
class ExportControllerTest {

    @TempDir
    Path tempDir;

    ApiServer server;

    @BeforeEach
    void setUp() {
        TestDatabase.reset();
        var data = TestDatabase.get();
        AuthController auth = new AuthController(new UserRepository(data),
                new SessionRepository(data, Duration.ofDays(7), Duration.ofHours(24)),
                new PasswordHasher(new byte[32], 2), new PasswordPolicy(password -> false), false);
        AdminRepository admins = new AdminRepository(data);
        server = new ApiServer(routes -> {
            auth.register(routes);
            new ExportController(() -> admins.recentActions(ExportController.EXPORT_LIMIT), auth::currentUser, tempDir)
                    .register(routes);
        });
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private long signUpAsAdmin() {
        server.send("POST", "/api/auth/register",
                "{\"email\":\"test@sru.edu\",\"username\":\"testadmin\",\"password\":\"quiet lemon river walk\"}");
        long id = TestDatabase.number("SELECT id FROM users WHERE username = 'testadmin'");
        TestDatabase.makeAdmin(id);
        return id;
    }

    @Test
    void guestsCantDownloadTheLog() {
        assertEquals(401, server.get("/api/admin/actions/export").statusCode());
    }

    @Test
    void regularUsersCantDownloadTheLog() {
        server.send("POST", "/api/auth/register",
                "{\"email\":\"test@sru.edu\",\"username\":\"testuser\",\"password\":\"quiet lemon river walk\"}");
        assertEquals(403, server.get("/api/admin/actions/export").statusCode());
    }

    @Test
    void adminGetsACsvAttachmentWithEveryAction() throws Exception {
        long adminId = signUpAsAdmin();
        TestDatabase.exec("""
                INSERT INTO admin_actions (admin_id, action, target, reason, ip)
                VALUES (?, 'ban_video', 'video000001', '=HYPERLINK("http://example.com")', '192.0.2.1')
                """, adminId);
        TestDatabase.exec("""
                INSERT INTO admin_actions (admin_id, action, target, reason, ip)
                VALUES (?, 'unban_video', 'video000001', NULL, '192.0.2.1')
                """, adminId);

        HttpResponse<String> response = server.get("/api/admin/actions/export");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"));
        assertTrue(response.headers().firstValue("Content-Disposition").orElse("")
                .matches("attachment; filename=\"brainfeed-audit-log-\\d{4}-\\d{2}-\\d{2}\\.csv\""));
        // the app-wide headers still apply to downloads
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));

        String[] lines = response.body().split("\r\n");
        assertEquals(3, lines.length, "header plus two actions");
        assertTrue(lines[0].contains("\"time_utc\""));
        // newest first, like the admin page
        assertTrue(lines[1].contains("\"unban_video\""));
        assertTrue(lines[2].contains("\"'=HYPERLINK(\"\"http://example.com\"\")\""), lines[2]);
    }

    @Test
    void anEmptyLogStillDownloadsWithAHeader() {
        signUpAsAdmin();
        HttpResponse<String> response = server.get("/api/admin/actions/export");
        assertEquals(200, response.statusCode());
        assertEquals("﻿\"id\",\"time_utc\",\"admin\",\"action\",\"target\",\"reason\",\"ip\"\r\n", response.body());
    }

    @Test
    void theTempFileIsDeletedAfterTheDownload() throws Exception {
        signUpAsAdmin();
        server.get("/api/admin/actions/export");
        try (var files = Files.list(tempDir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void aBrokenTempFolderGivesAClearErrorNotAStackTrace() throws Exception {
        signUpAsAdmin();
        Files.delete(tempDir);
        HttpResponse<String> response = server.get("/api/admin/actions/export");
        assertEquals(500, response.statusCode());
        assertTrue(response.body().contains("Couldn't build the export file"), response.body());
        Files.createDirectory(tempDir); // so JUnit can clean up
    }
}
