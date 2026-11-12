package edu.cpsc488.brainfeed.topics;

import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.RequestBodies;
import edu.cpsc488.brainfeed.auth.AuthController;
import edu.cpsc488.brainfeed.auth.SecurityLog;
import edu.cpsc488.brainfeed.auth.User;
import edu.cpsc488.brainfeed.feed.FeedController;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * POST /api/me/topics (README, "Topic finder API"): type a topic name, get that topic. Needs a
 * login, since adding changes the catalog everyone sees, so each add is tied to an account (and
 * its limits). The usual CSRF, JSON-only and body-size checks from App.java apply.
 */
public class TopicFinderController {

    /** asTyped is optional: true skips typo matching ("No, add Generics, not Genetics"). */
    record AddRequest(String name, Boolean asTyped) {
    }

    private final TopicFinder finder;
    private final TopicReviewer reviewer;
    private final FeedController.MissingVideoFetcher videos;
    private final Function<Context, Optional<User>> currentUser;

    public TopicFinderController(TopicFinder finder, TopicReviewer reviewer,
                                 FeedController.MissingVideoFetcher videos, AuthController auth) {
        this(finder, reviewer, videos, auth::currentUser);
    }

    TopicFinderController(TopicFinder finder, TopicReviewer reviewer, FeedController.MissingVideoFetcher videos,
                          Function<Context, Optional<User>> currentUser) {
        this.finder = finder;
        this.reviewer = reviewer;
        this.videos = videos;
        this.currentUser = currentUser;
    }

    /** Adds the topic finder route. */
    public void register(RoutesConfig routes) {
        routes.post("/api/me/topics", this::handleAdd);
    }

    private void handleAdd(Context ctx) {
        User user = currentUser.apply(ctx)
                .orElseThrow(() -> ApiException.unauthorized("Log in to add topics."));
        AddRequest body = RequestBodies.parse(ctx, AddRequest.class);
        TopicFinder.Found found;
        try {
            found = finder.find(user.id(), ctx.ip(), body.name(), Boolean.TRUE.equals(body.asTyped()));
        } catch (ApiException e) {
            if (e.status() == 409 || e.status() == 429) {
                // Blocked names and limits are worth seeing in the log: they're how abuse shows up.
                SecurityLog.failure("topic_add", ctx, user.id(), e.getMessage());
            }
            throw e;
        }
        int topicId = found.topic().id();
        if (found.created()) {
            // Who added what goes in the security log, so an admin can trace a bad topic to an account.
            SecurityLog.success("topic_added", ctx, user.id(), "topic=" + topicId);
            reviewer.queue(topicId);
        }
        // Start the YouTube search now, so videos are ready by the time they open the feed. Only
        // searches topics never searched before, within the daily quota (see FeedRefresher).
        videos.fetchMissing(List.of(topicId));
        ctx.status(found.created() ? 201 : 200).json(Map.of(
                "topic", found.topic(),
                "created", found.created(),
                "similar", found.similar()));
    }
}
