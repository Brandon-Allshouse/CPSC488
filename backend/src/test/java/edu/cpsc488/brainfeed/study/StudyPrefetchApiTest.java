package edu.cpsc488.brainfeed.study;

import edu.cpsc488.brainfeed.ApiServer;
import edu.cpsc488.brainfeed.auth.AuthController;
import edu.cpsc488.brainfeed.auth.PasswordHasher;
import edu.cpsc488.brainfeed.db.TestDatabase;
import edu.cpsc488.brainfeed.feed.FeedController;
import edu.cpsc488.brainfeed.feed.TopicRepository;
import edu.cpsc488.brainfeed.feed.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Loading a feed page over real HTTP lines up summaries for its first videos, within the limits,
// with the real database behind it and a fake model (no real model calls).
class StudyPrefetchApiTest {

    ApiServer server;
    StudyRepository repository;
    StudyGeneratorTest.FakeLlm llm;
    // jobs wait here until the test runs them, like the real worker thread would later
    final Deque<Runnable> jobs = new ArrayDeque<>();
    int math;

    @BeforeEach
    void setUp() {
        TestDatabase.reset();
        var data = TestDatabase.get();
        repository = new StudyRepository(data);
        llm = new StudyGeneratorTest.FakeLlm();
        StudyGenerator generator = new StudyGenerator(repository, llm, id -> Optional.empty(), jobs::add);
        // guests can load the feed, so no user or session tables are needed
        AuthController auth = new AuthController(null, null, new PasswordHasher(new byte[32], 2), null, false);
        FeedController feed = new FeedController(new TopicRepository(data), new VideoRepository(data), auth,
                topicIds -> false, generator::prefetch);
        server = new ApiServer(feed::register);
        math = TestDatabase.topicId("Math");
        for (int i = 0; i < 12; i++) {
            TestDatabase.addVideo(String.format("video%06d", i), math);
        }
    }

    @AfterEach
    void stop() {
        server.close();
    }

    HttpResponse<String> feedPage() {
        return server.get("/api/feed?topics=" + math + "&seed=7&page=0");
    }

    @Test
    void servingAFeedPageQueuesItsFirstVideos() {
        HttpResponse<String> page = feedPage();
        assertEquals(200, page.statusCode());
        assertEquals(StudyGenerator.PREFETCH_PER_PAGE, jobs.size());

        // they're the first videos on the page, in order
        List<String> shown = server.json(page).get("videos").findValuesAsText("youtubeId");
        while (!jobs.isEmpty()) {
            jobs.poll().run();
        }
        assertEquals(Set.copyOf(shown.subList(0, StudyGenerator.PREFETCH_PER_PAGE)),
                repository.withMaterial(shown));
        assertEquals(StudyGenerator.PREFETCH_PER_PAGE,
                TestDatabase.number("SELECT count(*) FROM video_study"));
    }

    @Test
    void reloadingThePageDoesNotQueueThemAgain() {
        feedPage();
        feedPage();
        assertEquals(StudyGenerator.PREFETCH_PER_PAGE, jobs.size());
        while (!jobs.isEmpty()) {
            jobs.poll().run();
        }
        // already written now, so nothing new and the model isn't called again
        feedPage();
        assertTrue(jobs.isEmpty());
        assertEquals(StudyGenerator.PREFETCH_PER_PAGE, llm.prompts.size());
    }

    @Test
    void oneGuestReloadingTheFeedStopsAtThePerIpLimit() {
        // more videos than the limit, so the limit is what stops it
        for (int i = 0; i < 40; i++) {
            TestDatabase.addVideo(String.format("video%06d", 100 + i), math);
        }
        // different seeds shuffle the feed, so each load can bring new videos
        for (int seed = 0; seed < 40; seed++) {
            assertEquals(200, server.get("/api/feed?topics=" + math + "&seed=" + seed + "&page=0").statusCode());
            while (!jobs.isEmpty()) {
                jobs.poll().run();
            }
        }
        assertEquals(StudyGenerator.PREFETCH_PER_IP_PER_HOUR, llm.prompts.size());
    }

    @Test
    void theFeedStillLoadsWhenPrefetchingFails() {
        server.close();
        var data = TestDatabase.get();
        AuthController auth = new AuthController(null, null, new PasswordHasher(new byte[32], 2), null, false);
        FeedController feed = new FeedController(new TopicRepository(data), new VideoRepository(data), auth,
                topicIds -> false, (ids, ip) -> {
                    throw new RuntimeException("database is down");
                });
        server = new ApiServer(feed::register);
        assertEquals(200, feedPage().statusCode());
    }
}
