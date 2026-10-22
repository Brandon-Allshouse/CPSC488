package edu.cpsc488.brainfeed.feed;

import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.RequestBodies;
import edu.cpsc488.brainfeed.auth.AuthController;
import edu.cpsc488.brainfeed.auth.User;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;

import java.util.Map;

/**
 * Endpoints for saved videos: the user's playlists ("Watch later" plus their own). The request and
 * response shapes are in the README's "Playlists API" table.
 *
 * <p>All of them need a login and only touch the logged-in user's own playlists. The user comes
 * from the session cookie, and PlaylistRepository checks the playlist belongs to them, so a guessed
 * playlist id gets a 404.
 */
public class PlaylistController {

    static final int MAX_NAME = 50;

    record CreatePlaylistRequest(String name) {
    }

    private final PlaylistRepository playlists;
    private final AuthController auth;

    public PlaylistController(PlaylistRepository playlists, AuthController auth) {
        this.playlists = playlists;
        this.auth = auth;
    }

    public void register(RoutesConfig routes) {
        routes.get("/api/me/playlists", this::handleList);
        routes.post("/api/me/playlists", this::handleCreate);
        routes.delete("/api/me/playlists/{id}", this::handleDelete);
        routes.put("/api/me/playlists/{id}/videos/{youtubeId}", this::handleAddVideo);
        routes.delete("/api/me/playlists/{id}/videos/{youtubeId}", this::handleRemoveVideo);
    }

    private void handleList(Context ctx) {
        User user = requireUser(ctx);
        ctx.json(Map.of("playlists", playlists.list(user.id())));
    }

    private void handleCreate(Context ctx) {
        User user = requireUser(ctx);
        CreatePlaylistRequest request = RequestBodies.parse(ctx, CreatePlaylistRequest.class);
        String name = checkName(request.name());
        ctx.status(201).json(Map.of("playlist", playlists.create(user.id(), name)));
    }

    private void handleDelete(Context ctx) {
        User user = requireUser(ctx);
        long id = parsePlaylistId(ctx.pathParam("id"));
        if (!playlists.delete(user.id(), id)) {
            throw ApiException.notFound("Playlist not found.");
        }
        ctx.status(204);
    }

    private void handleAddVideo(Context ctx) {
        User user = requireUser(ctx);
        long id = parsePlaylistId(ctx.pathParam("id"));
        String youtubeId = FeedController.checkYoutubeId(ctx.pathParam("youtubeId"));
        playlists.addVideo(user.id(), id, youtubeId);
        ctx.status(204);
    }

    private void handleRemoveVideo(Context ctx) {
        User user = requireUser(ctx);
        long id = parsePlaylistId(ctx.pathParam("id"));
        String youtubeId = FeedController.checkYoutubeId(ctx.pathParam("youtubeId"));
        if (!playlists.removeVideo(user.id(), id, youtubeId)) {
            throw ApiException.notFound("Playlist not found.");
        }
        ctx.status(204);
    }

    private User requireUser(Context ctx) {
        return auth.currentUser(ctx).orElseThrow(() -> ApiException.unauthorized("Not logged in."));
    }

    /** Trims the name and makes sure it's 1-50 characters on one line. */
    static String checkName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw ApiException.badRequest("Give the playlist a name.");
        }
        if (trimmed.length() > MAX_NAME || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest("Playlist names must be at most " + MAX_NAME + " characters on one line.");
        }
        return trimmed;
    }

    static long parsePlaylistId(String id) {
        try {
            long value = Long.parseLong(id);
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException e) {
            // falls through to the error below
        }
        throw ApiException.badRequest("Invalid playlist id.");
    }
}
