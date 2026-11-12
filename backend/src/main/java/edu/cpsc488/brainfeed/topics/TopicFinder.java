package edu.cpsc488.brainfeed.topics;

import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.auth.LoginRateLimiter;
import edu.cpsc488.brainfeed.feed.Topic;

import java.time.Duration;
import java.util.Optional;

/**
 * "Type a topic, get it": someone types one to three words and, in well under a second, either
 * gets the catalog topic they meant or a new one made on the spot. Nothing here waits on the
 * LLM. The steps:
 * <ol>
 *   <li>clean up and check the name ({@link TopicNames}), then the denylist ({@link TopicDenylist})</li>
 *   <li>look for it in the catalog, allowing plurals and small typos ({@link TopicMatcher})</li>
 *   <li>if it's new: check banned names and the limits, then add it under a quick guess at its
 *       subject ({@link SubjectGuesser}), all inside one lock so parallel adds can't race</li>
 *   <li>add it to the user's interests</li>
 * </ol>
 * The caller then fetches its videos and queues it for the LLM review ({@link TopicReviewer}).
 */
public class TopicFinder {

    // Each request is cheap now (no model call), so these are only there to stop scripts. A
    // person typing topics one after another stays well under them.
    static final int REQUESTS_PER_HOUR = 60;
    static final int REQUESTS_PER_HOUR_PER_IP = 120;
    // 10 new topics a day per user, 200 a day overall, at most 40 subjects (so "Select all
    // subjects" stays well under the feed's 200-topic limit) and 2,000 topics in total.
    static final AiTopicRepository.Limits LIMITS = new AiTopicRepository.Limits(10, 200, 40, 2000);
    // The feed takes at most 200 topics per request (FeedController.MAX_TOPICS_PER_REQUEST).
    static final int MAX_INTERESTS = 200;

    /**
     * What the user typed turned into a topic. created: it was just added. similar: a likely
     * typo of a catalog name was picked (the user can add their own spelling instead).
     */
    public record Found(Topic topic, boolean created, boolean similar) {
    }

    private final AiTopicRepository repository;
    private final LoginRateLimiter perUser = new LoginRateLimiter(REQUESTS_PER_HOUR, Duration.ofHours(1));
    private final LoginRateLimiter perIp = new LoginRateLimiter(REQUESTS_PER_HOUR_PER_IP, Duration.ofHours(1));

    public TopicFinder(AiTopicRepository repository) {
        this.repository = repository;
    }

    /**
     * Finds or adds the topic {@code typed} names and makes the user follow it. With
     * {@code asTyped}, typo matches are skipped, for "No, I meant Generics". Throws ApiException
     * with a message for the user.
     */
    public Found find(long userId, String ip, String typed, boolean asTyped) {
        String userKey = "user:" + userId;
        String ipKey = "ip:" + ip;
        if (perUser.isLimited(userKey) || perIp.isLimited(ipKey)) {
            throw ApiException.tooManyRequests("That's a lot of topics for one hour. Try again later.");
        }
        perUser.record(userKey);
        perIp.record(ipKey);

        TopicNames.Checked checked = TopicNames.check(typed);
        if (!checked.ok()) {
            throw ApiException.badRequest(checked.problem());
        }
        String name = checked.name();
        if (TopicDenylist.blocks(name)) {
            throw blocked();
        }
        boolean allowTypos = !asTyped;
        // Most adds are for topics we already have, so look without taking the lock first.
        Optional<TopicMatcher.Match> match = TopicMatcher.find(name, repository.catalog(), allowTypos);
        Found found;
        if (match.isPresent()) {
            found = new Found(match.get().topic(), false, match.get().kind() == TopicMatcher.Kind.SIMILAR);
        } else {
            found = create(userId, name, allowTypos);
        }
        if (!repository.follow(userId, found.topic().id(), MAX_INTERESTS)) {
            throw ApiException.conflict("You follow " + MAX_INTERESTS + " topics already. Remove some to add more.");
        }
        return found;
    }

    private Found create(long userId, String name, boolean allowTypos) {
        AiTopicRepository.Result result = repository.add(userId, name, TopicParser.query(null, name),
                catalog -> TopicMatcher.find(name, catalog, allowTypos),
                catalog -> SubjectGuesser.guess(name, catalog), LIMITS);
        return switch (result.outcome()) {
            case ADDED -> new Found(result.topic(), true, false);
            // someone added it (or something close) a moment ago
            case EXISTS -> new Found(result.topic(), false, result.kind() == TopicMatcher.Kind.SIMILAR);
            case BANNED -> throw blocked();
            case USER_LIMIT -> throw ApiException.tooManyRequests(
                    "You've added " + LIMITS.perUserPerDay() + " new topics today. Try again tomorrow.");
            case DAILY_LIMIT -> throw ApiException.tooManyRequests(
                    "We've hit today's limit for new topics. Try again tomorrow.");
            case CATALOG_FULL, NO_SUBJECT -> throw ApiException.conflict("We can't add more topics right now.");
        };
    }

    // The same message for the denylist and banned names, so it doesn't tell anyone which list
    // a word is on.
    private static ApiException blocked() {
        return ApiException.conflict("That topic can't be added.");
    }
}
