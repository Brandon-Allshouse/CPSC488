package edu.cpsc488.brainfeed.admin;

import edu.cpsc488.brainfeed.ClientIp;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Database side of the admin page: user and video bans, the counts on the overview, and the
 * admin_actions log. Each ban or unban is written in one transaction together with its log entry,
 * so there's never a ban without a record of who did it.
 *
 * <p>Only AdminController should use this. It doesn't check who is asking.
 */
public class AdminRepository {

    /**
     * A user as admins see them. Unlike {@code User}, includes whether and why they're banned, and
     * the IP addresses they signed up and last logged in from (null for accounts from before IPs
     * were recorded).
     */
    public record AdminUser(long id, String username, String email, OffsetDateTime createdAt, boolean admin,
                            OffsetDateTime bannedAt, String banReason, String signupIp, String lastLoginIp,
                            OffsetDateTime lastLoginAt) {
    }

    public record AdminVideo(String youtubeId, String title, String channelTitle, String topic) {
    }

    /** bannedBy is the admin's username, or null if that account was deleted. */
    public record BannedVideo(String youtubeId, String title, String reason, String bannedBy, OffsetDateTime bannedAt) {
    }

    /** ip is where the admin did it from. */
    public record AdminAction(long id, String admin, String action, String target, String reason, String ip,
                              OffsetDateTime createdAt) {
    }

    // host() turns an INET into plain text like "192.0.2.1", without the "/32" Postgres would add.
    private static final String USER_COLUMNS = """
            id, username, email, created_at, is_admin, banned_at, ban_reason,
            host(signup_ip) AS signup_ip, host(last_login_ip) AS last_login_ip, last_login_at""";

    public record Stats(long users, long bannedUsers, long admins, long videos, long bannedVideos) {
    }

    private final DataSource dataSource;

    public AdminRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Stats stats() {
        String sql = """
                SELECT (SELECT count(*) FROM users) AS users,
                       (SELECT count(*) FROM users WHERE banned_at IS NOT NULL) AS banned_users,
                       (SELECT count(*) FROM users WHERE is_admin) AS admins,
                       (SELECT count(*) FROM videos) AS videos,
                       (SELECT count(*) FROM banned_videos) AS banned_videos
                """;
        return query(sql, ps -> { }, rs -> new Stats(rs.getLong("users"), rs.getLong("banned_users"),
                rs.getLong("admins"), rs.getLong("videos"), rs.getLong("banned_videos"))).getFirst();
    }

    /**
     * Newest users first, matching the username, the email, or either IP address, so admins can
     * find other accounts from the same place. {@code pattern} is a ready-made ILIKE pattern (see
     * AdminController.likePattern).
     */
    public List<AdminUser> searchUsers(String pattern, int limit) {
        String sql = "SELECT " + USER_COLUMNS + """

                FROM users
                WHERE username ILIKE ? ESCAPE '\\' OR email ILIKE ? ESCAPE '\\'
                   OR host(signup_ip) ILIKE ? ESCAPE '\\' OR host(last_login_ip) ILIKE ? ESCAPE '\\'
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """;
        return query(sql, ps -> {
            for (int i = 1; i <= 4; i++) {
                ps.setString(i, pattern);
            }
            ps.setInt(5, limit);
        }, AdminRepository::mapUser);
    }

    public Optional<AdminUser> findUser(long id) {
        String sql = "SELECT " + USER_COLUMNS + " FROM users WHERE id = ?";
        return query(sql, ps -> ps.setLong(1, id), AdminRepository::mapUser).stream().findFirst();
    }

    /**
     * Bans a user and logs them out everywhere by deleting their sessions. Returns false if the
     * user doesn't exist or is an admin (the database refuses to ban admins too).
     */
    public boolean banUser(long adminId, String adminIp, long userId, String reason) {
        return inTransaction(conn -> {
            int changed = update(conn, """
                    UPDATE users SET banned_at = now(), ban_reason = ?
                    WHERE id = ? AND NOT is_admin
                    """, ps -> {
                ps.setString(1, reason);
                ps.setLong(2, userId);
            });
            if (changed == 0) {
                return false;
            }
            update(conn, "DELETE FROM sessions WHERE user_id = ?", ps -> ps.setLong(1, userId));
            logUserAction(conn, adminId, adminIp, "ban_user", userId, reason);
            return true;
        });
    }

    /** Returns false if the user doesn't exist or wasn't banned. */
    public boolean unbanUser(long adminId, String adminIp, long userId) {
        return inTransaction(conn -> {
            int changed = update(conn, """
                    UPDATE users SET banned_at = NULL, ban_reason = NULL
                    WHERE id = ? AND banned_at IS NOT NULL
                    """, ps -> ps.setLong(1, userId));
            if (changed == 0) {
                return false;
            }
            logUserAction(conn, adminId, adminIp, "unban_user", userId, null);
            return true;
        });
    }

    /** Videos in the feed whose title, channel or id matches. */
    public List<AdminVideo> searchVideos(String pattern, int limit) {
        String sql = """
                SELECT v.youtube_id, v.title, v.channel_title, t.name AS topic
                FROM videos v JOIN topics t ON t.id = v.topic_id
                WHERE v.title ILIKE ? ESCAPE '\\' OR v.channel_title ILIKE ? ESCAPE '\\' OR v.youtube_id ILIKE ? ESCAPE '\\'
                ORDER BY v.fetched_at DESC, v.youtube_id
                LIMIT ?
                """;
        return query(sql, ps -> {
            ps.setString(1, pattern);
            ps.setString(2, pattern);
            ps.setString(3, pattern);
            ps.setInt(4, limit);
        }, rs -> new AdminVideo(rs.getString("youtube_id"), rs.getString("title"),
                rs.getString("channel_title"), rs.getString("topic")));
    }

    public List<BannedVideo> bannedVideos(int limit) {
        String sql = """
                SELECT b.youtube_id, b.title, b.reason, u.username AS banned_by, b.banned_at
                FROM banned_videos b LEFT JOIN users u ON u.id = b.banned_by
                ORDER BY b.banned_at DESC, b.youtube_id
                LIMIT ?
                """;
        return query(sql, ps -> ps.setInt(1, limit), rs -> new BannedVideo(rs.getString("youtube_id"),
                rs.getString("title"), rs.getString("reason"), rs.getString("banned_by"),
                rs.getObject("banned_at", OffsetDateTime.class)));
    }

    /**
     * Blacklists a video and removes it from the feed. Deleting the row also deletes everyone's
     * likes and dislikes of it. Works for videos we haven't fetched yet, too. Banning an
     * already-banned video just updates the reason.
     */
    public void banVideo(long adminId, String adminIp, String youtubeId, String reason) {
        inTransaction(conn -> {
            update(conn, """
                    INSERT INTO banned_videos (youtube_id, title, reason, banned_by)
                    VALUES (?, (SELECT title FROM videos WHERE youtube_id = ?), ?, ?)
                    ON CONFLICT (youtube_id) DO UPDATE SET reason = EXCLUDED.reason
                    """, ps -> {
                ps.setString(1, youtubeId);
                ps.setString(2, youtubeId);
                ps.setString(3, reason);
                ps.setLong(4, adminId);
            });
            update(conn, "DELETE FROM videos WHERE youtube_id = ?", ps -> ps.setString(1, youtubeId));
            logAction(conn, adminId, adminIp, "ban_video", youtubeId, reason);
            return true;
        });
    }

    /**
     * Takes a video off the blacklist. It isn't put back right away: it shows up again the next
     * time its topic is refreshed from YouTube. Returns false if it wasn't banned.
     */
    public boolean unbanVideo(long adminId, String adminIp, String youtubeId) {
        return inTransaction(conn -> {
            int changed = update(conn, "DELETE FROM banned_videos WHERE youtube_id = ?",
                    ps -> ps.setString(1, youtubeId));
            if (changed == 0) {
                return false;
            }
            logAction(conn, adminId, adminIp, "unban_video", youtubeId, null);
            return true;
        });
    }

    public List<AdminAction> recentActions(int limit) {
        String sql = """
                SELECT a.id, u.username AS admin, a.action, a.target, a.reason, host(a.ip) AS ip, a.created_at
                FROM admin_actions a LEFT JOIN users u ON u.id = a.admin_id
                ORDER BY a.created_at DESC, a.id DESC
                LIMIT ?
                """;
        return query(sql, ps -> ps.setInt(1, limit), rs -> new AdminAction(rs.getLong("id"),
                rs.getString("admin"), rs.getString("action"), rs.getString("target"),
                rs.getString("reason"), rs.getString("ip"), rs.getObject("created_at", OffsetDateTime.class)));
    }

    private static void logAction(Connection conn, long adminId, String adminIp, String action, String target,
                                  String reason) throws SQLException {
        update(conn, """
                INSERT INTO admin_actions (admin_id, ip, action, target, reason)
                VALUES (?, CAST(? AS inet), ?, ?, ?)
                """, ps -> {
            ps.setLong(1, adminId);
            ps.setString(2, ClientIp.normalize(adminIp));
            ps.setString(3, action);
            ps.setString(4, target);
            ps.setString(5, reason);
        });
    }

    /** Logs the user as "username (#id)", so the log stays readable even if they're renamed. */
    private static void logUserAction(Connection conn, long adminId, String adminIp, String action, long userId,
                                      String reason) throws SQLException {
        update(conn, """
                INSERT INTO admin_actions (admin_id, ip, action, target, reason)
                SELECT ?, CAST(? AS inet), ?, username || ' (#' || id || ')', ? FROM users WHERE id = ?
                """, ps -> {
            ps.setLong(1, adminId);
            ps.setString(2, ClientIp.normalize(adminIp));
            ps.setString(3, action);
            ps.setString(4, reason);
            ps.setLong(5, userId);
        });
    }

    private static AdminUser mapUser(ResultSet rs) throws SQLException {
        return new AdminUser(rs.getLong("id"), rs.getString("username"), rs.getString("email"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getBoolean("is_admin"),
                rs.getObject("banned_at", OffsetDateTime.class), rs.getString("ban_reason"),
                rs.getString("signup_ip"), rs.getString("last_login_ip"),
                rs.getObject("last_login_at", OffsetDateTime.class));
    }

    /** Fills in a statement's {@code ?} placeholders. Like a lambda, but allowed to throw SQLException. */
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private interface TransactionWork {
        boolean run(Connection conn) throws SQLException;
    }

    private <T> List<T> query(String sql, Binder binder, RowMapper<T> mapper) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            List<T> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static int update(Connection conn, String sql, Binder binder) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            return ps.executeUpdate();
        }
    }

    private boolean inTransaction(TransactionWork work) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                boolean result = work.run(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
