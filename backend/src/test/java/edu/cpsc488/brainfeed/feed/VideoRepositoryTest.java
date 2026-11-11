package edu.cpsc488.brainfeed.feed;

import edu.cpsc488.brainfeed.db.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Runs against the real test database (see TestDatabase).
class VideoRepositoryTest {

    VideoRepository videos;
    int math;
    int algebra;
    int space;
    long user;
    long other;

    @BeforeEach
    void setUp() {
        videos = new VideoRepository(TestDatabase.get());
        TestDatabase.reset();
        math = TestDatabase.topicId("Math");
        algebra = TestDatabase.topicId("Algebra 2");
        space = TestDatabase.topicId("Space");
        user = TestDatabase.addUser("testuser");
        other = TestDatabase.addUser("otheruser");
    }

    static Video video(String id, int topicId, String title) {
        return new Video(id, title, "Test Channel", topicId, OffsetDateTime.parse("2026-01-15T10:00:00Z"));
    }

    @Test
    void savingKeepsExistingVideosAsTheyAre() {
        videos.saveAll(List.of(video("video000001", math, "First title")));
        videos.saveAll(List.of(video("video000001", math, "Changed title"), video("video000002", math, "Second")));
        assertEquals("First title", TestDatabase.text("SELECT title FROM videos WHERE youtube_id = 'video000001'"));
        assertEquals(2, TestDatabase.number("SELECT count(*) FROM videos"));
    }

    @Test
    void bannedVideosAreNeverSavedAgain() {
        TestDatabase.exec("INSERT INTO banned_videos (youtube_id, reason) VALUES ('video000001', 'spam')");
        videos.saveAll(List.of(video("video000001", math, "Banned"), video("video000002", math, "Fine")));
        assertEquals(1, TestDatabase.number("SELECT count(*) FROM videos"));
    }

    @Test
    void aSubjectsFeedIncludesItsSubSubjects() {
        videos.saveAll(List.of(video("video000001", math, "Math"), video("video000002", algebra, "Algebra"),
                video("video000003", space, "Space")));
        List<Video> page = videos.page(List.of(math), 1, 0, 10);
        assertEquals(2, page.size());
        assertTrue(page.stream().noneMatch(v -> v.topicId() == space));
    }

    @Test
    void theSameSeedGivesTheSameOrderAndPagesDontOverlap() {
        List<Video> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(video(String.format("video%06d", i), math, "Video " + i));
        }
        videos.saveAll(many);
        List<String> first = videos.page(List.of(math), 42, 0, 10).stream().map(Video::youtubeId).toList();
        assertEquals(first, videos.page(List.of(math), 42, 0, 10).stream().map(Video::youtubeId).toList());
        List<String> second = videos.page(List.of(math), 42, 10, 10).stream().map(Video::youtubeId).toList();
        assertTrue(second.stream().noneMatch(first::contains));
        assertNotEquals(first, videos.page(List.of(math), 7, 0, 10).stream().map(Video::youtubeId).toList());
    }

    @Test
    void feedbackIsPrivateToEachUser() {
        videos.saveAll(List.of(video("video000001", math, "Liked one"), video("video000002", math, "Disliked one")));
        assertTrue(videos.saveFeedback(user, "video000001", "liked"));
        assertTrue(videos.saveFeedback(user, "video000002", "disliked"));
        assertTrue(videos.saveFeedback(other, "video000002", "liked"));
        assertEquals(List.of("video000001"), videos.findFeedback(user, "liked").stream().map(SavedVideo::youtubeId).toList());
        assertEquals(List.of("video000002"), videos.findFeedback(user, "disliked").stream().map(SavedVideo::youtubeId).toList());
        assertEquals(List.of("video000002"), videos.findFeedback(other, "liked").stream().map(SavedVideo::youtubeId).toList());
    }

    @Test
    void changingAndClearingFeedback() {
        videos.saveAll(List.of(video("video000001", math, "Video")));
        videos.saveFeedback(user, "video000001", "liked");
        videos.saveFeedback(user, "video000001", "disliked");
        assertTrue(videos.findFeedback(user, "liked").isEmpty());
        assertEquals(1, videos.findFeedback(user, "disliked").size());
        videos.saveFeedback(user, "video000001", null);
        assertTrue(videos.findFeedback(user, "disliked").isEmpty());
    }

    @Test
    void feedbackForAVideoWeDontHaveIsRefused() {
        assertFalse(videos.saveFeedback(user, "unknown0001", "liked"));
    }
}
