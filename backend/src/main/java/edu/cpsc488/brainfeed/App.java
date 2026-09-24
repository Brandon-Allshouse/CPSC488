package edu.cpsc488.brainfeed;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariDataSource;
import edu.cpsc488.brainfeed.auth.AuthController;
import edu.cpsc488.brainfeed.auth.BreachedPasswordChecker;
import edu.cpsc488.brainfeed.auth.PasswordHasher;
import edu.cpsc488.brainfeed.auth.PasswordPolicy;
import edu.cpsc488.brainfeed.auth.SessionRepository;
import edu.cpsc488.brainfeed.auth.UserRepository;
import edu.cpsc488.brainfeed.db.Database;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Backend entry point. Reads settings, connects to Postgres (applying any pending migrations),
 * wires up the controllers, and starts the Javalin HTTP server.
 *
 * <p>To add a feature, give its controller a {@code register(RoutesConfig routes)} method like
 * {@link AuthController}'s, and call it inside {@code Javalin.create} below. Use
 * {@link AuthController#currentUser} to find out who is making a request.
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    private static final Set<HandlerType> STATE_CHANGING =
            Set.of(HandlerType.POST, HandlerType.PUT, HandlerType.PATCH, HandlerType.DELETE);

    public static void main(String[] args) {
        Map<String, String> dotEnv = DotEnv.load();
        Config settings = new Config(dotEnv);

        // Every setting is documented in .env.example.
        int port = Integer.parseInt(settings.get("PORT", "7070"));
        String dbUrl = settings.get("DB_URL", "jdbc:postgresql://localhost:5432/brainfeed");
        String dbUser = settings.get("DB_USER", "brainfeed");
        // Secrets have no defaults on purpose: a missing secret stops startup with a clear error,
        // rather than letting the app quietly run with a password that's published on GitHub.
        String dbPassword = settings.require("DB_PASSWORD");
        byte[] pepper = decodePepper(settings.require("PASSWORD_PEPPER"));
        boolean secureCookies = Boolean.parseBoolean(settings.get("COOKIE_SECURE", "false"));
        boolean breachCheck = Boolean.parseBoolean(settings.get("PASSWORD_BREACH_CHECK", "true"));
        // Where the frontend is served from, exactly as it appears in the browser's address bar
        // (scheme + host + port, no trailing slash). Used by rejectCrossSiteWrites below.
        Set<String> allowedOrigins = Arrays.stream(settings.get("ALLOWED_ORIGINS", "http://localhost:5173").split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        HikariDataSource dataSource = Database.connect(dbUrl, dbUser, dbPassword);
        Database.migrate(dataSource);

        UserRepository users = new UserRepository(dataSource);
        // Users must log in again after 7 days no matter what (NIST SP 800-63B allows up to 30),
        // or after 24 hours without using the site.
        SessionRepository sessions = new SessionRepository(dataSource, Duration.ofDays(7), Duration.ofHours(24));
        // At most 4 passwords are hashed at once. Each hash needs ~19 MiB of RAM, so this caps
        // hashing memory at ~76 MiB even if someone floods the login endpoint.
        PasswordHasher hasher = new PasswordHasher(pepper, 4);
        // Turning the breach check off (PASSWORD_BREACH_CHECK=false) is for working offline only.
        PasswordPolicy policy = new PasswordPolicy(breachCheck ? new BreachedPasswordChecker() : password -> false);
        AuthController auth = new AuthController(users, sessions, hasher, policy, secureCookies);

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // Reject bodies with unexpected fields instead of silently ignoring them.
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        Javalin app = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(mapper, false));
            // Largest request body the server will accept. Every current endpoint takes a few
            // hundred bytes, so 16 KB rejects oversized junk early. Raise it only if a new
            // endpoint (e.g. file upload) genuinely needs more.
            config.http.maxRequestSize = 16_384L;
            config.startup.showJavalinBanner = false;
            // Don't advertise the server software and version to attackers.
            config.jetty.modifyHttpConfiguration(http -> http.setSendServerVersion(false));

            // Runs before every route, so new endpoints get these protections automatically.
            config.routes.before(ctx -> {
                setSecurityHeaders(ctx, secureCookies);
                rejectCrossSiteWrites(ctx, allowedOrigins);
            });

            // Expected errors (bad input, not logged in, ...) become {"error": "..."} with their status.
            config.routes.exception(ApiException.class, (e, ctx) ->
                    ctx.status(e.status()).json(Map.of("error", e.getMessage())));
            // Anything else is a bug. Log the details here, but only send the client a generic message,
            // since stack traces and SQL errors reveal internals an attacker could use.
            config.routes.exception(Exception.class, (e, ctx) -> {
                log.error("Unhandled error on {} {}", ctx.method(), ctx.path(), e);
                ctx.status(500).json(Map.of("error", "Something went wrong on our end."));
            });

            config.routes.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));
            auth.register(config.routes);
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            dataSource.close();
        }));

        app.start(port);
    }

    private static void setSecurityHeaders(Context ctx, boolean https) {
        // The API only returns JSON. Even if someone tricks a browser into opening an API URL
        // directly, these make sure the response can't run scripts, be framed by another site
        // (clickjacking), be reinterpreted as HTML, or be cached with user data in it.
        ctx.header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.header("X-Frame-Options", "DENY");
        ctx.header("Referrer-Policy", "no-referrer");
        ctx.header("Cache-Control", "no-store");
        // HSTS tells browsers to only ever use HTTPS for this site. Sending it over plain HTTP
        // (local dev) would be ignored at best, so only send it when we're behind HTTPS.
        if (https) {
            ctx.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
    }

    /**
     * Blocks cross-site request forgery (CSRF): another website making a logged-in user's browser
     * send requests to our API. This is a second layer on top of the SameSite=Strict cookie.
     *
     * <ul>
     *   <li>Requiring {@code Content-Type: application/json}: a plain HTML form on another site
     *       can't set that header, and a script on another site that sets it triggers a CORS
     *       "preflight" check, which this server never approves.</li>
     *   <li>Checking {@code Origin}: browsers attach it to cross-site POSTs, so we reject any that
     *       don't come from our frontend. Requests with no Origin (curl, Postman, tests) are
     *       allowed, because they don't carry a victim's browser cookies.</li>
     * </ul>
     *
     * The frontend's {@code request()} helper in {@code api/auth.ts} sets the header automatically.
     */
    private static void rejectCrossSiteWrites(Context ctx, Set<String> allowedOrigins) {
        if (!STATE_CHANGING.contains(ctx.method())) {
            return;
        }
        String contentType = ctx.header("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            throw ApiException.unsupportedMediaType("Requests must be sent as JSON.");
        }
        String origin = ctx.header("Origin");
        if (origin != null && !allowedOrigins.contains(origin)) {
            throw ApiException.forbidden("Cross-site request blocked.");
        }
    }

    /** Decodes the base64 pepper from .env and checks it's long enough (256 bits). */
    private static byte[] decodePepper(String value) {
        String howTo = " See \"Create your .env file\" in the README for how to generate one.";
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("PASSWORD_PEPPER must be base64." + howTo);
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("PASSWORD_PEPPER must be at least 32 bytes." + howTo);
        }
        return bytes;
    }

    /**
     * Looks up a setting: a real environment variable wins, otherwise the value from .env
     * (see {@link DotEnv}), otherwise the fallback. Blank values count as unset.
     */
    private record Config(Map<String, String> dotEnv) {

        String get(String name, String fallback) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                value = dotEnv.get(name);
            }
            return value == null || value.isBlank() ? fallback : value;
        }

        String require(String name) {
            String value = get(name, null);
            if (value == null) {
                throw new IllegalStateException(name + " is not set. Copy .env.example to .env in the repo root"
                        + " and fill it in (see \"Create your .env file\" in the README).");
            }
            return value;
        }
    }
}
