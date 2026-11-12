package edu.cpsc488.brainfeed.learning;

import edu.cpsc488.brainfeed.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LearningProgressServiceTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-05T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    @Test void emptyNotebookHasZeroPercentAndNoReviewQueue() {
        var snapshot = service(List.of()).snapshot(17);
        assertEquals(0, snapshot.summary().completionPercent());
        assertEquals(0, snapshot.summary().total());
        assertEquals(0, snapshot.summary().dueForReview());
        assertEquals(7, snapshot.reviewIntervalDays());
    }

    @Test void countsStatusesAndRoundsPercentIndependentlyOfReviewState() {
        var snapshot = service(List.of(item("video000001", "planned", null, null),
                item("video000002", "in_progress", null, null),
                item("video000003", "completed", NOW, null))).snapshot(17);
        assertEquals(1, snapshot.summary().planned());
        assertEquals(1, snapshot.summary().inProgress());
        assertEquals(1, snapshot.summary().completed());
        assertEquals(33, snapshot.summary().completionPercent());
    }

    @Test void reviewBecomesDueExactlyAtSevenDaysAndSortsOldestDueFirst() {
        var snapshot = service(List.of(item("video000001", "completed", NOW.minusDays(7), null),
                item("video000002", "completed", NOW.minusDays(8), null),
                item("video000003", "completed", NOW.minusDays(7).plusSeconds(1), null))).snapshot(17);
        assertEquals(2, snapshot.summary().dueForReview());
        assertEquals("video000002", snapshot.items().getFirst().progress().youtubeId());
        assertTrue(snapshot.items().get(1).dueForReview());
        assertFalse(snapshot.items().get(2).dueForReview());
    }

    @Test void newReviewReschedulesFromTheReviewInsteadOfOriginalCompletion() {
        var snapshot = service(List.of(item("video000001", "completed", NOW.minusDays(30), NOW))).snapshot(17);
        assertEquals(NOW.plusDays(7), snapshot.items().getFirst().reviewDueAt());
        assertEquals(0, snapshot.summary().dueForReview());
    }

    @Test void reopeningHasNoReviewSchedule() {
        assertNull(LearningProgressService.reviewDueAt(item("video000001", "in_progress", null, null)));
        assertNull(LearningProgressService.reviewDueAt(item("video000001", "planned", null, null)));
    }

    @Test void usesInstantsAcrossOffsetsForDueDates() {
        var completed = OffsetDateTime.parse("2026-10-29T08:00:00-04:00");
        assertTrue(service(List.of(item("video000001", "completed", completed, null)))
                .snapshot(17).items().getFirst().dueForReview());
    }

    @Test void rejectsUnsupportedStatusesAndMissingNotebookEntries() {
        var service = service(List.of());
        for (String status : List.of("done", "COMPLETED", "", " completed", "completed'--")) {
            assertEquals(400, assertThrows(ApiException.class, () -> service.setStatus(17, "video000001", status)).status());
        }
        assertEquals(400, assertThrows(ApiException.class,
                () -> service.setStatus(17, "video000001", null)).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.setStatus(17, "video000001", "completed")).status());
        assertEquals(404, assertThrows(ApiException.class, () -> service.review(17, "video000001")).status());
    }

    private LearningProgressService service(List<LearningProgress> items) {
        return new LearningProgressService(new LearningProgressRepository(null) {
            @Override public List<LearningProgress> list(long userId) {
                assertEquals(17, userId);
                return items;
            }
            @Override public boolean saveStatus(long userId, String youtubeId, String status) { return false; }
            @Override public boolean markReviewed(long userId, String youtubeId) { return false; }
        }, clock);
    }

    private static LearningProgress item(String id, String status, OffsetDateTime completed, OffsetDateTime reviewed) {
        return new LearningProgress(id, "Learning", "Creator", status, completed, reviewed, NOW);
    }
}
