package edu.cpsc488.brainfeed.topics;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.feed.Topic;
import edu.cpsc488.brainfeed.llm.Llm;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Fake database and model, so these check the rules in TopicSuggestions on their own.
class TopicSuggestionsTest {

    static final long USER = 1;
    static final long OTHER_USER = 2;

    // pretends to be the topics table
    static class FakeRepository extends AiTopicRepository {
        List<Topic> catalog = new ArrayList<>(List.of(
                new Topic(1, "Math", null, false),
                new Topic(2, "Space", null, false),
                new Topic(3, "Algebra 2", 1, false),
                new Topic(4, "Stars and Galaxies", 2, false)));
        Set<String> banned = new HashSet<>();
        List<String> addCalls = new ArrayList<>();
        Outcome nextOutcome = Outcome.ADDED;

        FakeRepository() {
            super(null);
        }

        @Override
        List<Topic> catalog() {
            return catalog;
        }

        @Override
        boolean isBanned(String name) {
            return banned.contains(TopicParser.key(name));
        }

        @Override
        Result add(long userId, Integer subjectId, String newSubject, String newSubjectQuery,
                   String name, String query, Limits limits) {
            addCalls.add(userId + ":" + subjectId + ":" + newSubject + ":" + newSubjectQuery + ":" + name + ":" + query);
            if (nextOutcome != Outcome.ADDED && nextOutcome != Outcome.EXISTS) {
                return Result.of(nextOutcome);
            }
            List<Topic> topics = new ArrayList<>();
            int parent = subjectId == null ? 900 : subjectId;
            if (newSubject != null) {
                topics.add(new Topic(900, newSubject, null, true));
            }
            topics.add(new Topic(901, name, parent, true));
            return new Result(nextOutcome, topics);
        }
    }

    static class FakeLlm implements Llm {
        Deque<Object> replies = new ArrayDeque<>();
        List<String> prompts = new ArrayList<>();

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
            return next == null ? "{\"topics\": []}" : (String) next;
        }
    }

    static class FakeClock extends Clock {
        Instant now = Instant.parse("2026-11-10T12:00:00Z");

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

    final FakeRepository repository = new FakeRepository();
    final FakeLlm llm = new FakeLlm();
    final FakeClock clock = new FakeClock();
    final TopicSuggestions suggestions = new TopicSuggestions(repository, llm, clock);

    static String reply(String... topics) {
        return "{\"topics\": [" + String.join(",", topics) + "]}";
    }

    static String topic(String subject, String name) {
        return "{\"subject\": \"" + subject + "\", \"name\": \"" + name + "\", \"searchQuery\": \"" + name.toLowerCase() + " explained\"}";
    }

    @Test
    void sortsIdeasIntoCatalogMatchesAndNewTopics() {
        llm.replies.add(reply(topic("Space", "Stars and Galaxies"), topic("Space", "Black Holes")));
        TopicSuggestions.Suggestions result = suggestions.suggest(USER, "192.0.2.1", "black holes");
        assertEquals(List.of(4), result.matches().stream().map(Topic::id).toList());
        assertEquals(1, result.ideas().size());
        TopicSuggestions.Idea idea = result.ideas().get(0);
        assertEquals("Black Holes", idea.name());
        assertEquals("Space", idea.subject());
        assertFalse(idea.newSubject());
        assertTrue(llm.prompts.get(0).contains("<request>black holes</request>"));
    }

    @Test
    void matchingIgnoresCase() {
        llm.replies.add(reply(topic("math", "ALGEBRA 2")));
        TopicSuggestions.Suggestions result = suggestions.suggest(USER, "192.0.2.1", "algebra two");
        assertEquals(List.of(3), result.matches().stream().map(Topic::id).toList());
        assertTrue(result.ideas().isEmpty());
    }

    @Test
    void anUnknownSubjectBecomesANewSubject() {
        llm.replies.add(reply(topic("Cooking", "Baking Bread")));
        TopicSuggestions.Idea idea = suggestions.suggest(USER, "192.0.2.1", "bread").ideas().get(0);
        assertTrue(idea.newSubject());
        assertEquals("Cooking", idea.subject());
    }

    @Test
    void aTopicNamedAsTheSubjectUsesThatTopicsSubject() {
        // the model said "Stars and Galaxies" (a topic) where a subject should be
        llm.replies.add(reply(topic("Stars and Galaxies", "Black Holes")));
        TopicSuggestions.Idea idea = suggestions.suggest(USER, "192.0.2.1", "black holes").ideas().get(0);
        assertEquals("Space", idea.subject());
        assertFalse(idea.newSubject());
    }

    @Test
    void removedNamesAreNeverSuggested() {
        repository.banned.add("black holes");
        repository.banned.add("bad subject");
        llm.replies.add(reply(topic("Space", "Black Holes"), topic("Bad Subject", "Anything")));
        assertTrue(suggestions.suggest(USER, "192.0.2.1", "black holes").ideas().isEmpty());
    }

    @Test
    void addingUsesTheStoredIdeaAndTheKeyOnlyWorksOnce() {
        llm.replies.add(reply(topic("Space", "Black Holes")));
        String key = suggestions.suggest(USER, "192.0.2.1", "black holes").ideas().get(0).key();
        TopicSuggestions.Added added = suggestions.add(USER, key);
        assertTrue(added.created());
        assertEquals("Black Holes", added.topics().get(0).name());
        assertEquals(List.of("1:2:null:null:Black Holes:black holes explained"), repository.addCalls);
        ApiException again = assertThrows(ApiException.class, () -> suggestions.add(USER, key));
        assertEquals(404, again.status());
    }

    @Test
    void aNewSubjectGetsItsOwnSearchQuery() {
        llm.replies.add(reply(topic("Cooking", "Baking Bread")));
        String key = suggestions.suggest(USER, "192.0.2.1", "bread").ideas().get(0).key();
        suggestions.add(USER, key);
        assertEquals(List.of("1:null:Cooking:Cooking explained:Baking Bread:baking bread explained"), repository.addCalls);
    }

    @Test
    void anotherUserCannotUseYourKey() {
        llm.replies.add(reply(topic("Space", "Black Holes")));
        String key = suggestions.suggest(USER, "192.0.2.1", "black holes").ideas().get(0).key();
        assertEquals(404, assertThrows(ApiException.class, () -> suggestions.add(OTHER_USER, key)).status());
        assertTrue(repository.addCalls.isEmpty());
    }

    @Test
    void madeUpAndMissingKeysAreRefused() {
        assertEquals(404, assertThrows(ApiException.class, () -> suggestions.add(USER, "made-up")).status());
        assertEquals(404, assertThrows(ApiException.class, () -> suggestions.add(USER, null)).status());
    }

    @Test
    void keysExpire() {
        llm.replies.add(reply(topic("Space", "Black Holes")));
        String key = suggestions.suggest(USER, "192.0.2.1", "black holes").ideas().get(0).key();
        clock.now = clock.now.plus(TopicSuggestions.PROPOSALS_LAST).plusSeconds(1);
        assertEquals(404, assertThrows(ApiException.class, () -> suggestions.add(USER, key)).status());
    }

    @Test
    void askingAgainReplacesYourOldKeys() {
        llm.replies.add(reply(topic("Space", "Black Holes")));
        llm.replies.add(reply(topic("Space", "Comets")));
        String first = suggestions.suggest(USER, "192.0.2.1", "black holes").ideas().get(0).key();
        String second = suggestions.suggest(USER, "192.0.2.1", "comets").ideas().get(0).key();
        assertNotEquals(first, second);
        assertEquals(404, assertThrows(ApiException.class, () -> suggestions.add(USER, first)).status());
        suggestions.add(USER, second);
    }

    @Test
    void keysAreLongAndRandom() {
        llm.replies.add(reply(topic("Space", "Black Holes"), topic("Space", "Comets")));
        List<TopicSuggestions.Idea> ideas = suggestions.suggest(USER, "192.0.2.1", "space").ideas();
        assertNotEquals(ideas.get(0).key(), ideas.get(1).key());
        // 18 random bytes, base64url: 144 bits, not guessable
        assertEquals(24, ideas.get(0).key().length());
        assertTrue(ideas.get(0).key().matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void limitsAreTurnedIntoMessages() {
        for (AiTopicRepository.Outcome outcome : List.of(AiTopicRepository.Outcome.USER_LIMIT,
                AiTopicRepository.Outcome.DAILY_LIMIT, AiTopicRepository.Outcome.CATALOG_FULL,
                AiTopicRepository.Outcome.BANNED, AiTopicRepository.Outcome.CONFLICT)) {
            llm.replies.add(reply(topic("Space", "Black Holes")));
            String key = suggestions.suggest(USER, "192.0.2." + outcome.ordinal(), "black holes").ideas().get(0).key();
            repository.nextOutcome = outcome;
            ApiException e = assertThrows(ApiException.class, () -> suggestions.add(USER, key));
            boolean limit = outcome == AiTopicRepository.Outcome.USER_LIMIT || outcome == AiTopicRepository.Outcome.DAILY_LIMIT;
            assertEquals(limit ? 429 : 409, e.status(), outcome.name());
        }
    }

    @Test
    void anExistingTopicIsReturnedNotCreated() {
        llm.replies.add(reply(topic("Space", "Black Holes")));
        String key = suggestions.suggest(USER, "192.0.2.1", "black holes").ideas().get(0).key();
        repository.nextOutcome = AiTopicRepository.Outcome.EXISTS;
        assertFalse(suggestions.add(USER, key).created());
    }

    @Test
    void emptyOrSymbolOnlyRequestsAreRefusedBeforeTheModelRuns() {
        for (String bad : new String[]{null, "", "   ", "!!!", "a".repeat(TopicPrompt.MAX_REQUEST + 1)}) {
            assertEquals(400, assertThrows(ApiException.class, () -> suggestions.suggest(USER, "192.0.2.1", bad)).status());
        }
        assertTrue(llm.prompts.isEmpty());
    }

    @Test
    void eachUserCanOnlyAskSoManyTimesAnHour() {
        for (int i = 0; i < TopicSuggestions.REQUESTS_PER_HOUR; i++) {
            suggestions.suggest(USER, "192.0.2." + i, "space");
        }
        assertEquals(429, assertThrows(ApiException.class, () -> suggestions.suggest(USER, "192.0.2.99", "space")).status());
        // someone else is fine
        suggestions.suggest(OTHER_USER, "192.0.2.99", "space");
    }

    @Test
    void eachIpCanOnlyAskSoManyTimesAnHourAcrossAccounts() {
        for (int i = 0; i < TopicSuggestions.REQUESTS_PER_HOUR_PER_IP; i++) {
            suggestions.suggest(100 + i, "192.0.2.1", "space");
        }
        assertEquals(429, assertThrows(ApiException.class, () -> suggestions.suggest(999, "192.0.2.1", "space")).status());
    }

    @Test
    void triesOnceMoreAfterBrokenJson() {
        llm.replies.add("not json");
        llm.replies.add(reply(topic("Space", "Black Holes")));
        assertEquals(1, suggestions.suggest(USER, "192.0.2.1", "black holes").ideas().size());
        assertEquals(2, llm.prompts.size());
    }

    @Test
    void twoBrokenRepliesMeanNothingFound() {
        llm.replies.add("not json");
        llm.replies.add("still not json");
        TopicSuggestions.Suggestions result = suggestions.suggest(USER, "192.0.2.1", "black holes");
        assertTrue(result.matches().isEmpty() && result.ideas().isEmpty());
    }

    @Test
    void ollamaBeingDownIs503() {
        llm.replies.add(new IOException("Ollama request failed: ConnectException"));
        assertEquals(503, assertThrows(ApiException.class, () -> suggestions.suggest(USER, "192.0.2.1", "space")).status());
    }

    @Test
    void withoutAnLlmItSaysItIsOff() {
        TopicSuggestions off = new TopicSuggestions(repository, null, clock);
        assertEquals(503, assertThrows(ApiException.class, () -> off.suggest(USER, "192.0.2.1", "space")).status());
    }
}
