package edu.cpsc488.brainfeed.feed;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Uses fake versions of the database and YouTube classes so the test doesn't need either one.
class FeedRefresherTest {

    // pretends to be the topics table
    static class FakeTopics extends TopicRepository {
        List<SearchTopic> due = new ArrayList<>();
        List<Integer> marked = new ArrayList<>();
        Duration askedForAge;

        FakeTopics() {
            super(null);
        }

        @Override
        List<SearchTopic> dueForRefresh(Duration maxAge) {
            askedForAge = maxAge;
            return due;
        }

        @Override
        void markFetched(int topicId) {
            marked.add(topicId);
        }
    }

    // pretends to be the videos table
    static class FakeVideos extends VideoRepository {
        List<Video> saved = new ArrayList<>();

        FakeVideos() {
            super(null);
        }

        @Override
        void saveAll(List<Video> videos) {
            saved.addAll(videos);
        }
    }

    // pretends to be YouTube: returns one video per search, or fails on a chosen topic
    static class FakeYouTube extends YouTubeClient {
        List<String> searchedFor = new ArrayList<>();
        int failOnTopic = -1;
        boolean crash = false;

        FakeYouTube() {
            super("test-key");
        }

        @Override
        public List<Video> search(int topicId, String query) throws IOException {
            searchedFor.add(query);
            if (crash) {
                throw new IllegalStateException("something unexpected");
            }
            if (topicId == failOnTopic) {
                throw new IOException("YouTube returned HTTP 403 (quotaExceeded)");
            }
            String id = ("topic" + topicId + "aaaaaaaaaa").substring(0, 11);
            return List.of(new Video(id, "Video for " + query, "Test Channel", topicId, OffsetDateTime.now()));
        }
    }

    private final FakeTopics topics = new FakeTopics();
    private final FakeVideos videos = new FakeVideos();
    private final FakeYouTube youtube = new FakeYouTube();
    private final FeedRefresher refresher = new FeedRefresher(topics, videos, youtube);

    @Test
    void searchesEachDueTopicAndSavesTheVideos() {
        topics.due = List.of(new TopicRepository.SearchTopic(1, "math explained"),
                new TopicRepository.SearchTopic(2, "history explained"));

        refresher.run();

        assertEquals(List.of("math explained", "history explained"), youtube.searchedFor);
        assertEquals(2, videos.saved.size());
        assertEquals(List.of(1, 2), topics.marked);
    }

    @Test
    void onlyAsksForTopicsNotRefreshedInADay() {
        refresher.run();
        assertEquals(Duration.ofDays(1), topics.askedForAge);
    }

    @Test
    void nothingDueMeansNoSearches() {
        refresher.run();
        assertTrue(youtube.searchedFor.isEmpty());
    }

    @Test
    void stopsWhenYouTubeFailsAndDoesntMarkThatTopic() {
        // e.g. out of quota: no point trying the rest, and topic 2 should be tried again next time
        topics.due = List.of(new TopicRepository.SearchTopic(1, "math explained"),
                new TopicRepository.SearchTopic(2, "history explained"),
                new TopicRepository.SearchTopic(3, "biology explained"));
        youtube.failOnTopic = 2;

        refresher.run();

        assertEquals(List.of(1), topics.marked);
        assertEquals(2, youtube.searchedFor.size());
    }

    @Test
    void unexpectedErrorsDontEscape() {
        // if run() threw, the scheduler would quietly stop calling it forever
        topics.due = List.of(new TopicRepository.SearchTopic(1, "math explained"));
        youtube.crash = true;

        refresher.run();

        assertTrue(topics.marked.isEmpty());
    }
}
