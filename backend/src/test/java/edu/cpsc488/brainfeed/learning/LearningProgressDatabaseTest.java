package edu.cpsc488.brainfeed.learning;

import edu.cpsc488.brainfeed.db.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the existing isolated test DB and every real migration, including V16. No application data. */
class LearningProgressDatabaseTest {
    private static final String VIDEO = "video000001";
    private DataSource source;
    private LearningProgressRepository repository;
    private long first;
    private long second;

    @BeforeEach void start() {
        source = TestDatabase.get();
        TestDatabase.reset();
        first = TestDatabase.addUser("learner_one");
        second = TestDatabase.addUser("learner_two");
        TestDatabase.addVideo(VIDEO, TestDatabase.topicId("Math"));
        TestDatabase.exec("INSERT INTO saved_videos (user_id, youtube_id) VALUES (?, ?), (?, ?)",
                first, VIDEO, second, VIDEO);
        repository = new LearningProgressRepository(source);
    }

    @Test void unscheduledNotebookEntriesDefaultToPlanned() {
        assertEquals("planned", repository.list(first).getFirst().status());
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM learning_progress"));
        assertNull(repository.list(first).getFirst().completedAt());
    }

    @Test void persistsAcrossConnectionsAndSeparatesOwnersOfTheSameVideo() {
        assertTrue(repository.saveStatus(first, VIDEO, "completed"));
        assertTrue(repository.saveStatus(second, VIDEO, "in_progress"));
        var reload = new LearningProgressRepository(source);
        assertEquals("completed", reload.list(first).getFirst().status());
        assertEquals("in_progress", reload.list(second).getFirst().status());
        assertTrue(reload.list(Long.MAX_VALUE).isEmpty());
        assertFalse(reload.saveStatus(Long.MAX_VALUE, VIDEO, "completed"));
        assertFalse(reload.saveStatus(first, "video999999", "completed"));
    }

    @Test void repeatedCompletionKeepsTheOriginalCompletionAndReviewTimes() {
        repository.saveStatus(first, VIDEO, "completed");
        OffsetDateTime completedAt = repository.list(first).getFirst().completedAt();
        assertNotNull(completedAt);
        assertTrue(repository.markReviewed(first, VIDEO));
        var reviewed = repository.list(first).getFirst();
        assertTrue(repository.saveStatus(first, VIDEO, "completed"));
        var repeated = new LearningProgressRepository(source).list(first).getFirst();
        assertEquals(completedAt, repeated.completedAt());
        assertEquals(reviewed.reviewedAt(), repeated.reviewedAt());
        assertEquals(reviewed.updatedAt(), repeated.updatedAt());
    }

    @Test void reopeningClearsDatesAndStopsReviewEligibility() {
        repository.saveStatus(first, VIDEO, "completed");
        repository.markReviewed(first, VIDEO);
        repository.saveStatus(first, VIDEO, "in_progress");
        var reopened = repository.list(first).getFirst();
        assertNull(reopened.completedAt());
        assertNull(reopened.reviewedAt());
        assertFalse(repository.markReviewed(first, VIDEO));
        assertNull(LearningProgressService.reviewDueAt(reopened));
    }

    @Test void reviewsUseServerTimeAndRescheduleSevenDaysAhead() {
        assertFalse(repository.markReviewed(first, VIDEO));
        repository.saveStatus(first, VIDEO, "completed");
        assertTrue(repository.markReviewed(first, VIDEO));
        var progress = repository.list(first).getFirst();
        assertNotNull(progress.reviewedAt());
        assertFalse(progress.reviewedAt().isBefore(progress.completedAt()));
        assertEquals(progress.reviewedAt().plusDays(7), LearningProgressService.reviewDueAt(progress));
        assertFalse(repository.markReviewed(Long.MAX_VALUE, VIDEO));
    }

    @Test void removingANotebookEntryCascadesOnlyThatOwnersProgress() {
        repository.saveStatus(first, VIDEO, "completed");
        repository.saveStatus(second, VIDEO, "in_progress");
        TestDatabase.exec("DELETE FROM saved_videos WHERE user_id = ? AND youtube_id = ?", first, VIDEO);
        assertTrue(repository.list(first).isEmpty());
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM learning_progress WHERE user_id = ?", first));
        assertEquals("in_progress", repository.list(second).getFirst().status());
        assertFalse(repository.markReviewed(first, VIDEO));
    }

    @Test void deletingAVideoCascadesBothOwnersProgress() {
        repository.saveStatus(first, VIDEO, "completed");
        repository.saveStatus(second, VIDEO, "in_progress");
        TestDatabase.exec("DELETE FROM videos WHERE youtube_id = ?", VIDEO);
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM learning_progress"));
    }

    @Test void databaseRejectsInvalidStatesAndOrphanProgress() throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            String prefix = "INSERT INTO learning_progress(user_id,youtube_id,status) VALUES (" + first + ",'" + VIDEO + "',";
            assertThrows(SQLException.class, () -> statement.executeUpdate(prefix + "'bad')"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(prefix + "'completed')"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO learning_progress(user_id,youtube_id,status) VALUES (" + Long.MAX_VALUE + ",'" + VIDEO + "','planned')"));
        }
        assertEquals("planned", repository.list(first).getFirst().status());
    }
}
