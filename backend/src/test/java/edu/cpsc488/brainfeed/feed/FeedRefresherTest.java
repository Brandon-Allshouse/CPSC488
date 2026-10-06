package edu.cpsc488.brainfeed.feed;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Uses fake versions of the database and YouTube classes so the test doesn't need either one.
class FeedRefresherTest {

    // pretends to be the topics table
    static class FakeTopics extends TopicRepository {
        List<SearchTopic> due = new ArrayList<>();
        // topics that have never been searched
        List<SearchTopic> missing = new ArrayList<>();
        List<Integer> marked = new ArrayList<>();
        int searchesToday = 0;
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
        List<SearchTopic> neverFetched(Collection<Integer> topicIds) {
            return missing.stream().filter(topic -> topicIds.contains(topic.id())).toList();
        }

        @Override
        int searchesInLastDay() {
            return searchesToday;
        }

        @Override
        void markFetched(int topicId) {
            marked.add(topicId);
            missing.removeIf(topic -> topic.id() == topicId);
            searchesToday++;
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

    // a clock the test can move forward
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-05T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final FakeTopics topics = new FakeTopics();
    private final FakeVideos videos = new FakeVideos();
    private final FakeYouTube youtube = new FakeYouTube();
    private final TestClock clock = new TestClock();
    // background work the refresher handed off, run when the test says so
    private final List<Runnable> waiting = new ArrayList<>();
    private final FeedRefresher refresher = new FeedRefresher(topics, videos, youtube, waiting::add, clock);

    private static TopicRepository.SearchTopic topic(int id, String query) {
        return new TopicRepository.SearchTopic(id, query);
    }

    private static List<TopicRepository.SearchTopic> numberedTopics(int count) {
        List<TopicRepository.SearchTopic> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(topic(i, "topic " + i));
        }
        return list;
    }

    private void runWaitingWork() {
        List<Runnable> work = new ArrayList<>(waiting);
        waiting.clear();
        work.forEach(Runnable::run);
    }

    // ---- the hourly run ----

    @Test
    void searchesEachDueTopicAndSavesTheVideos() {
        topics.due = List.of(topic(1, "math explained"), topic(2, "history explained"));

        refresher.run();

        assertEquals(List.of("math explained", "history explained"), youtube.searchedFor);
        assertEquals(2, videos.saved.size());
        assertEquals(List.of(1, 2), topics.marked);
    }

    @Test
    void onlyAsksForTopicsNotRefreshedInAWeek() {
        refresher.run();
        assertEquals(Duration.ofDays(7), topics.askedForAge);
    }

    @Test
    void onlyDoesAFewSearchesPerRun() {
        // ~120 topics all due at once would blow through the daily quota, so the rest wait an hour
        topics.due = numberedTopics(10);

        refresher.run();

        assertEquals(List.of(1, 2, 3), topics.marked);
    }

    @Test
    void nothingDueMeansNoSearches() {
        refresher.run();
        assertTrue(youtube.searchedFor.isEmpty());
    }

    @Test
    void hourlyRunStopsWhenTheDailyBudgetIsUsedUp() {
        topics.due = numberedTopics(3);
        topics.searchesToday = FeedRefresher.DAILY_SEARCH_BUDGET;

        refresher.run();

        assertTrue(youtube.searchedFor.isEmpty());
    }

    @Test
    void hourlyRunOnlyUsesWhatIsLeftOfTheBudget() {
        topics.due = numberedTopics(3);
        topics.searchesToday = FeedRefresher.DAILY_SEARCH_BUDGET - 1;

        refresher.run();

        assertEquals(List.of(1), topics.marked);
    }

    @Test
    void stopsWhenYouTubeFailsAndDoesntMarkThatTopic() {
        // e.g. out of quota: no point trying the rest, and topic 2 should be tried again next time
        topics.due = List.of(topic(1, "math explained"), topic(2, "history explained"), topic(3, "biology explained"));
        youtube.failOnTopic = 2;

        refresher.run();

        assertEquals(List.of(1), topics.marked);
        assertEquals(2, youtube.searchedFor.size());
    }

    @Test
    void unexpectedErrorsDontEscape() {
        // if run() threw, the scheduler would quietly stop calling it forever
        topics.due = List.of(topic(1, "math explained"));
        youtube.crash = true;

        refresher.run();

        assertTrue(topics.marked.isEmpty());
    }

    // ---- fetching new picks right away ----

    @Test
    void newlyPickedTopicsGetFetchedRightAway() {
        // this was the bug: Statistics and Linear Algebra sat empty until the next hourly run
        topics.missing = new ArrayList<>(List.of(topic(29, "statistics explained"), topic(30, "linear algebra explained")));

        assertTrue(refresher.fetchMissing(List.of(29, 30)));
        runWaitingWork();

        assertEquals(List.of("statistics explained", "linear algebra explained"), youtube.searchedFor);
        assertEquals(List.of(29, 30), topics.marked);
    }

    @Test
    void topicsThatAlreadyHaveVideosArentFetched() {
        assertFalse(refresher.fetchMissing(List.of(1, 2)));
        assertTrue(waiting.isEmpty());
    }

    @Test
    void nothingIsFetchedWhenTheDailyBudgetIsUsedUp() {
        topics.missing = new ArrayList<>(List.of(topic(29, "statistics explained")));
        topics.searchesToday = FeedRefresher.DAILY_SEARCH_BUDGET;

        assertFalse(refresher.fetchMissing(List.of(29)));
        assertTrue(waiting.isEmpty());
    }

    @Test
    void oneRequestCanOnlyFetchAFewTopics() {
        // someone asking for 100 new topics at once shouldn't spend the whole budget
        topics.missing = new ArrayList<>(numberedTopics(8));

        refresher.fetchMissing(List.of(1, 2, 3, 4, 5, 6, 7, 8));
        runWaitingWork();

        assertEquals(FeedRefresher.MAX_FETCHES_PER_REQUEST, youtube.searchedFor.size());
    }

    @Test
    void checkingAgainWhileWaitingDoesntQueueTheSameTopicTwice() {
        // the feed page checks every few seconds while it waits
        topics.missing = new ArrayList<>(List.of(topic(29, "statistics explained")));

        assertTrue(refresher.fetchMissing(List.of(29)));
        assertTrue(refresher.fetchMissing(List.of(29)));
        assertEquals(1, waiting.size());

        runWaitingWork();
        assertEquals(1, youtube.searchedFor.size());
    }

    @Test
    void skipsTopicsTheHourlyRunAlreadyFetched() {
        topics.missing = new ArrayList<>(List.of(topic(29, "statistics explained")));
        refresher.fetchMissing(List.of(29));

        // the hourly run got to it first while it waited in line
        topics.missing.clear();
        runWaitingWork();

        assertTrue(youtube.searchedFor.isEmpty());
    }

    @Test
    void stopsTryingForAWhileAfterAYouTubeError() {
        // otherwise every feed request would hit YouTube again while the key or quota is broken
        topics.missing = new ArrayList<>(List.of(topic(29, "statistics explained")));
        youtube.failOnTopic = 29;
        refresher.fetchMissing(List.of(29));
        runWaitingWork();

        assertFalse(refresher.fetchMissing(List.of(29)));

        clock.now = clock.now.plus(FeedRefresher.PAUSE_AFTER_ERROR).plusSeconds(1);
        assertTrue(refresher.fetchMissing(List.of(29)));
    }

    @Test
    void aTopicCanBeQueuedAgainAfterAnUnexpectedError() {
        topics.missing = new ArrayList<>(List.of(topic(29, "statistics explained")));
        youtube.crash = true;
        refresher.fetchMissing(List.of(29));
        runWaitingWork();

        youtube.crash = false;
        assertTrue(refresher.fetchMissing(List.of(29)));
        runWaitingWork();
        assertEquals(List.of(29), topics.marked);
    }
}
