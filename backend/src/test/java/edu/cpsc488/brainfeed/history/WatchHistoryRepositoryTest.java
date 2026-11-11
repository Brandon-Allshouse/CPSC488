package edu.cpsc488.brainfeed.history;

import edu.cpsc488.brainfeed.db.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Runs against the real test database (see TestDatabase).
class WatchHistoryRepositoryTest {

    WatchHistoryRepository history;
    long user;
    long other;

    @BeforeEach
    void setUp() {
        history = new WatchHistoryRepository(TestDatabase.get(), WatchHistoryController.LIMIT);
        TestDatabase.reset();
        user = TestDatabase.addUser("testuser");
        other = TestDatabase.addUser("otheruser");
        int math = TestDatabase.topicId("Math");
        for (int i = 0; i < 30; i++) {
            TestDatabase.addVideo(String.format("video%06d", i), math);
        }
    }

    static String id(int i) {
        return String.format("video%06d", i);
    }

    List<String> ids(long userId) {
        return history.list(userId).stream().map(WatchHistoryRepository.WatchedVideo::youtubeId).toList();
    }

    @Test
    void newestFirstAndWatchingAgainMovesItUp() {
        history.record(user, id(1));
        TestDatabase.exec("UPDATE watch_history SET watched_at = now() - interval '1 minute'");
        history.record(user, id(2));
        assertEquals(List.of(id(2), id(1)), ids(user));
        history.record(user, id(1));
        assertEquals(List.of(id(1), id(2)), ids(user));
    }

    @Test
    void keepsOnlyTheNewest25() {
        for (int i = 0; i < 30; i++) {
            history.record(user, id(i));
            TestDatabase.exec("UPDATE watch_history SET watched_at = watched_at - interval '1 second'");
        }
        List<String> kept = ids(user);
        assertEquals(WatchHistoryController.LIMIT, kept.size());
        assertEquals(id(29), kept.get(0));
        assertFalse(kept.contains(id(0)));
    }

    @Test
    void onlyVideosWeHaveCanBeRecorded() {
        assertFalse(history.record(user, "unknown0001"));
    }

    @Test
    void historyAndClearingOnlyTouchYourOwn() {
        history.record(user, id(1));
        history.record(other, id(2));
        assertEquals(List.of(id(1)), ids(user));
        // can't remove someone else's entry
        assertFalse(history.remove(user, id(2)));
        history.clear(user);
        assertTrue(ids(user).isEmpty());
        assertEquals(List.of(id(2)), ids(other));
    }

    @Test
    void removingOne() {
        history.record(user, id(1));
        assertTrue(history.remove(user, id(1)));
        assertFalse(history.remove(user, id(1)));
    }
}
