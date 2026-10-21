package edu.cpsc488.brainfeed.history;

import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.auth.AuthController;
import edu.cpsc488.brainfeed.auth.User;
import edu.cpsc488.brainfeed.feed.FeedController;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;

import java.util.Map;

/**
 * The logged-in user's watch history: the videos they pressed play on, newest first. The request
 * and response shapes are in the README's "Watch history API" table.
 *
 * <p>Every endpoint needs a login and only touches that user's own history. The user comes from
 * the session cookie, so there's no user id in the request to tamper with. Nobody else can see a
 * user's history, admins included.
 */
public class WatchHistoryController {

    /** How many videos each user's history keeps. */
    public static final int LIMIT = 25;

    private final WatchHistoryRepository history;
    private final AuthController auth;

    public WatchHistoryController(WatchHistoryRepository history, AuthController auth) {
        this.history = history;
        this.auth = auth;
    }

    public void register(RoutesConfig routes) {
        routes.get("/api/me/watch-history", this::handleList);
        routes.put("/api/me/watch-history/{youtubeId}", this::handleRecord);
        routes.delete("/api/me/watch-history/{youtubeId}", this::handleRemove);
        routes.delete("/api/me/watch-history", this::handleClear);
    }

    private void handleList(Context ctx) {
        User user = requireUser(ctx);
        ctx.json(Map.of("videos", history.list(user.id()), "limit", LIMIT));
    }

    /** Called when the user presses play. PUT, because recording the same video twice is harmless. */
    private void handleRecord(Context ctx) {
        User user = requireUser(ctx);
        String youtubeId = FeedController.checkYoutubeId(ctx.pathParam("youtubeId"));
        if (!history.record(user.id(), youtubeId)) {
            throw ApiException.notFound("Video not found.");
        }
        ctx.status(204);
    }

    private void handleRemove(Context ctx) {
        User user = requireUser(ctx);
        String youtubeId = FeedController.checkYoutubeId(ctx.pathParam("youtubeId"));
        if (!history.remove(user.id(), youtubeId)) {
            throw ApiException.notFound("That video isn't in your history.");
        }
        ctx.status(204);
    }

    private void handleClear(Context ctx) {
        User user = requireUser(ctx);
        history.clear(user.id());
        ctx.status(204);
    }

    private User requireUser(Context ctx) {
        return auth.currentUser(ctx).orElseThrow(() -> ApiException.unauthorized("Not logged in."));
    }
}
