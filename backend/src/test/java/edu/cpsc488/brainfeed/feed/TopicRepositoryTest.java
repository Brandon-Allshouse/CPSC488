package edu.cpsc488.brainfeed.feed;

import edu.cpsc488.brainfeed.db.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Runs against the real test database (see TestDatabase).
class TopicRepositoryTest {

    TopicRepository topics;
    long user;
    long other;
    int math;
    int algebra;
    int space;

    @BeforeEach
    void setUp() {
        topics = new TopicRepository(TestDatabase.get());
        TestDatabase.reset();
        user = TestDatabase.addUser("testuser");
        other = TestDatabase.addUser("otheruser");
        math = TestDatabase.topicId("Math");
        algebra = TestDatabase.topicId("Algebra 2");
        space = TestDatabase.topicId("Space");
    }

    @Test
    void listsSubjectsAlphabeticallyThenSubSubjects() {
        List<Topic> all = topics.listAll();
        assertEquals(120, all.size());
        List<Topic> subjects = all.stream().filter(topic -> topic.parentId() == null).toList();
        assertEquals(20, subjects.size());
        assertEquals("Art", subjects.get(0).name());
        // every subject comes before every sub-subject
        assertNull(all.get(19).parentId());
        assertEquals(math, all.stream().filter(t -> t.name().equals("Algebra 2")).findFirst().orElseThrow().parentId());
        assertTrue(all.stream().noneMatch(Topic::ai));
    }

    @Test
    void marksAiTopics() {
        TestDatabase.exec("INSERT INTO topics (parent_id, name, search_query, source, created_by) VALUES (?, 'Comets', 'comets', 'ai', ?)", space, user);
        assertTrue(topics.listAll().stream().anyMatch(topic -> topic.name().equals("Comets") && topic.ai()));
    }

    @Test
    void interestsAreReplacedAndOnlyEverTheUsersOwn() {
        topics.replaceInterests(user, List.of(math, space));
        topics.replaceInterests(other, List.of(algebra));
        assertEquals(List.of(math, space), topics.findInterests(user));
        topics.replaceInterests(user, List.of(algebra));
        assertEquals(List.of(algebra), topics.findInterests(user));
        assertEquals(List.of(algebra), topics.findInterests(other));
    }

    @Test
    void aFailedReplaceKeepsTheOldInterests() {
        topics.replaceInterests(user, List.of(math));
        // 999999 isn't a topic, so the insert fails and the delete before it must roll back
        assertThrows(RuntimeException.class, () -> topics.replaceInterests(user, List.of(space, 999_999)));
        assertEquals(List.of(math), topics.findInterests(user));
    }

    @Test
    void savingInterestsTwiceAtOnceDoesNotFail() throws Exception {
        // a double click or two open tabs used to be able to hit a duplicate key here
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                List<Integer> picks = i % 2 == 0 ? List.of(math, space) : List.of(math, algebra);
                results.add(pool.submit(() -> topics.replaceInterests(user, picks)));
            }
            for (Future<?> result : results) {
                // throws if any of them failed
                result.get();
            }
        } finally {
            pool.shutdown();
        }
        // whichever ran last wins, and nothing is mixed from two requests
        List<Integer> saved = topics.findInterests(user);
        assertTrue(saved.equals(List.of(math, space).stream().sorted().toList())
                || saved.equals(List.of(math, algebra).stream().sorted().toList()), saved.toString());
    }

    @Test
    void pickedTopicsComeFirstForRefresh() {
        topics.replaceInterests(user, List.of(space));
        List<TopicRepository.SearchTopic> due = topics.dueForRefresh(Duration.ofDays(7));
        assertEquals(space, due.get(0).id());
        assertEquals(120, due.size());
    }

    @Test
    void recentlyFetchedTopicsAreNotDue() {
        topics.markFetched(math);
        assertFalse(topics.dueForRefresh(Duration.ofDays(7)).stream().anyMatch(t -> t.id() == math));
        assertEquals(1, topics.searchesInLastDay());
        TestDatabase.exec("UPDATE topics SET videos_fetched_at = now() - interval '8 days' WHERE id = ?", math);
        assertTrue(topics.dueForRefresh(Duration.ofDays(7)).stream().anyMatch(t -> t.id() == math));
        assertEquals(0, topics.searchesInLastDay());
    }

    @Test
    void aiTopicsNobodyPickedAreNotRefreshed() {
        long id = TestDatabase.number("INSERT INTO topics (parent_id, name, search_query, source, created_by) "
                + "VALUES (?, 'Comets', 'comets', 'ai', ?) RETURNING id", space, user);
        assertFalse(topics.dueForRefresh(Duration.ofDays(7)).stream().anyMatch(t -> t.id() == id));
        topics.replaceInterests(other, List.of((int) id));
        assertTrue(topics.dueForRefresh(Duration.ofDays(7)).stream().anyMatch(t -> t.id() == id));
    }

    @Test
    void neverFetchedOnlyListsTopicsNeverSearched() {
        topics.markFetched(math);
        List<TopicRepository.SearchTopic> missing = topics.neverFetched(List.of(math, space));
        assertEquals(List.of(space), missing.stream().map(TopicRepository.SearchTopic::id).toList());
        assertEquals("astronomy space explained", missing.get(0).searchQuery());
    }
}
