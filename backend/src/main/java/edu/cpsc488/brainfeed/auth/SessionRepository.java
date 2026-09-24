package edu.cpsc488.brainfeed.auth;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Reads and writes the {@code sessions} table.
 *
 * <p>How login sessions work: on login we generate a random token and send it to the browser in
 * a cookie. The database stores only the SHA-256 hash of that token. On each request we hash the
 * cookie's token and look it up. So someone who steals a copy of the database gets hashes they
 * can't turn back into working cookies.
 *
 * <p>Plain SHA-256 is fine here, unlike for passwords. A token is 256 random bits, so there's
 * nothing to guess, and a slow hash would only slow down every request.
 */
public class SessionRepository {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DataSource dataSource;
    private final Duration lifetime;
    private final Duration idleTimeout;

    /**
     * @param lifetime    absolute limit: the user must log in again after this, no matter what
     * @param idleTimeout the session ends early if it goes unused this long (OWASP Session Management)
     */
    public SessionRepository(DataSource dataSource, Duration lifetime, Duration idleTimeout) {
        this.dataSource = dataSource;
        this.lifetime = lifetime;
        this.idleTimeout = idleTimeout;
    }

    public Duration lifetime() {
        return lifetime;
    }

    /** Creates a session for the user and returns the raw token to put in the cookie. */
    public String create(long userId) {
        // 32 bytes = 256 bits from a cryptographically secure generator. Base64url-encoded
        // without padding, that's always 43 characters (AuthController checks for that shape).
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        execute("INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)", ps -> {
            ps.setString(1, hash(token));
            ps.setLong(2, userId);
            ps.setObject(3, OffsetDateTime.now().plus(lifetime));
        });
        return token;
    }

    /**
     * Returns the user id for a valid session token, and marks the session as just used.
     * Sessions past their absolute expiry or idle timeout are treated as missing.
     *
     * <p>This is an UPDATE rather than a SELECT so the validity check and the idle-timer refresh
     * happen in one database round trip. It means every logged-in request writes one row.
     */
    public Optional<Long> findUserId(String token) {
        String sql = """
                UPDATE sessions SET last_seen_at = now()
                WHERE token_hash = ?
                  AND expires_at > now()
                  AND last_seen_at > now() - make_interval(secs => ?)
                RETURNING user_id
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash(token));
            ps.setLong(2, idleTimeout.toSeconds());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong("user_id")) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void delete(String token) {
        execute("DELETE FROM sessions WHERE token_hash = ?", ps -> ps.setString(1, hash(token)));
    }

    /**
     * Housekeeping: removes dead sessions so the table doesn't grow forever. Expired sessions are
     * already ignored by findUserId, so this is about table size, not security. Called on each login.
     */
    public void deleteExpired() {
        execute("DELETE FROM sessions WHERE expires_at <= now() OR last_seen_at <= now() - make_interval(secs => ?)",
                ps -> ps.setLong(1, idleTimeout.toSeconds()));
    }

    /** Fills in a statement's {@code ?} placeholders. Like a lambda, but allowed to throw SQLException. */
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private void execute(String sql, Binder binder) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
