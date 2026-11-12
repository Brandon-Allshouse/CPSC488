package edu.cpsc488.brainfeed.learning;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.RequestBodies;
import edu.cpsc488.brainfeed.auth.AuthController;
import edu.cpsc488.brainfeed.auth.User;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;

import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/** All routes inherit the app's JSON size limit, CSRF checks and security headers. */
public class LearningProgressController {
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private final LearningProgressService service;
    private final Function<Context, Optional<User>> currentUser;

    public LearningProgressController(LearningProgressService service, AuthController auth) {
        this(service, auth::currentUser);
    }

    LearningProgressController(LearningProgressService service, Function<Context, Optional<User>> currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    public void register(RoutesConfig routes) {
        routes.get("/api/me/learning-progress", this::handleList);
        routes.get("/api/me/learning-progress/export", this::handleExport);
        routes.put("/api/me/learning-progress/{youtubeId}", this::handleStatus);
        routes.post("/api/me/learning-progress/{youtubeId}/review", this::handleReview);
    }

    private User requireUser(Context context) {
        return currentUser.apply(context).orElseThrow(() -> ApiException.unauthorized("Not logged in."));
    }

    private void handleList(Context context) {
        context.json(service.snapshot(requireUser(context).id()));
    }

    /** Generates an owner-only file in memory; no private temporary files remain on disk. */
    private void handleExport(Context context) {
        User user = requireUser(context);
        byte[] csv = LearningProgressCsv.encode(service.snapshot(user.id()));
        context.contentType("text/csv; charset=utf-8");
        context.header("Content-Disposition", "attachment; filename=\"brainfeed-learning-progress.csv\"");
        context.result(csv);
    }

    private void handleStatus(Context context) {
        User user = requireUser(context);
        String youtubeId = validateVideoId(context.pathParam("youtubeId"));
        String status = readStatus(RequestBodies.parse(context, JsonNode.class));
        service.setStatus(user.id(), youtubeId, status);
        context.status(204);
    }

    private void handleReview(Context context) {
        User user = requireUser(context);
        String youtubeId = validateVideoId(context.pathParam("youtubeId"));
        // This operation has no user-controlled fields, including no owner or timestamps.
        if (!context.body().isBlank()) {
            throw ApiException.badRequest("Send no body when marking a review.");
        }
        service.review(user.id(), youtubeId);
        context.status(204);
    }

    static String readStatus(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 1 || !body.has("status")
                || !body.get("status").isTextual()) {
            throw ApiException.badRequest("Send only a status as text.");
        }
        return LearningProgressService.validateStatus(body.get("status").textValue());
    }

    static String validateVideoId(String youtubeId) {
        if (youtubeId == null || !VIDEO_ID.matcher(youtubeId).matches()) {
            throw ApiException.badRequest("Invalid video id.");
        }
        return youtubeId;
    }
}
