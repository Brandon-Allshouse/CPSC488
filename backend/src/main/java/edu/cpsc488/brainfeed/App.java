package edu.cpsc488.brainfeed;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariDataSource;
import edu.cpsc488.brainfeed.admin.AdminController;
import edu.cpsc488.brainfeed.admin.AdminRepository;
import edu.cpsc488.brainfeed.auth.AuthController;
import edu.cpsc488.brainfeed.auth.BreachedPasswordChecker;
import edu.cpsc488.brainfeed.auth.PasswordHasher;
import edu.cpsc488.brainfeed.auth.PasswordPolicy;
import edu.cpsc488.brainfeed.auth.SecurityLog;
import edu.cpsc488.brainfeed.auth.SessionRepository;
import edu.cpsc488.brainfeed.auth.UserRepository;
import edu.cpsc488.brainfeed.db.Database;
import edu.cpsc488.brainfeed.export.ExportController;
import edu.cpsc488.brainfeed.feed.FeedController;
import edu.cpsc488.brainfeed.feed.FeedRefresher;
import edu.cpsc488.brainfeed.feed.PlaylistController;
import edu.cpsc488.brainfeed.feed.PlaylistRepository;
import edu.cpsc488.brainfeed.feed.TopicRepository;
import edu.cpsc488.brainfeed.feed.VideoRepository;
import edu.cpsc488.brainfeed.feed.YouTubeClient;
import edu.cpsc488.brainfeed.saved.SavedVideoController;
import edu.cpsc488.brainfeed.saved.SavedVideoRepository;
import edu.cpsc488.brainfeed.saved.StudyNoteController;
import edu.cpsc488.brainfeed.saved.StudyNoteRepository;
import edu.cpsc488.brainfeed.history.WatchHistoryController;
import edu.cpsc488.brainfeed.history.WatchHistoryRepository;
import edu.cpsc488.brainfeed.llm.Llm;
import edu.cpsc488.brainfeed.llm.OllamaClient;
import edu.cpsc488.brainfeed.study.StudyController;
import edu.cpsc488.brainfeed.study.StudyGenerator;
import edu.cpsc488.brainfeed.study.StudyRepository;
import edu.cpsc488.brainfeed.topics.AiTopicRepository;
import edu.cpsc488.brainfeed.topics.TopicSuggestionController;
import edu.cpsc488.brainfeed.topics.TopicSuggestions;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

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

    // Ollama model names look like qwen3:4b or library/gemma4:e4b. It ends up in a URL body and the
    // database, so anything else in .env is a typo we stop on.
    private static final Pattern OLLAMA_MODEL = Pattern.compile("^[A-Za-z0-9._/-]{1,80}(:[A-Za-z0-9._-]{1,19})?$");

    public static void main(String[] args) {
        Map<String, String> dotEnv = DotEnv.load();
        Config settings = new Config(dotEnv);

        // Every setting is documented in .env.example.
        int port = settings.port("PORT", 7070);
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
        // Our own reverse proxies, whose X-Forwarded-For header says who the real client is (see
        // ClientIp). Blank means nothing sits in front of the backend.
        ClientIp clientIp = new ClientIp(settings.get("TRUSTED_PROXIES", ""));

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

        TopicRepository topics = new TopicRepository(dataSource);
        VideoRepository videos = new VideoRepository(dataSource);

        SavedVideoRepository savedVideos = new SavedVideoRepository(dataSource);
        SavedVideoController savedVideoController = new SavedVideoController(savedVideos, auth);
        StudyNoteController studyNotes = new StudyNoteController(new StudyNoteRepository(dataSource), auth);
        
        // Optional: without a key the app still runs, but no new videos are fetched.
        String youtubeKey = settings.get("YOUTUBE_API_KEY", null);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "feed-refresher");
            thread.setDaemon(true);
            return thread;
        });
        FeedController.MissingVideoFetcher fetcher;
        if (youtubeKey == null) {
            log.warn("YOUTUBE_API_KEY is not set, so no videos will be fetched. See .env.example.");
            fetcher = topicIds -> false;
        } else {
            // Right away, then every hour. Each run searches a few topics not refreshed in a week.
            // New picks are fetched on demand on the same thread (see FeedRefresher).
            FeedRefresher refresher = new FeedRefresher(topics, videos, new YouTubeClient(youtubeKey), scheduler);
            scheduler.scheduleWithFixedDelay(refresher, 0, 1, TimeUnit.HOURS);
            fetcher = refresher;
        }
        FeedController feed = new FeedController(topics, videos, auth, fetcher);
        // One model client for summaries and the topic finder; null when OLLAMA_URL isn't set.
        Llm llm = llm(settings);
        StudyController study = new StudyController(studyGenerator(llm, youtubeKey, dataSource));
        TopicSuggestionController topicFinder = new TopicSuggestionController(
                new TopicSuggestions(new AiTopicRepository(dataSource), llm), auth);
        AdminRepository adminRepository = new AdminRepository(dataSource);
        AdminController admin = new AdminController(adminRepository, auth);
        ExportController exports = new ExportController(adminRepository, auth);
        WatchHistoryController history = new WatchHistoryController(
                new WatchHistoryRepository(dataSource, WatchHistoryController.LIMIT), auth);
        PlaylistController playlists = new PlaylistController(new PlaylistRepository(dataSource), auth);

        Javalin app = Javalin.create(config -> {
            configure(config, secureCookies, allowedOrigins);
            // ctx.ip() everywhere (rate limits, security log, admin page) gives the real client.
            config.contextResolver.ip = ctx -> clientIp.resolve(ctx.req().getRemoteAddr(), ctx.header("X-Forwarded-For"));

            config.routes.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));
            auth.register(config.routes);
            feed.register(config.routes);
            savedVideoController.register(config.routes);
            studyNotes.register(config.routes);
            admin.register(config.routes);
            exports.register(config.routes);
            history.register(config.routes);
            playlists.register(config.routes);
            study.register(config.routes);
            topicFinder.register(config.routes);
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdownNow();
            app.stop();
            dataSource.close();
        }));

        app.start(port);
    }

    /**
     * The free model running in Ollama, used for video summaries and the AI topic finder. Without
     * OLLAMA_URL the app still runs: saved summaries are shown, but nothing new is written.
     */
    private static Llm llm(Config settings) {
        String ollamaUrl = settings.get("OLLAMA_URL", null);
        String model = settings.get("OLLAMA_MODEL", "gemma4:e4b");
        if (!OLLAMA_MODEL.matcher(model).matches()) {
            throw new IllegalStateException("OLLAMA_MODEL must be an Ollama model name like gemma4:e4b.");
        }
        if (ollamaUrl == null) {
            log.warn("OLLAMA_URL is not set, so video summaries and the AI topic finder are off. See .env.example.");
            return null;
        }
        if (!ollamaUrl.startsWith("http://") && !ollamaUrl.startsWith("https://")) {
            throw new IllegalStateException("OLLAMA_URL must start with http:// or https://.");
        }
        log.info("Video summaries and the topic finder use {} at {}", model, ollamaUrl);
        return new OllamaClient(ollamaUrl, model);
    }

    /** Writes video summaries and quizzes in the background (see StudyGenerator). */
    private static StudyGenerator studyGenerator(Llm llm, String youtubeKey, HikariDataSource dataSource) {
        StudyGenerator.Descriptions descriptions = youtubeId -> Optional.empty();
        if (youtubeKey != null) {
            YouTubeClient youtube = new YouTubeClient(youtubeKey);
            descriptions = youtubeId -> {
                try {
                    return youtube.description(youtubeId);
                } catch (IOException e) {
                    // the summary can still be written from the title
                    log.warn("Couldn't get the description for video {}: {}", youtubeId, e.getMessage());
                    return Optional.empty();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            };
        }
        // Its own thread: the model takes much longer than a YouTube search, and shouldn't hold
        // up new topics' videos.
        ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "study-writer");
            thread.setDaemon(true);
            return thread;
        });
        return new StudyGenerator(new StudyRepository(dataSource), llm, descriptions, worker);
    }

    /** Strict JSON: bodies with fields we don't expect are rejected instead of silently ignored. */
    static ObjectMapper jsonMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * The settings and protections every route gets: JSON handling, the body size cap, security
     * headers, the CSRF check and safe error responses. The API tests use this same method, so
     * they test exactly what runs in production.
     */
    public static void configure(JavalinConfig config, boolean https, Set<String> allowedOrigins) {
        config.jsonMapper(new JavalinJackson(jsonMapper(), false));
        // Largest request body the server will accept. Every current endpoint takes a few
        // hundred bytes, so 16 KB rejects oversized junk early. Raise it only if a new
        // endpoint (e.g. file upload) genuinely needs more.
        config.http.maxRequestSize = 16_384L;
        config.startup.showJavalinBanner = false;
        // Don't advertise the server software and version to attackers.
        config.jetty.modifyHttpConfiguration(http -> http.setSendServerVersion(false));

        // Runs before every route, so new endpoints get these protections automatically.
        config.routes.before(ctx -> {
            setSecurityHeaders(ctx, https);
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
     * The frontend's {@code request()} helper in {@code api/client.ts} sets the header automatically.
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
            // Logged with the origin, which is either an attack or a misconfigured ALLOWED_ORIGINS
            // (e.g. opening the site as 127.0.0.1 instead of localhost). SecurityLog cleans the value.
            SecurityLog.failure("cross_site_write", ctx, null, "origin " + origin + " not in ALLOWED_ORIGINS");
            throw ApiException.forbidden("Cross-site request blocked.");
        }
    }

    /** Decodes the base64 pepper from .env and checks it's long enough (256 bits). */
    static byte[] decodePepper(String value) {
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

        /** A TCP port number, with a clear startup error instead of a bare NumberFormatException. */
        int port(String name, int fallback) {
            String value = get(name, null);
            if (value == null) {
                return fallback;
            }
            try {
                int port = Integer.parseInt(value.trim());
                if (port >= 1 && port <= 65535) {
                    return port;
                }
            } catch (NumberFormatException ignored) {
                // falls through to the clearer message below
            }
            throw new IllegalStateException(name + " must be a port number from 1 to 65535, but .env has \"" + value + "\".");
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
