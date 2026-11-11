package edu.cpsc488.brainfeed.topics;

import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.auth.LoginRateLimiter;
import edu.cpsc488.brainfeed.feed.Topic;
import edu.cpsc488.brainfeed.llm.Llm;
import edu.cpsc488.brainfeed.llm.UntrustedText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The AI topic finder. A user describes what they want to learn; the LLM picks matching catalog
 * topics and proposes new ones; the user then adds the new ones they want.
 *
 * <p>Adding takes a key we handed out, not a name, so the only topics that can ever be created
 * are ones that came from the model and passed {@link TopicParser}. A client can't make up its
 * own topic names. Keys are random, tied to the user who asked, and expire after
 * {@link #PROPOSALS_LAST}.
 *
 * <p>Asking takes a CPU 10-30 seconds, so it's limited per user and per IP, and only one request
 * talks to the model at a time.
 */
public class TopicSuggestions {

    private static final Logger log = LoggerFactory.getLogger(TopicSuggestions.class);

    static final int REQUESTS_PER_HOUR = 10;
    static final int REQUESTS_PER_HOUR_PER_IP = 30;
    static final Duration PROPOSALS_LAST = Duration.ofMinutes(30);
    // How long a request waits for someone else's to finish before we say we're busy.
    static final Duration WAIT_FOR_MODEL = Duration.ofSeconds(60);
    // 10 a day per user, 200 a day overall, at most 40 subjects (so "Select all subjects" stays
    // well under the feed's 200-topic limit) and 2,000 topics in total.
    static final AiTopicRepository.Limits LIMITS = new AiTopicRepository.Limits(10, 200, 40, 2000);

    /** A new topic the user can add. key is what they send back to add it. */
    public record Idea(String key, String name, String subject, boolean newSubject) {
    }

    /** matches: catalog topics that fit (the user can pick them). ideas: new topics to add. */
    public record Suggestions(List<Topic> matches, List<Idea> ideas) {
    }

    /** topics: what the user should pick. created: true if they were just added, false if they already existed. */
    public record Added(List<Topic> topics, boolean created) {
    }

    private record Proposal(Idea idea, Integer subjectId, String searchQuery, Instant expires) {
    }

    /** The model's ideas sorted into catalog matches and new topics (not given keys yet). */
    private record Resolved(List<Topic> matches, List<Proposal> proposals) {
    }

    private final AiTopicRepository repository;
    private final Llm llm;
    private final Clock clock;
    private final LoginRateLimiter perUser = new LoginRateLimiter(REQUESTS_PER_HOUR, Duration.ofHours(1));
    private final LoginRateLimiter perIp = new LoginRateLimiter(REQUESTS_PER_HOUR_PER_IP, Duration.ofHours(1));
    private final Semaphore modelTurn = new Semaphore(1, true);
    // each user's latest suggestions; asking again replaces them
    private final Map<Long, Map<String, Proposal>> proposals = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /** llm is null when OLLAMA_URL isn't set; asking then says the finder isn't running. */
    public TopicSuggestions(AiTopicRepository repository, Llm llm) {
        this(repository, llm, Clock.systemUTC());
    }

    TopicSuggestions(AiTopicRepository repository, Llm llm, Clock clock) {
        this.repository = repository;
        this.llm = llm;
        this.clock = clock;
    }

    /** Asks the model for topics that fit {@code request}. Throws ApiException with a message for the user. */
    public Suggestions suggest(long userId, String ip, String request) {
        String text = checkRequest(request);
        if (llm == null) {
            throw unavailable();
        }
        String userKey = "user:" + userId;
        String ipKey = "ip:" + ip;
        if (perUser.isLimited(userKey) || perIp.isLimited(ipKey)) {
            throw ApiException.tooManyRequests("You've asked a lot this hour. Try again later, or pick from the list.");
        }
        perUser.record(userKey);
        perIp.record(ipKey);

        List<Topic> catalog = repository.catalog();
        List<TopicParser.Idea> ideas = ask(text, catalog);
        return remember(userId, resolve(ideas, catalog));
    }

    /** Adds the topic behind {@code key} and returns what the user should now have. */
    public Added add(long userId, String key) {
        Map<String, Proposal> mine = proposals.get(userId);
        Proposal proposal = mine == null || key == null ? null : mine.get(key);
        if (proposal == null || clock.instant().isAfter(proposal.expires())) {
            throw ApiException.notFound("That suggestion has expired. Search again.");
        }
        Idea idea = proposal.idea();
        AiTopicRepository.Result result = repository.add(userId, proposal.subjectId(),
                idea.newSubject() ? idea.subject() : null,
                idea.newSubject() ? TopicParser.query(null, idea.subject()) : null,
                idea.name(), proposal.searchQuery(), LIMITS);
        switch (result.outcome()) {
            case ADDED, EXISTS -> {
                mine.remove(key);
                return new Added(result.topics(), result.outcome() == AiTopicRepository.Outcome.ADDED);
            }
            case USER_LIMIT -> throw ApiException.tooManyRequests(
                    "You've added " + LIMITS.perUserPerDay() + " topics today. Try again tomorrow.");
            case DAILY_LIMIT -> throw ApiException.tooManyRequests(
                    "We've hit today's limit for new topics. Try again tomorrow.");
            case CATALOG_FULL -> throw ApiException.conflict("We can't add more topics right now.");
            default -> throw ApiException.conflict("That topic can't be added.");
        }
    }

    static String checkRequest(String request) {
        String text = request == null ? "" : UntrustedText.oneLine(request);
        if (text.isEmpty() || text.codePoints().noneMatch(Character::isLetterOrDigit)) {
            throw ApiException.badRequest("Type a few words about what you want to learn.");
        }
        if (text.length() > TopicPrompt.MAX_REQUEST) {
            throw ApiException.badRequest("Keep it under " + TopicPrompt.MAX_REQUEST + " characters.");
        }
        return text;
    }

    private List<TopicParser.Idea> ask(String text, List<Topic> catalog) {
        boolean gotTurn = false;
        try {
            gotTurn = modelTurn.tryAcquire(WAIT_FOR_MODEL.toMillis(), TimeUnit.MILLISECONDS);
            if (!gotTurn) {
                throw ApiException.tooManyRequests("Lots of people are asking right now. Try again in a minute.");
            }
            String prompt = TopicPrompt.userMessage(text, catalog);
            // one more try if the reply wasn't valid JSON; small models occasionally slip
            for (int attempt = 0; attempt < 2; attempt++) {
                Optional<List<TopicParser.Idea>> ideas = TopicParser.parse(llm.chat(TopicPrompt.SYSTEM, prompt, TopicPrompt.SCHEMA));
                if (ideas.isPresent()) {
                    return ideas.get();
                }
            }
            return List.of();
        } catch (IOException e) {
            log.warn("Topic finder failed: {}", e.getMessage());
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } finally {
            if (gotTurn) {
                modelTurn.release();
            }
        }
    }

    /**
     * Sorts the model's ideas into catalog matches and new topics. A new topic goes under the
     * subject the model named if that exists (or under the subject of a topic it named instead),
     * otherwise it brings a new subject with it. Names an admin removed are skipped.
     */
    private Resolved resolve(List<TopicParser.Idea> ideas, List<Topic> catalog) {
        Map<String, Topic> byName = new HashMap<>();
        Map<Integer, Topic> byId = new HashMap<>();
        for (Topic topic : catalog) {
            byName.put(TopicParser.key(topic.name()), topic);
            byId.put(topic.id(), topic);
        }
        Map<Integer, Topic> matches = new LinkedHashMap<>();
        List<Proposal> found = new ArrayList<>();
        for (TopicParser.Idea idea : ideas) {
            Topic same = byName.get(TopicParser.key(idea.name()));
            if (same != null) {
                matches.putIfAbsent(same.id(), same);
                continue;
            }
            if (repository.isBanned(idea.name())) {
                continue;
            }
            Topic subject = byName.get(TopicParser.key(idea.subject()));
            if (subject != null && subject.parentId() != null) {
                subject = byId.get(subject.parentId());
            }
            if (subject == null && repository.isBanned(idea.subject())) {
                continue;
            }
            found.add(subject == null
                    ? new Proposal(new Idea(null, idea.name(), idea.subject(), true), null, idea.searchQuery(), null)
                    : new Proposal(new Idea(null, idea.name(), subject.name(), false), subject.id(), idea.searchQuery(), null));
        }
        return new Resolved(List.copyOf(matches.values()), found);
    }

    /** Gives each new idea a key, stores them for this user (replacing older ones) and builds the reply. */
    private Suggestions remember(long userId, Resolved resolved) {
        Instant expires = clock.instant().plus(PROPOSALS_LAST);
        Map<String, Proposal> mine = new ConcurrentHashMap<>();
        List<Idea> ideas = new ArrayList<>();
        for (Proposal p : resolved.proposals()) {
            String key = newKey();
            Idea idea = new Idea(key, p.idea().name(), p.idea().subject(), p.idea().newSubject());
            mine.put(key, new Proposal(idea, p.subjectId(), p.searchQuery(), expires));
            ideas.add(idea);
        }
        dropExpired();
        proposals.put(userId, mine);
        return new Suggestions(resolved.matches(), List.copyOf(ideas));
    }

    // Keeps the map from growing forever with users who asked once and left.
    private void dropExpired() {
        Instant now = clock.instant();
        proposals.values().removeIf(mine -> mine.values().stream().allMatch(p -> now.isAfter(p.expires())));
    }

    private String newKey() {
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ApiException unavailable() {
        return new ApiException(503, "The AI topic finder isn't running right now. Pick from the list instead.");
    }
}
