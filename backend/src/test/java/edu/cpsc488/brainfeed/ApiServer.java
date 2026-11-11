package edu.cpsc488.brainfeed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.config.RoutesConfig;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A real server for API tests, set up with {@link App#configure} like production (security
 * headers, CSRF check, body size cap, strict JSON, safe errors), plus the routes a test adds.
 * Requests go over real HTTP, and the client keeps cookies like a browser, so a login carries
 * over to later requests.
 */
public final class ApiServer implements AutoCloseable {

    public static final String ORIGIN = "http://localhost:5173";

    private final Javalin app;
    private final CookieManager cookies = new CookieManager();
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiServer(boolean https, Consumer<RoutesConfig> routes) {
        app = Javalin.create(config -> {
            App.configure(config, https, Set.of(ORIGIN));
            routes.accept(config.routes);
        }).start(0);
        client = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    public ApiServer(Consumer<RoutesConfig> routes) {
        this(false, routes);
    }

    public HttpResponse<String> get(String path) {
        return send(builder(path).GET());
    }

    /** A JSON POST/PUT/DELETE from our own frontend (right Content-Type and Origin). */
    public HttpResponse<String> send(String method, String path, String json) {
        return send(builder(path)
                .header("Content-Type", "application/json")
                .header("Origin", ORIGIN)
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json)));
    }

    public HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public HttpRequest.Builder builder(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path));
    }

    public JsonNode json(HttpResponse<String> response) {
        try {
            return mapper.readTree(response.body());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Forgets cookies, like a different browser. */
    public void clearCookies() {
        cookies.getCookieStore().removeAll();
    }

    @Override
    public void close() {
        client.close();
        app.stop();
    }
}
