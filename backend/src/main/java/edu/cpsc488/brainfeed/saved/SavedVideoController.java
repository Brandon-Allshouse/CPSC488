package edu.cpsc488.brainfeed.saved;

import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.auth.AuthController;
import edu.cpsc488.brainfeed.auth.User;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Endpoints for a logged-in user's saved videos (the Study notebook). The user always comes from the
 * session cookie, never from the request, so nobody can read or change another user's list.
 */
public class SavedVideoController {

    private static final Pattern YOUTUBE_ID =
            Pattern.compile("^[A-Za-z0-9_-]{11}$");

    private final SavedVideoRepository savedVideos;
    private final Function<Context, Optional<User>> currentUser;

    public SavedVideoController(SavedVideoRepository savedVideos, AuthController auth) {
        this(savedVideos, auth::currentUser);
    }

    // Lets tests pick the logged-in user without a real session.
    SavedVideoController(SavedVideoRepository savedVideos, Function<Context, Optional<User>> currentUser) {
        this.savedVideos = savedVideos;
        this.currentUser = currentUser;
    }

    public void register(RoutesConfig routes) {
        routes.get("/api/me/saved-videos", this::handleList);
        routes.post("/api/me/saved-videos/{youtubeId}", this::handleSave);
        routes.delete("/api/me/saved-videos/{youtubeId}", this::handleRemove);
    }

    private void handleList(Context ctx) {
        User user = requireUser(ctx);

        ctx.json(Map.of(
                "videos",
                savedVideos.findByUser(user.id())
        ));
    }

    private void handleSave(Context ctx) {
        User user = requireUser(ctx);
        String youtubeId = validateYoutubeId(ctx.pathParam("youtubeId"));

        boolean found = savedVideos.save(user.id(), youtubeId);

        if (!found) {
            throw new ApiException(404, "Video not found.");
        }

        ctx.status(204);
    }

    private void handleRemove(Context ctx) {
        User user = requireUser(ctx);
        String youtubeId = validateYoutubeId(ctx.pathParam("youtubeId"));

        savedVideos.remove(user.id(), youtubeId);

        ctx.status(204);
    }

    private User requireUser(Context ctx) {
        return currentUser.apply(ctx)
                .orElseThrow(() ->
                        ApiException.unauthorized("Not logged in."));
    }

    static String validateYoutubeId(String youtubeId) {
        if (youtubeId == null || !YOUTUBE_ID.matcher(youtubeId).matches()) {
            throw ApiException.badRequest("Invalid video id.");
        }

        return youtubeId;
    }
}
