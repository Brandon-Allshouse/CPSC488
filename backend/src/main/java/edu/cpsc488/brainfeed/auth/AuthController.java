package edu.cpsc488.brainfeed.auth;

import edu.cpsc488.brainfeed.ApiException;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Account and login endpoints: register, login, logout, and "who am I" ({@code /api/auth/me}).
 * The request/response shapes are listed in the README's "Auth API" table.
 *
 * <p>Other controllers should call {@link #currentUser(Context)} to find the logged-in user, and
 * not read the session cookie themselves.
 *
 * <p>Security-sensitive: read SECURITY.md before changing anything in this package.
 */
public class AuthController {

    // Input rules. These are mirrored by CHECK constraints in the database (V2 migration)
    // and by frontend/src/validation.ts; the backend is the source of truth.
    private static final int MAX_EMAIL = 254;
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,30}$");
    // Shape of a token from SessionRepository.create: 43 base64url characters.
    private static final Pattern SESSION_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    // Deliberately doesn't say which part was wrong, so it can't be used to test whether an email
    // has an account.
    private static final String BAD_CREDENTIALS = "Incorrect email or password.";

    // JSON request bodies. Jackson rejects bodies with any other fields (see App.java).
    record LoginRequest(String email, String password) {
    }

    record RegisterRequest(String email, String username, String password) {
    }

    private final UserRepository users;
    private final SessionRepository sessions;
    private final PasswordHasher hasher;
    private final PasswordPolicy policy;
    private final boolean secureCookies;
    // With HTTPS, the __Host- prefix makes the browser refuse the cookie unless it's Secure,
    // host-only and Path=/, so a subdomain can't overwrite it (OWASP Session Management).
    private final String cookieName;
    // Verified when the email doesn't exist, so a failed login takes the same time whether or not
    // the account exists (prevents account enumeration by timing).
    private final String dummyHash;

    // NIST SP 800-63B §3.2.2 caps consecutive failures at 100; we're stricter:
    // 10 failed logins per 15 minutes, tracked separately per account and per IP.
    private final LoginRateLimiter loginFailures = new LoginRateLimiter(10, Duration.ofMinutes(15));
    // 20 signup attempts per hour per IP.
    private final LoginRateLimiter registrations = new LoginRateLimiter(20, Duration.ofHours(1));

    public AuthController(UserRepository users, SessionRepository sessions, PasswordHasher hasher,
                          PasswordPolicy policy, boolean secureCookies) {
        this.users = users;
        this.sessions = sessions;
        this.hasher = hasher;
        this.policy = policy;
        this.secureCookies = secureCookies;
        this.cookieName = secureCookies ? "__Host-brainfeed_session" : "brainfeed_session";
        this.dummyHash = hasher.hash("dummy password used only for timing equalization");
    }

    public void register(Javalin app) {
        app.post("/api/auth/register", this::handleRegister);
        app.post("/api/auth/login", this::handleLogin);
        app.post("/api/auth/logout", this::handleLogout);
        app.get("/api/auth/me", this::handleMe);
    }

    private void handleRegister(Context ctx) {
        String ipKey = "ip:" + ctx.ip();
        if (registrations.isLimited(ipKey)) {
            SecurityLog.failure("register", ctx, null, "rate limited");
            throw ApiException.tooManyRequests("Too many sign-up attempts. Please try again later.");
        }
        // Counted before validation on purpose: invalid attempts still cost us work (breach check,
        // hashing) and can still be used to probe which emails are taken.
        registrations.record(ipKey);

        RegisterRequest req = parse(ctx, RegisterRequest.class);
        String email = normalizeEmail(req.email());
        String username = req.username() == null ? "" : req.username().trim();
        // Passwords are never trimmed or truncated (NIST SP 800-63B).
        String password = req.password() == null ? "" : req.password();

        if (email.length() > MAX_EMAIL || !EMAIL.matcher(email).matches()) {
            throw ApiException.badRequest("Please enter a valid email address.");
        }
        if (!USERNAME.matcher(username).matches()) {
            throw ApiException.badRequest("Username must be 3-30 characters: letters, numbers, or underscores.");
        }
        Optional<String> passwordProblem = policy.check(password, username, email);
        if (passwordProblem.isPresent()) {
            SecurityLog.failure("register", ctx, null, "password rejected by policy");
            throw ApiException.badRequest(passwordProblem.get());
        }

        User user = users.create(email, username, hasher.hash(password));
        SecurityLog.success("register", ctx, user.id());
        startSession(ctx, user);
        ctx.status(201).json(Map.of("user", user));
    }

    private void handleLogin(Context ctx) {
        LoginRequest req = parse(ctx, LoginRequest.class);
        String email = normalizeEmail(req.email());
        String password = req.password() == null ? "" : req.password();

        if (email.isEmpty() || password.isEmpty()) {
            throw ApiException.badRequest("Email and password are required.");
        }

        String ipKey = "ip:" + ctx.ip();
        String emailKey = "email:" + email;
        if (loginFailures.isLimited(ipKey) || loginFailures.isLimited(emailKey)) {
            SecurityLog.failure("login", ctx, null, "rate limited");
            throw ApiException.tooManyRequests(
                    "Too many failed login attempts. Please try again in "
                            + loginFailures.window().toMinutes() + " minutes.");
        }

        // Oversized input can't match a valid account; reject it without spending time hashing.
        if (email.length() > MAX_EMAIL || PasswordPolicy.length(password) > PasswordPolicy.MAX_LENGTH) {
            loginFailures.record(ipKey);
            SecurityLog.failure("login", ctx, null, "oversized input");
            throw ApiException.unauthorized(BAD_CREDENTIALS);
        }

        Optional<UserRepository.Credentials> found = users.findByEmail(email);
        String hash = found.map(UserRepository.Credentials::passwordHash).orElse(dummyHash);
        boolean passwordOk = hasher.verify(password, hash);

        if (found.isEmpty() || !passwordOk) {
            loginFailures.record(ipKey);
            loginFailures.record(emailKey);
            SecurityLog.failure("login", ctx, found.map(c -> c.user().id()).orElse(null),
                    found.isEmpty() ? "unknown account" : "wrong password");
            // Same message either way, so attackers can't tell which emails have accounts.
            throw ApiException.unauthorized(BAD_CREDENTIALS);
        }

        // A correct password clears that account's failures. The IP's count isn't cleared, so an
        // attacker can't reset their limit by logging into their own account between guesses.
        loginFailures.reset(emailKey);
        sessions.deleteExpired();

        // Always issue a brand-new session on login (prevents session fixation) and drop any
        // session the browser already had.
        String oldToken = ctx.cookie(cookieName);
        if (oldToken != null) {
            sessions.delete(oldToken);
        }

        User user = found.get().user();
        SecurityLog.success("login", ctx, user.id());
        startSession(ctx, user);
        ctx.json(Map.of("user", user));
    }

    private void handleLogout(Context ctx) {
        Optional<User> user = currentUser(ctx);
        String token = ctx.cookie(cookieName);
        if (token != null) {
            sessions.delete(token);
        }
        user.ifPresent(u -> SecurityLog.success("logout", ctx, u.id()));
        setSessionCookie(ctx, "", 0);
        ctx.status(204);
    }

    private void handleMe(Context ctx) {
        User user = currentUser(ctx)
                .orElseThrow(() -> ApiException.unauthorized("Not logged in."));
        ctx.json(Map.of("user", user));
    }

    /** Looks up the logged-in user from the session cookie. Other controllers can reuse this. */
    public Optional<User> currentUser(Context ctx) {
        String token = ctx.cookie(cookieName);
        // Our tokens are always 43 base64url characters; reject anything else without a DB lookup.
        if (token == null || !SESSION_TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        return sessions.findUserId(token).flatMap(users::findById);
    }

    private void startSession(Context ctx, User user) {
        String token = sessions.create(user.id());
        setSessionCookie(ctx, token, sessions.lifetime().toSeconds());
    }

    /**
     * Writes the Set-Cookie header by hand so every security attribute is explicit:
     * <ul>
     *   <li>HttpOnly: JavaScript can't read the cookie, so an XSS bug can't steal the session.</li>
     *   <li>SameSite=Strict: the browser won't send it on requests started by other websites.</li>
     *   <li>Secure (production only): only sent over HTTPS. Local dev is plain HTTP, where a
     *       Secure cookie would never be sent back.</li>
     * </ul>
     * A Max-Age of 0 deletes the cookie (used by logout).
     */
    private void setSessionCookie(Context ctx, String value, long maxAgeSeconds) {
        StringBuilder cookie = new StringBuilder()
                .append(cookieName).append('=').append(value)
                .append("; Path=/")
                .append("; Max-Age=").append(maxAgeSeconds)
                .append("; HttpOnly")
                .append("; SameSite=Strict");
        if (secureCookies) {
            cookie.append("; Secure");
        }
        ctx.header("Set-Cookie", cookie.toString());
    }

    // Emails are compared and stored lowercase so "Alice@X.com" and "alice@x.com" are one account.
    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Parses the JSON body, turning any parse problem into a 400 instead of a 500. */
    private static <T> T parse(Context ctx, Class<T> type) {
        try {
            T body = ctx.bodyAsClass(type);
            if (body == null) {
                throw ApiException.badRequest("Request body is required.");
            }
            return body;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Malformed request body.");
        }
    }
}
