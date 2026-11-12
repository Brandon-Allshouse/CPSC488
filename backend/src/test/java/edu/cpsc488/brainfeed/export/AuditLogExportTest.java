package edu.cpsc488.brainfeed.export;

import edu.cpsc488.brainfeed.admin.AdminRepository.AdminAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditLogExportTest {

    @TempDir
    Path dir;

    static final AdminAction BAN = new AdminAction(7, "testadmin", "ban_user", "testuser (#2)", "spam",
            "192.0.2.1", OffsetDateTime.of(2026, 11, 11, 9, 30, 0, 0, ZoneOffset.ofHours(-5)));

    @Test
    void eachActionBecomesOneRowInHeaderOrder() {
        List<List<String>> rows = AuditLogExport.rows(List.of(BAN));
        assertEquals(1, rows.size());
        assertEquals(List.of("7", "2026-11-11T14:30:00Z", "testadmin", "ban_user", "testuser (#2)", "spam", "192.0.2.1"),
                rows.get(0));
        assertEquals(AuditLogExport.HEADER.size(), rows.get(0).size());
    }

    @Test
    void deletedAdminsAndMissingValuesStillMakeAFullRow() {
        AdminAction unban = new AdminAction(8, null, "unban_user", "testuser (#2)", null, null, null);
        assertEquals(List.of("8", "", "(deleted account)", "unban_user", "testuser (#2)", "", ""),
                AuditLogExport.rows(List.of(unban)).get(0));
    }

    @Test
    void noActionsGivesNoRows() {
        assertTrue(AuditLogExport.rows(List.of()).isEmpty());
    }

    @Test
    void tempFileHasTheHeaderAndTheRow() throws Exception {
        Path file = AuditLogExport.writeTempFile(dir, List.of(BAN));
        List<String> lines = Files.readAllLines(file);
        assertEquals("﻿\"id\",\"time_utc\",\"admin\",\"action\",\"target\",\"reason\",\"ip\"", lines.get(0));
        assertTrue(lines.get(1).contains("\"testadmin\""));
        assertTrue(file.getFileName().toString().startsWith("brainfeed-audit-"));
    }

    @Test
    void aFormulaTypedAsABanReasonCantRunInExcel() throws Exception {
        AdminAction sneaky = new AdminAction(9, "testadmin", "ban_video", "video000001", "=cmd|' /C calc'!A0",
                "192.0.2.1", OffsetDateTime.now());
        Path file = AuditLogExport.writeTempFile(dir, List.of(sneaky));
        assertTrue(Files.readString(file).contains("\"'=cmd|' /C calc'!A0\""));
    }

    @Test
    void aMissingFolderThrowsAndLeavesNothingBehind() {
        Path missing = dir.resolve("gone");
        assertThrows(java.io.IOException.class, () -> AuditLogExport.writeTempFile(missing, List.of(BAN)));
    }

    @Test
    void fileNameUsesTheDate() {
        assertEquals("brainfeed-audit-log-2026-11-11.csv", AuditLogExport.fileName(LocalDate.of(2026, 11, 11)));
    }
}
