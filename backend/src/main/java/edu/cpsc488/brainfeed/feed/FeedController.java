package edu.cpsc488.brainfeed.feed;

import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.RequestBodies;
import edu.cpsc488.brainfeed.auth.AuthController;
import edu.cpsc488.brainfeed.auth.User;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Endpoints for topics, interests and the video feed. The request and response shapes are in the
 * README's "Feed API" table.
 *
 * <p>/api/topics and /api/feed are public so guests can use them. The feed only reads from our
 * database, so nobody can use it to burn through our YouTube quota.
 *
 * <p>/api/me/interests needs a login and only touches the logged-in user's own interests. The
 * user comes from the session cookie, so there's no user id in the request to tamper with.
 */
public class FeedController {

    static final int PAGE_SIZE = 10;
    // way more than anyone will scroll, and stops requests for page 1,000,000
    static final int MAX_PAGE = 100;
    // there are only 10 topics, so anything past this is junk
    static final int MAX_TOPICS_PER_REQUEST = 50;

    record InterestsRequest(List<Integer> topicIds) {
    }

    /** nextPage is null when there are no more videos. */
    record FeedPage(List<Video> videos, Integer nextPage) {
    }

    /** The parsed query string of GET /api/feed. */
    record FeedQuery(List<Integer> topicIds, long seed, int page) {
    }

    private final TopicRepository topics;
    private final VideoRepository videos;
    private final AuthController auth;

    public FeedController(TopicRepository topics, VideoRepository videos, AuthController auth) {
        this.topics = topics;
        this.videos = videos;
        this.auth = auth;
    }

    public void register(RoutesConfig routes) {
        routes.get("/api/topics", this::handleTopics);
        routes.get("/api/me/interests", this::handleGetInterests);
        routes.put("/api/me/interests", this::handlePutInterests);
        routes.get("/api/feed", this::handleFeed);
    }

    private void handleTopics(Context ctx) {
        ctx.json(Map.of("topics", topics.listAll()));
    }

    private void handleGetInterests(Context ctx) {
        User user = requireUser(ctx);
        ctx.json(Map.of("topicIds", topics.findInterests(user.id())));
    }

    private void handlePutInterests(Context ctx) {
        User user = requireUser(ctx);
        InterestsRequest req = RequestBodies.parse(ctx, InterestsRequest.class);
        Set<Integer> picked = checkTopicIds(req.topicIds(), validTopicIds());
        topics.replaceInterests(user.id(), picked);
        ctx.json(Map.of("topicIds", List.copyOf(picked)));
    }

    /** GET /api/feed?topics=1,2,3&seed=123&page=0 */
    private void handleFeed(Context ctx) {
        FeedQuery query = parseFeedQuery(ctx.queryParam("topics"), ctx.queryParam("seed"), ctx.queryParam("page"));
        Set<Integer> topicIds = checkTopicIds(query.topicIds(), validTopicIds());

        // ask for one extra video so we know if there's another page after this one
        List<Video> found = videos.page(topicIds, query.seed(), query.page() * PAGE_SIZE, PAGE_SIZE + 1);
        ctx.json(toPage(found, query.page()));
    }

    /** Reads the feed's query parameters. Throws a 400 if anything is missing or not a number. */
    static FeedQuery parseFeedQuery(String topicsParam, String seedParam, String pageParam) {
        if (topicsParam == null || topicsParam.isBlank()) {
            throw ApiException.badRequest("Pick at least one topic.");
        }
        String[] parts = topicsParam.split(",", -1);
        if (parts.length > MAX_TOPICS_PER_REQUEST) {
            throw ApiException.badRequest("Too many topics.");
        }
        try {
            List<Integer> topicIds = new ArrayList<>();
            for (String part : parts) {
                topicIds.add(Integer.parseInt(part.trim()));
            }
            long seed = Long.parseLong(orDefault(seedParam, "0"));
            int page = Integer.parseInt(orDefault(pageParam, "0"));
            if (page < 0 || page > MAX_PAGE) {
                throw ApiException.badRequest("Invalid feed request.");
            }
            return new FeedQuery(topicIds, seed, page);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Invalid feed request.");
        }
    }

    /**
     * Makes sure at least one topic was picked and every id is a real topic. Returns the ids with
     * duplicates removed, in the order they were given.
     */
    static Set<Integer> checkTopicIds(List<Integer> ids, Set<Integer> validIds) {
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(Objects::isNull)) {
            throw ApiException.badRequest("Pick at least one topic.");
        }
        Set<Integer> unique = new LinkedHashSet<>(ids);
        if (!validIds.containsAll(unique)) {
            throw ApiException.badRequest("Unknown topic.");
        }
        return unique;
    }

    /** Cuts the extra lookahead video off and works out the next page number. */
    static FeedPage toPage(List<Video> found, int page) {
        boolean hasMore = found.size() > PAGE_SIZE && page < MAX_PAGE;
        List<Video> shown = found.subList(0, Math.min(found.size(), PAGE_SIZE));
        return new FeedPage(shown, hasMore ? page + 1 : null);
    }

    private Set<Integer> validTopicIds() {
        return topics.listAll().stream().map(Topic::id).collect(Collectors.toSet());
    }

    private User requireUser(Context ctx) {
        return auth.currentUser(ctx).orElseThrow(() -> ApiException.unauthorized("Not logged in."));
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
