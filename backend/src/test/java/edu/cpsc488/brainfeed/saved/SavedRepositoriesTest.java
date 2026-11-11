package edu.cpsc488.brainfeed.saved;

import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.db.TestDatabase;
import edu.cpsc488.brainfeed.feed.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The study notebook's two repositories, against the real test database (see TestDatabase).
class SavedRepositoriesTest {

    SavedVideoRepository saved;
    StudyNoteRepository notes;
    long user;
    long other;

    @BeforeEach
    void setUp() {
        saved = new SavedVideoRepository(TestDatabase.get());
        notes = new StudyNoteRepository(TestDatabase.get());
        TestDatabase.reset();
        user = TestDatabase.addUser("testuser");
        other = TestDatabase.addUser("otheruser");
        int math = TestDatabase.topicId("Math");
        TestDatabase.addVideo("video000001", math);
        TestDatabase.addVideo("video000002", math);
    }

    @Test
    void savingIsPerUserAndRepeatable() {
        assertTrue(saved.save(user, "video000001"));
        assertTrue(saved.save(user, "video000001"));
        assertEquals(List.of("video000001"), saved.findByUser(user).stream().map(Video::youtubeId).toList());
        assertTrue(saved.findByUser(other).isEmpty());
    }

    @Test
    void onlyVideosWeHaveCanBeSaved() {
        assertFalse(saved.save(user, "unknown0001"));
    }

    @Test
    void removingOnlyAffectsYourOwnCopy() {
        saved.save(user, "video000001");
        saved.save(other, "video000001");
        saved.remove(user, "video000001");
        assertTrue(saved.findByUser(user).isEmpty());
        assertEquals(1, saved.findByUser(other).size());
    }

    @Test
    void atMost500EvenWithParallelSaves() throws Exception {
        // fill to one under the limit directly, then race several saves for the last spot
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < SavedVideoRepository.MAX_SAVED_VIDEOS + 9; i++) {
            String id = String.format("fill%07d", i);
            TestDatabase.addVideo(id, TestDatabase.topicId("Math"));
            ids.add(id);
        }
        TestDatabase.exec("INSERT INTO saved_videos (user_id, youtube_id) SELECT ?, youtube_id FROM videos "
                + "WHERE youtube_id LIKE 'fill%' ORDER BY youtube_id LIMIT ?", user, SavedVideoRepository.MAX_SAVED_VIDEOS - 1);
        List<String> rest = ids.subList(SavedVideoRepository.MAX_SAVED_VIDEOS - 1, ids.size());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (String id : rest) {
                results.add(pool.submit(() -> {
                    try {
                        saved.save(user, id);
                    } catch (ApiException e) {
                        // full
                    }
                }));
            }
            for (Future<?> result : results) {
                result.get();
            }
        } finally {
            pool.shutdown();
        }
        assertEquals(SavedVideoRepository.MAX_SAVED_VIDEOS, TestDatabase.number("SELECT count(*) FROM saved_videos WHERE user_id = ?", user));
        assertEquals(409, assertThrows(ApiException.class, () -> saved.save(user, "video000001")).status());
    }

    @Test
    void notesNeedANotebookEntryOfYourOwn() {
        assertTrue(notes.save(user, "video000001", "SYN, SYN/ACK, ACK").isEmpty());
        saved.save(other, "video000001");
        // their entry doesn't let me write a note
        assertTrue(notes.save(user, "video000001", "mine").isEmpty());
        saved.save(user, "video000001");
        assertEquals("mine", notes.save(user, "video000001", "mine").orElseThrow().note());
    }

    @Test
    void notesArePrivate() {
        saved.save(user, "video000001");
        saved.save(other, "video000001");
        notes.save(user, "video000001", "my note");
        notes.save(other, "video000001", "their note");
        assertEquals(List.of("my note"), notes.list(user).stream().map(StudyNote::note).toList());
        assertEquals(List.of("their note"), notes.list(other).stream().map(StudyNote::note).toList());
    }

    @Test
    void editingANoteReplacesIt() {
        saved.save(user, "video000001");
        notes.save(user, "video000001", "first");
        notes.save(user, "video000001", "second");
        assertEquals(List.of("second"), notes.list(user).stream().map(StudyNote::note).toList());
    }

    @Test
    void removingTheVideoRemovesItsNote() {
        saved.save(user, "video000001");
        notes.save(user, "video000001", "note");
        saved.remove(user, "video000001");
        assertTrue(notes.list(user).isEmpty());
    }

    @Test
    void theDatabaseCapsNoteLength() {
        saved.save(user, "video000001");
        assertThrows(RuntimeException.class, () -> notes.save(user, "video000001", "a".repeat(2001)));
    }
}
