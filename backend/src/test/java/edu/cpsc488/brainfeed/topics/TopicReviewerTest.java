package edu.cpsc488.brainfeed.topics;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cpsc488.brainfeed.db.TestDatabase;
import edu.cpsc488.brainfeed.llm.Llm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// A fake model and the real test database. Reviews run on the test's own thread.
class TopicReviewerTest {

    static class FakeLlm implements Llm {
        final Deque<Object> replies = new ArrayDeque<>();
        final List<String> prompts = new ArrayList<>();

        @Override
        public String model() {
            return "gemma4:e4b";
        }

        @Override
        public String chat(String system, String user, JsonNode schema) throws IOException {
            prompts.add(user);
            Object next = replies.poll();
            if (next instanceof IOException e) {
                throw e;
            }
            return next == null ? "not json" : (String) next;
        }
    }

    final FakeLlm llm = new FakeLlm();
    AiTopicRepository repository;
    TopicReviewer reviewer;
    long user;

    @BeforeEach
    void setUp() {
        TestDatabase.reset();
        repository = new AiTopicRepository(TestDatabase.get());
        reviewer = new TopicReviewer(repository, llm, Runnable::run, AiTopicRepositoryTest.ROOMY);
        user = TestDatabase.addUser("testuser");
    }

    private int add(String name) {
        return repository.add(user, name, name + " explained", catalog -> TopicMatcher.find(name, catalog, true),
                catalog -> SubjectGuesser.guess(name, catalog), AiTopicRepositoryTest.ROOMY).topic().id();
    }

    @Test
    void movesTheTopicAndImprovesItsSearch() {
        int knitting = add("Knitting");
        llm.replies.add(TopicParserTest.review(false, "Art", "knitting basics for beginners"));
        reviewer.queue(knitting);
        assertEquals(TestDatabase.topicId("Art"), TestDatabase.number("SELECT parent_id FROM topics WHERE id = ?", knitting));
        assertEquals("knitting basics for beginners", TestDatabase.text("SELECT search_query FROM topics WHERE id = ?", knitting));
        assertTrue(llm.prompts.get(0).contains("<topic>Knitting</topic>"));
    }

    @Test
    void aHarmfulTopicIsRemovedAndBanned() {
        int topic = add("Bad Topic");
        llm.replies.add(TopicParserTest.review(true, "Explore", "x explained"));
        reviewer.queue(topic);
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM topics WHERE id = ?", topic));
        assertTrue(repository.isBanned("Bad Topic"));
        assertEquals(1, TestDatabase.number("SELECT count(*) FROM admin_actions WHERE action = 'remove_topic'"));
    }

    @Test
    void retriesOnceThenLeavesTheTopicAlone() {
        int knitting = add("Knitting");
        llm.replies.add("not json");
        llm.replies.add("{\"harmful\": \"maybe\"}");
        reviewer.queue(knitting);
        assertEquals(2, llm.prompts.size());
        assertEquals(TestDatabase.topicId("Explore"), TestDatabase.number("SELECT parent_id FROM topics WHERE id = ?", knitting));
        // marked reviewed, so it isn't asked about forever
        assertEquals(List.of(), repository.unreviewed(10));
    }

    @Test
    void whenTheModelIsDownTheTopicWaitsForTheNextStart() {
        int knitting = add("Knitting");
        llm.replies.add(new IOException("connection refused"));
        reviewer.queue(knitting);
        assertEquals(1, repository.unreviewed(10).size());

        llm.replies.add(TopicParserTest.review(false, "Art", "knitting explained"));
        reviewer.queueWaiting();
        assertEquals(List.of(), repository.unreviewed(10));
    }

    @Test
    void withoutAModelNothingIsReviewed() {
        TopicReviewer off = new TopicReviewer(repository, null, Runnable::run, AiTopicRepositoryTest.ROOMY);
        int knitting = add("Knitting");
        off.queue(knitting);
        off.queueWaiting();
        assertEquals(1, repository.unreviewed(10).size());
    }

    @Test
    void aTopicRemovedBeforeItsReviewIsSkipped() {
        int knitting = add("Knitting");
        TestDatabase.exec("DELETE FROM topics WHERE id = ?", knitting);
        reviewer.queue(knitting);
        assertTrue(llm.prompts.isEmpty());
    }

    @Test
    void aRejectedQueueDoesNotThrow() {
        TopicReviewer shutDown = new TopicReviewer(repository, llm, task -> {
            throw new java.util.concurrent.RejectedExecutionException();
        }, AiTopicRepositoryTest.ROOMY);
        shutDown.queue(add("Knitting"));
        assertTrue(llm.prompts.isEmpty());
    }
}
