package edu.cpsc488.brainfeed.saved;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.RequestBodies;
import edu.cpsc488.brainfeed.auth.AuthController;
import edu.cpsc488.brainfeed.auth.User;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** The existing app-wide CSRF, JSON, size and security-header checks also apply to these routes. */
public class StudyNoteController {
    // Even JSON with escaped characters fits the existing 16 KB request limit.
    static final int MAX_NOTE_LENGTH = 2000;
    private final StudyNoteRepository notes;
    private final Function<Context, Optional<User>> currentUser;

    public StudyNoteController(StudyNoteRepository notes, AuthController auth) {
        this(notes, auth::currentUser);
    }

    StudyNoteController(StudyNoteRepository notes, Function<Context, Optional<User>> currentUser) {
        this.notes = notes;
        this.currentUser = currentUser;
    }

    public void register(RoutesConfig routes) {
        routes.get("/api/me/study-notes", this::handleList);
        routes.put("/api/me/study-notes/{youtubeId}", this::handleSave);
    }

    private User requireUser(Context context) {
        return currentUser.apply(context).orElseThrow(() -> ApiException.unauthorized("Not logged in."));
    }

    private void handleList(Context context) {
        User user = requireUser(context);
        context.json(Map.of("notes", notes.list(user.id())));
    }

    private void handleSave(Context context) {
        User user = requireUser(context);
        String youtubeId = SavedVideoController.validateYoutubeId(context.pathParam("youtubeId"));
        JsonNode body = RequestBodies.parse(context, JsonNode.class);
        if (!body.isObject() || body.size() != 1 || !body.has("note") || !body.get("note").isTextual()) {
            throw ApiException.badRequest("Send a note as text.");
        }
        String text = checkNote(body.get("note").textValue());
        StudyNote saved = notes.save(user.id(), youtubeId, text)
                .orElseThrow(() -> ApiException.notFound("Save this video to your Study notebook first."));
        context.json(Map.of("note", saved));
    }

    static String checkNote(String note) {
        if (note == null || note.length() > MAX_NOTE_LENGTH || note.indexOf('\0') >= 0) {
            throw ApiException.badRequest("Notes must be text of at most " + MAX_NOTE_LENGTH + " characters.");
        }
        return note.isBlank() ? "" : note;
    }
}
