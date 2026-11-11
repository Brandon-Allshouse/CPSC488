package edu.cpsc488.brainfeed.topics;

import edu.cpsc488.brainfeed.db.TestDatabase;
import edu.cpsc488.brainfeed.feed.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Runs against the real test database (see TestDatabase).
class AiTopicRepositoryTest {

    static final AiTopicRepository.Limits ROOMY = new AiTopicRepository.Limits(10, 200, 40, 2000);

    AiTopicRepository repository;
    long user;
    int space;

    @BeforeEach
    void setUp() {
        repository = new AiTopicRepository(TestDatabase.get());
        TestDatabase.reset();
        user = TestDatabase.addUser("testuser");
        space = TestDatabase.topicId("Space");
    }

    @Test
    void addsATopicUnderAnExistingSubject() {
        AiTopicRepository.Result result = repository.add(user, space, null, null, "Black Holes", "black holes explained", ROOMY);
        assertEquals(AiTopicRepository.Outcome.ADDED, result.outcome());
        Topic topic = result.topics().get(0);
        assertEquals("Black Holes", topic.name());
        assertEquals(space, topic.parentId());
        assertTrue(topic.ai());
        // stored as AI-made, with who added it and the search query (never sent to browsers)
        assertEquals("ai", TestDatabase.text("SELECT source FROM topics WHERE id = ?", topic.id()));
        assertEquals(user, TestDatabase.number("SELECT created_by FROM topics WHERE id = ?", topic.id()));
        assertEquals("black holes explained", TestDatabase.text("SELECT search_query FROM topics WHERE id = ?", topic.id()));
    }

    @Test
    void addsANewSubjectAndItsTopic() {
        AiTopicRepository.Result result = repository.add(user, null, "Cooking", "cooking explained",
                "Baking Bread", "baking bread explained", ROOMY);
        assertEquals(AiTopicRepository.Outcome.ADDED, result.outcome());
        assertEquals(2, result.topics().size());
        Topic subject = result.topics().get(0);
        assertNull(subject.parentId());
        assertEquals(subject.id(), result.topics().get(1).parentId());
    }

    @Test
    void aTopicNamedLikeItsNewSubjectIsJustTheSubject() {
        AiTopicRepository.Result result = repository.add(user, null, "Cooking", "cooking explained", "cooking", "cooking", ROOMY);
        assertEquals(1, result.topics().size());
        assertNull(result.topics().get(0).parentId());
    }

    @Test
    void namesAreUniqueIgnoringCase() {
        AiTopicRepository.Result result = repository.add(user, space, null, null, "algebra 2", "algebra 2", ROOMY);
        assertEquals(AiTopicRepository.Outcome.EXISTS, result.outcome());
        assertEquals(TestDatabase.topicId("Algebra 2"), result.topics().get(0).id());
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM topics WHERE source = 'ai'"));
    }

    @Test
    void anExistingSubjectNameIsReusedNotDuplicated() {
        AiTopicRepository.Result result = repository.add(user, null, "space", "space", "Comets", "comets", ROOMY);
        assertEquals(AiTopicRepository.Outcome.ADDED, result.outcome());
        assertEquals(1, result.topics().size());
        assertEquals(space, result.topics().get(0).parentId());
    }

    @Test
    void cannotAddUnderATopicThatIsNotASubject() {
        int algebra = TestDatabase.topicId("Algebra 2");
        assertEquals(AiTopicRepository.Outcome.CONFLICT,
                repository.add(user, algebra, null, null, "Matrices", "matrices", ROOMY).outcome());
        assertEquals(AiTopicRepository.Outcome.CONFLICT,
                repository.add(user, null, "Algebra 2", "algebra", "Matrices", "matrices", ROOMY).outcome());
    }

    @Test
    void removedNamesCannotComeBack() {
        TestDatabase.exec("INSERT INTO banned_topic_names (name_lower, reason) VALUES ('black holes', 'test')");
        assertEquals(AiTopicRepository.Outcome.BANNED,
                repository.add(user, space, null, null, "Black Holes", "x", ROOMY).outcome());
        assertTrue(repository.isBanned("BLACK HOLES"));
        assertFalse(repository.isBanned("Comets"));
    }

    @Test
    void eachUserHasADailyLimit() {
        AiTopicRepository.Limits two = new AiTopicRepository.Limits(2, 200, 40, 2000);
        repository.add(user, space, null, null, "Comets", "comets", two);
        repository.add(user, space, null, null, "Nebulae", "nebulae", two);
        assertEquals(AiTopicRepository.Outcome.USER_LIMIT, repository.add(user, space, null, null, "Quasars", "quasars", two).outcome());
        // a new subject plus its topic counts as two
        long other = TestDatabase.addUser("otheruser");
        repository.add(other, space, null, null, "Pulsars", "pulsars", two);
        assertEquals(AiTopicRepository.Outcome.USER_LIMIT,
                repository.add(other, null, "Cooking", "cooking", "Baking Bread", "bread", two).outcome());
    }

    @Test
    void addsFromYesterdayDontCount() {
        AiTopicRepository.Limits one = new AiTopicRepository.Limits(1, 200, 40, 2000);
        repository.add(user, space, null, null, "Comets", "comets", one);
        TestDatabase.exec("UPDATE topics SET created_at = now() - interval '25 hours' WHERE source = 'ai'");
        assertEquals(AiTopicRepository.Outcome.ADDED, repository.add(user, space, null, null, "Nebulae", "nebulae", one).outcome());
    }

    @Test
    void thereIsADailyLimitForEveryone() {
        AiTopicRepository.Limits one = new AiTopicRepository.Limits(10, 1, 40, 2000);
        repository.add(user, space, null, null, "Comets", "comets", one);
        long other = TestDatabase.addUser("otheruser");
        assertEquals(AiTopicRepository.Outcome.DAILY_LIMIT, repository.add(other, space, null, null, "Nebulae", "x", one).outcome());
    }

    @Test
    void theCatalogAndSubjectCountsAreCapped() {
        long topics = TestDatabase.number("SELECT count(*) FROM topics");
        long subjects = TestDatabase.number("SELECT count(*) FROM topics WHERE parent_id IS NULL");
        assertEquals(AiTopicRepository.Outcome.CATALOG_FULL, repository.add(user, space, null, null, "Comets", "c",
                new AiTopicRepository.Limits(10, 200, 40, (int) topics)).outcome());
        assertEquals(AiTopicRepository.Outcome.CATALOG_FULL, repository.add(user, null, "Cooking", "c", "Bread", "b",
                new AiTopicRepository.Limits(10, 200, (int) subjects, 2000)).outcome());
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM topics WHERE source = 'ai'"));
    }

    @Test
    void parallelAddsCannotGoOverTheLimit() throws Exception {
        AiTopicRepository.Limits five = new AiTopicRepository.Limits(5, 200, 40, 2000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<AiTopicRepository.Result>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String name = "Parallel Topic " + i;
                results.add(pool.submit(() -> repository.add(user, space, null, null, name, "x", five)));
            }
            for (Future<AiTopicRepository.Result> result : results) {
                result.get();
            }
        } finally {
            pool.shutdown();
        }
        assertEquals(5, TestDatabase.number("SELECT count(*) FROM topics WHERE source = 'ai'"));
    }

    @Test
    void catalogListsSeededAndAiTopics() {
        repository.add(user, space, null, null, "Comets", "comets", ROOMY);
        List<Topic> catalog = repository.catalog();
        assertTrue(catalog.stream().anyMatch(topic -> topic.name().equals("Comets") && topic.ai()));
        assertTrue(catalog.stream().anyMatch(topic -> topic.name().equals("Math") && !topic.ai()));
        // subjects first
        assertNull(catalog.get(0).parentId());
    }

    @Test
    void theDatabaseRefusesControlCharactersInNames() {
        boolean refused = false;
        try {
            TestDatabase.exec("INSERT INTO topics (name, search_query) VALUES (?, 'x')", "Bad\nName");
        } catch (RuntimeException e) {
            refused = true;
        }
        assertTrue(refused);
    }
}
