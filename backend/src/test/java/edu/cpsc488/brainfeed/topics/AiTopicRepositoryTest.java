package edu.cpsc488.brainfeed.topics;

import edu.cpsc488.brainfeed.db.TestDatabase;
import edu.cpsc488.brainfeed.feed.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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
    int explore;

    @BeforeEach
    void setUp() {
        repository = new AiTopicRepository(TestDatabase.get());
        TestDatabase.reset();
        user = TestDatabase.addUser("testuser");
        space = TestDatabase.topicId("Space");
        explore = TestDatabase.topicId("Explore");
    }

    private AiTopicRepository.Result add(long userId, String name, AiTopicRepository.Limits limits) {
        return repository.add(userId, name, name.toLowerCase() + " explained",
                catalog -> TopicMatcher.find(name, catalog, true),
                catalog -> SubjectGuesser.guess(name, catalog), limits);
    }

    @Test
    void addsATopicUnderItsGuessedSubject() {
        AiTopicRepository.Result result = add(user, "Black Holes", ROOMY);
        assertEquals(AiTopicRepository.Outcome.ADDED, result.outcome());
        Topic topic = result.topic();
        assertEquals("Black Holes", topic.name());
        assertEquals(space, topic.parentId());
        assertTrue(topic.ai());
        // stored as AI-made, with who added it, waiting for review
        assertEquals("ai", TestDatabase.text("SELECT source FROM topics WHERE id = ?", topic.id()));
        assertEquals(user, TestDatabase.number("SELECT created_by FROM topics WHERE id = ?", topic.id()));
        assertNull(TestDatabase.text("SELECT reviewed_at FROM topics WHERE id = ?", topic.id()));
        assertEquals("black holes explained", TestDatabase.text("SELECT search_query FROM topics WHERE id = ?", topic.id()));
    }

    @Test
    void unknownTopicsGoToExplore() {
        assertEquals(explore, add(user, "Knitting", ROOMY).topic().parentId());
    }

    @Test
    void anExistingTopicIsReturnedNotDuplicated() {
        AiTopicRepository.Result result = add(user, "algebra 2", ROOMY);
        assertEquals(AiTopicRepository.Outcome.EXISTS, result.outcome());
        assertEquals(TestDatabase.topicId("Algebra 2"), result.topic().id());
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM topics WHERE source = 'ai'"));
    }

    @Test
    void removedNamesCannotComeBackInAnyForm() {
        TestDatabase.exec("INSERT INTO banned_topic_names (name_lower, reason) VALUES ('black holes', 'test')");
        assertEquals(AiTopicRepository.Outcome.BANNED, add(user, "Black Holes", ROOMY).outcome());
        assertEquals(AiTopicRepository.Outcome.BANNED, add(user, "Black Hole", ROOMY).outcome());
        assertTrue(repository.isBanned("BLACK HOLES"));
        assertFalse(repository.isBanned("Comets"));
    }

    @Test
    void eachUserHasADailyLimit() {
        AiTopicRepository.Limits two = new AiTopicRepository.Limits(2, 200, 40, 2000);
        add(user, "Comets", two);
        add(user, "Nebulae", two);
        assertEquals(AiTopicRepository.Outcome.USER_LIMIT, add(user, "Quasars", two).outcome());
        long other = TestDatabase.addUser("otheruser");
        assertEquals(AiTopicRepository.Outcome.ADDED, add(other, "Pulsars", two).outcome());
    }

    @Test
    void addsFromYesterdayDontCount() {
        AiTopicRepository.Limits one = new AiTopicRepository.Limits(1, 200, 40, 2000);
        add(user, "Comets", one);
        TestDatabase.exec("UPDATE topics SET created_at = now() - interval '25 hours' WHERE source = 'ai'");
        assertEquals(AiTopicRepository.Outcome.ADDED, add(user, "Nebulae", one).outcome());
    }

    @Test
    void thereIsADailyLimitForEveryone() {
        AiTopicRepository.Limits one = new AiTopicRepository.Limits(10, 1, 40, 2000);
        add(user, "Comets", one);
        long other = TestDatabase.addUser("otheruser");
        assertEquals(AiTopicRepository.Outcome.DAILY_LIMIT, add(other, "Nebulae", one).outcome());
    }

    @Test
    void theCatalogIsCapped() {
        int topics = (int) TestDatabase.number("SELECT count(*) FROM topics");
        assertEquals(AiTopicRepository.Outcome.CATALOG_FULL,
                add(user, "Comets", new AiTopicRepository.Limits(10, 200, 40, topics)).outcome());
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM topics WHERE source = 'ai'"));
    }

    @Test
    void followAddsAnInterestOnceAndStopsAtTheLimit() {
        int math = TestDatabase.topicId("Math");
        assertTrue(repository.follow(user, math, 2));
        assertTrue(repository.follow(user, math, 2));
        assertEquals(1, TestDatabase.number("SELECT count(*) FROM user_interests WHERE user_id = ?", user));
        assertTrue(repository.follow(user, space, 2));
        assertFalse(repository.follow(user, explore, 2));
        // already following is still fine at the limit
        assertTrue(repository.follow(user, space, 2));
        assertTrue(repository.isFollowing(user, space));
        assertFalse(repository.isFollowing(user, explore));
    }

    @Test
    void placeMovesToAnExistingSubjectAndSetsTheQuery() {
        int knitting = add(user, "Knitting", ROOMY).topic().id();
        int art = TestDatabase.topicId("Art");
        assertTrue(repository.place(knitting, new AiTopicRepository.Placement("art", "knitting for beginners"), ROOMY));
        assertEquals(art, TestDatabase.number("SELECT parent_id FROM topics WHERE id = ?", knitting));
        assertEquals("knitting for beginners", TestDatabase.text("SELECT search_query FROM topics WHERE id = ?", knitting));
        assertTrue(repository.pending(knitting).isEmpty());
    }

    @Test
    void placeCanMakeANewSubject() {
        int bread = add(user, "Sourdough", ROOMY).topic().id();
        repository.place(bread, new AiTopicRepository.Placement("Cooking", null), ROOMY);
        long cooking = TestDatabase.number("SELECT id FROM topics WHERE name = 'Cooking' AND parent_id IS NULL");
        assertEquals(cooking, TestDatabase.number("SELECT parent_id FROM topics WHERE id = ?", bread));
        // the search query wasn't usable, so it kept the old one
        assertEquals("sourdough explained", TestDatabase.text("SELECT search_query FROM topics WHERE id = ?", bread));
        // the new subject is already reviewed, so it isn't reviewed again
        assertEquals(List.of(), repository.unreviewed(10));
    }

    @Test
    void aBroadFieldBecomesASubjectItself() {
        int cooking = add(user, "Cooking", ROOMY).topic().id();
        repository.place(cooking, new AiTopicRepository.Placement("Cooking", null), ROOMY);
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM topics WHERE id = ? AND parent_id IS NOT NULL", cooking));
    }

    @Test
    void placeKeepsTheTopicWhereItIsWhenTheSubjectCannotBeUsed() {
        int knitting = add(user, "Knitting", ROOMY).topic().id();
        // a sub-topic's name, a banned name, Explore itself, and no room for another subject
        repository.place(knitting, new AiTopicRepository.Placement("Algebra 2", null), ROOMY);
        assertEquals(explore, TestDatabase.number("SELECT parent_id FROM topics WHERE id = ?", knitting));
        TestDatabase.exec("UPDATE topics SET reviewed_at = NULL WHERE id = ?", knitting);
        TestDatabase.exec("INSERT INTO banned_topic_names (name_lower, reason) VALUES ('crafts', 'test')");
        repository.place(knitting, new AiTopicRepository.Placement("Crafts", null), ROOMY);
        int subjects = (int) TestDatabase.number("SELECT count(*) FROM topics WHERE parent_id IS NULL");
        repository.place(knitting, new AiTopicRepository.Placement("Hobbies", null),
                new AiTopicRepository.Limits(10, 200, subjects, 2000));
        assertEquals(explore, TestDatabase.number("SELECT parent_id FROM topics WHERE id = ?", knitting));
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM topics WHERE name IN ('Crafts', 'Hobbies')"));
    }

    @Test
    void removeFlaggedDeletesBansAndLogsIt() {
        int topic = add(user, "Bad Topic", ROOMY).topic().id();
        TestDatabase.exec("INSERT INTO user_interests (user_id, topic_id) VALUES (?, ?)", user, topic);
        assertTrue(repository.removeFlagged(topic, "Removed automatically"));
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM topics WHERE id = ?", topic));
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM user_interests WHERE topic_id = ?", topic));
        assertTrue(repository.isBanned("bad topics"));
        assertEquals("remove_topic", TestDatabase.text("SELECT action FROM admin_actions WHERE admin_id IS NULL"));
        assertEquals("Bad Topic (#" + topic + ")", TestDatabase.text("SELECT target FROM admin_actions"));
        // a second time there's nothing left to remove
        assertFalse(repository.removeFlagged(topic, "again"));
    }

    @Test
    void seededTopicsCanNeverBeRemovedOrMovedByTheReview() {
        int math = TestDatabase.topicId("Math");
        assertFalse(repository.removeFlagged(math, "x"));
        assertFalse(repository.place(math, new AiTopicRepository.Placement("Space", null), ROOMY));
        assertEquals(Optional.empty(), repository.pending(math));
    }

    @Test
    void unreviewedListsWaitingTopicsOldestFirst() {
        int first = add(user, "Comets", ROOMY).topic().id();
        int second = add(user, "Nebulae", ROOMY).topic().id();
        assertEquals(List.of(first, second), repository.unreviewed(10).stream().map(AiTopicRepository.Pending::id).toList());
        repository.markReviewed(first);
        assertEquals(List.of(second), repository.unreviewed(10).stream().map(AiTopicRepository.Pending::id).toList());
    }

    @Test
    void exploreIsASeededSubject() {
        assertEquals("seeded", TestDatabase.text("SELECT source FROM topics WHERE name = 'Explore' AND parent_id IS NULL"));
    }
}
