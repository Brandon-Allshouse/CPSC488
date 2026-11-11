package edu.cpsc488.brainfeed.study;

import edu.cpsc488.brainfeed.ApiException;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * GET /api/videos/{youtubeId}/study: a video's summary and quiz (see the README's "Study API").
 *
 * <p>Public, like the feed, so guests get summaries too. Asking for one that doesn't exist yet
 * starts writing it, the same way the feed starts fetching new topics; {@link StudyGenerator}
 * keeps that bounded. Takes no body and no user input besides the validated video id.
 */
public class StudyController {

    private static final Pattern YOUTUBE_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    private final StudyGenerator generator;

    public StudyController(StudyGenerator generator) {
        this.generator = generator;
    }

    public void register(RoutesConfig routes) {
        routes.get("/api/videos/{youtubeId}/study", this::handleGet);
    }

    private void handleGet(Context ctx) {
        String youtubeId = ctx.pathParam("youtubeId");
        if (!YOUTUBE_ID.matcher(youtubeId).matches()) {
            throw ApiException.badRequest("Invalid video id.");
        }
        StudyGenerator.Result result = generator.request(youtubeId);
        if (result.status() == StudyGenerator.Status.NOT_FOUND) {
            throw ApiException.notFound("Video not found.");
        }
        ctx.json(body(result));
    }

    static Map<String, Object> body(StudyGenerator.Result result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", result.status().name().toLowerCase(Locale.ROOT));
        if (result.material() != null) {
            body.put("summary", result.material().summary());
            body.put("questions", result.material().questions());
        }
        return body;
    }
}
