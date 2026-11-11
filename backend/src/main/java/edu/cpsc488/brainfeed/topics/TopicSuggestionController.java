package edu.cpsc488.brainfeed.topics;

import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.RequestBodies;
import edu.cpsc488.brainfeed.auth.AuthController;
import edu.cpsc488.brainfeed.auth.SecurityLog;
import edu.cpsc488.brainfeed.auth.User;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The AI topic finder's endpoints (README, "Topic finder API"). Both need a login: asking runs
 * the model, and adding changes the catalog everyone sees, so each is tied to an account (and
 * its limits). The usual CSRF, JSON-only and body-size checks from App.java apply.
 */
public class TopicSuggestionController {

    record SuggestRequest(String text) {
    }

    record AddRequest(String key) {
    }

    private final TopicSuggestions suggestions;
    private final Function<Context, Optional<User>> currentUser;

    public TopicSuggestionController(TopicSuggestions suggestions, AuthController auth) {
        this(suggestions, auth::currentUser);
    }

    TopicSuggestionController(TopicSuggestions suggestions, Function<Context, Optional<User>> currentUser) {
        this.suggestions = suggestions;
        this.currentUser = currentUser;
    }

    public void register(RoutesConfig routes) {
        routes.post("/api/me/topic-suggestions", this::handleSuggest);
        routes.post("/api/me/topics", this::handleAdd);
    }

    private User requireUser(Context ctx) {
        return currentUser.apply(ctx).orElseThrow(() -> ApiException.unauthorized("Log in to find topics with AI."));
    }

    private void handleSuggest(Context ctx) {
        User user = requireUser(ctx);
        SuggestRequest body = RequestBodies.parse(ctx, SuggestRequest.class);
        ctx.json(suggestions.suggest(user.id(), ctx.ip(), body.text()));
    }

    private void handleAdd(Context ctx) {
        User user = requireUser(ctx);
        String key = RequestBodies.parse(ctx, AddRequest.class).key();
        TopicSuggestions.Added added = suggestions.add(user.id(), key);
        if (added.created()) {
            // Who added what goes in the security log, so an admin can trace a bad topic to an account.
            added.topics().forEach(topic -> SecurityLog.success("topic_added", ctx, user.id(), "topic=" + topic.id()));
        }
        ctx.status(added.created() ? 201 : 200).json(Map.of("topics", added.topics()));
    }
}
