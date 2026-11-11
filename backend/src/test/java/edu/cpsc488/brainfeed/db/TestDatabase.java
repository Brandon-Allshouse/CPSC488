package edu.cpsc488.brainfeed.db;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Assumptions;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A real Postgres database for tests of our SQL (the repository classes). Mocks can't tell us
 * whether a query only returns your own rows or a limit holds under parallel requests, so these
 * run against Postgres with every migration applied.
 *
 * <p>It always uses a separate database called {@code brainfeed_test}, dropped and recreated
 * once per test run, so it can never touch the real {@code brainfeed} database. Settings come
 * from {@code TEST_DB_URL} (server only, like {@code jdbc:postgresql://db:5432/}),
 * {@code TEST_DB_USER} and {@code TEST_DB_PASSWORD}; docker-compose.yml and CI set them. Without
 * them (e.g. running one test from an IDE) these tests are skipped, unless
 * {@code REQUIRE_DB_TESTS=true}, which CI and Docker set so they can't be skipped by accident.
 */
public final class TestDatabase {

    private static final String NAME = "brainfeed_test";
    // A made-up hash in the right format, so the users table's CHECK accepts it.
    public static final String FAKE_HASH = "$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHRzb21lc2FsdA$"
            + "aGFzaGhhc2hoYXNoaGFzaGhhc2hoYXNoaGFzaGhhc2g";

    private static HikariDataSource dataSource;

    private TestDatabase() {
    }

    /** The test database, created and migrated the first time. Skips the test if none is set up. */
    public static synchronized DataSource get() {
        if (dataSource != null) {
            return dataSource;
        }
        String server = System.getenv("TEST_DB_URL");
        if (server == null || server.isBlank()) {
            if ("true".equals(System.getenv("REQUIRE_DB_TESTS"))) {
                throw new IllegalStateException("REQUIRE_DB_TESTS is true but TEST_DB_URL isn't set.");
            }
            Assumptions.abort("TEST_DB_URL isn't set, so database tests are skipped (see TestDatabase).");
        }
        String base = server.endsWith("/") ? server : server + "/";
        String user = System.getenv("TEST_DB_USER");
        String password = System.getenv("TEST_DB_PASSWORD");
        try (Connection conn = DriverManager.getConnection(base + "postgres", user, password);
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + NAME + " WITH (FORCE)");
            st.execute("CREATE DATABASE " + NAME);
        } catch (SQLException e) {
            throw new IllegalStateException("Couldn't create the test database at " + base, e);
        }
        dataSource = Database.connect(base + NAME, user, password);
        Database.migrate(dataSource);
        return dataSource;
    }

    /**
     * Empties everything tests write, keeping the seeded topics from the migrations. Uses DELETE
     * rather than TRUNCATE ... CASCADE, which would also empty topics (it references users).
     */
    public static void reset() {
        exec("DELETE FROM topics WHERE source = 'ai'");
        exec("DELETE FROM admin_actions");
        exec("DELETE FROM banned_topic_names");
        exec("DELETE FROM banned_videos");
        exec("DELETE FROM users");
        exec("DELETE FROM videos");
        exec("UPDATE topics SET videos_fetched_at = NULL");
    }

    /** Adds a user named {@code username} with email {@code username@sru.edu}. Returns their id. */
    public static long addUser(String username) {
        return number("INSERT INTO users (email, username, password_hash) VALUES (?, ?, ?) RETURNING id",
                username.toLowerCase() + "@sru.edu", username, FAKE_HASH);
    }

    public static void makeAdmin(long userId) {
        exec("UPDATE users SET is_admin = true WHERE id = ?", userId);
    }

    /** Adds a video to the feed table under the given topic, titled "Video &lt;id&gt;". */
    public static void addVideo(String youtubeId, int topicId) {
        exec("INSERT INTO videos (youtube_id, topic_id, title, channel_title, published_at) VALUES (?, ?, ?, ?, now())",
                youtubeId, topicId, "Video " + youtubeId, "Test Channel");
    }

    /** A seeded topic's id by name, like "Math" or "Algebra 2". */
    public static int topicId(String name) {
        return (int) number("SELECT id FROM topics WHERE name = ?", name);
    }

    /** Runs a statement with ? placeholders. */
    public static void exec(String sql, Object... params) {
        try (Connection conn = get().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            ps.execute();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** The first column of the first row, as a number (counts, ids). */
    public static long number(String sql, Object... params) {
        try (Connection conn = get().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("No rows for: " + sql);
                }
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** The first column of the first row as text, or null if there are no rows. */
    public static String text(String sql, Object... params) {
        try (Connection conn = get().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}
