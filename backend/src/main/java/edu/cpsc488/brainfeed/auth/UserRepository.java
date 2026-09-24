package edu.cpsc488.brainfeed.auth;

import edu.cpsc488.brainfeed.ApiException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Reads and writes the {@code users} table. All SQL uses {@code ?} placeholders; never build SQL
 * by concatenating strings, since that's how SQL injection happens.
 */
public class UserRepository {

    // Postgres error code for "duplicate value in a unique index".
    private static final String UNIQUE_VIOLATION = "23505";

    /**
     * A user plus their password hash. It's package-private, so only auth code can see password
     * hashes. Everything outside this package, including API responses, gets a plain {@link User}.
     */
    record Credentials(User user, String passwordHash) {
    }

    private final DataSource dataSource;

    public UserRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<User> findById(long id) {
        return findCredentials("SELECT * FROM users WHERE id = ?", ps -> ps.setLong(1, id))
                .map(Credentials::user);
    }

    /** {@code email} must already be lowercased, which is how emails are stored. */
    Optional<Credentials> findByEmail(String email) {
        return findCredentials("SELECT * FROM users WHERE email = ?", ps -> ps.setString(1, email));
    }

    /**
     * Inserts a new user. Callers must validate the inputs first; the database's CHECK constraints
     * will also reject bad data, but with an unfriendly 500 error.
     *
     * @throws ApiException 409 if the email or username is already taken
     */
    User create(String email, String username, String passwordHash) {
        String sql = """
                INSERT INTO users (email, username, password_hash)
                VALUES (?, ?, ?)
                RETURNING *
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            ps.setString(2, username);
            ps.setString(3, passwordHash);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return mapUser(rs);
            }
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                // Postgres names the violated index in the message. "users_username_key" is
                // defined in V1__users_and_sessions.sql; rename both together.
                String detail = String.valueOf(e.getMessage());
                if (detail.contains("users_username_key")) {
                    throw ApiException.conflict("That username is already taken.");
                }
                throw ApiException.conflict("An account with that email already exists.");
            }
            throw new RuntimeException(e);
        }
    }

    /** Fills in a statement's {@code ?} placeholders. Like a lambda, but allowed to throw SQLException. */
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private Optional<Credentials> findCredentials(String sql, Binder binder) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Credentials(mapUser(rs), rs.getString("password_hash")));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static User mapUser(ResultSet rs) throws SQLException {
        return new User(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("username"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
