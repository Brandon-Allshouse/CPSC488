package edu.cpsc488.brainfeed.progress;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the leaderboard from {@code quiz_attempts} and {@code quiz_streaks} (V14).
 *
 * <p>Ranks use standard competition ranking ("1, 2, 2, 4"): people with the same points share a
 * rank and the next rank skips ahead, like sports tables. It's what SQL's {@code RANK()} does,
 * and nobody's position depends on something they can't see. Ties are listed by username.
 * Banned users are left out before ranking, so they don't leave gaps. Only people with points in
 * the period are ranked.
 */
public class LeaderboardRepository {

    /** How many rows the top list has. */
    public static final int TOP = 20;

    /**
     * One row of the board. Only the username, never the email or anything else about the account.
     *
     * @param rank    null when the user has no points in the period (so isn't ranked)
     * @param correct correct first answers in the period
     * @param streak  current streak in days, 0 if it ended before yesterday
     */
    public record Entry(Integer rank, String username, long points, long correct, int streak) {
    }

    // Everyone with points since ?, ranked. Parameters: since, today - 1 (for streaks).
    private static final String RANKED = """
            WITH scores AS (
                SELECT a.user_id, sum(a.points) AS points, count(*) FILTER (WHERE a.correct) AS correct
                FROM quiz_attempts a
                WHERE a.answered_at >= ?
                GROUP BY a.user_id
                HAVING sum(a.points) > 0
            )
            SELECT u.id, u.username, s.points, s.correct,
                   CASE WHEN st.last_day >= ? THEN st.current_days ELSE 0 END AS streak,
                   rank() OVER (ORDER BY s.points DESC) AS rank
            FROM scores s
            JOIN users u ON u.id = s.user_id
            LEFT JOIN quiz_streaks st ON st.user_id = s.user_id
            WHERE u.banned_at IS NULL
            """;

    private final DataSource dataSource;

    public LeaderboardRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Monday 00:00 UTC of the week {@code now} is in. "This week" counts points from then on. */
    public static Instant weekStart(Instant now) {
        return LocalDate.ofInstant(now, ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
    }

    /** The top {@link #TOP} by points earned since {@code since} (Instant.EPOCH for all time). */
    public List<Entry> top(Instant since, LocalDate today) {
        String sql = "SELECT username, points, correct, streak, rank FROM (" + RANKED + ") r"
                + " ORDER BY rank, lower(username) LIMIT " + TOP;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, since, today);
            List<Entry> entries = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new Entry(rs.getInt("rank"), rs.getString("username"), rs.getLong("points"),
                            rs.getLong("correct"), rs.getInt("streak")));
                }
            }
            return entries;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The user's own row, ranked among everyone (so it works outside the top 20). Empty only if the
     * user doesn't exist or is banned; someone with no points gets rank null and 0 points.
     */
    public Optional<Entry> forUser(long userId, Instant since, LocalDate today) {
        String sql = "SELECT username, points, correct, streak, rank FROM (" + RANKED + ") r WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, since, today);
            ps.setLong(3, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new Entry(rs.getInt("rank"), rs.getString("username"), rs.getLong("points"),
                            rs.getLong("correct"), rs.getInt("streak")));
                }
            }
            return unranked(conn, userId, since, today);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // Not on the board in this period: still show their correct answers (wrong-only weeks are 0)
    // and streak, so the card isn't blank.
    private static Optional<Entry> unranked(Connection conn, long userId, Instant since, LocalDate today)
            throws SQLException {
        String sql = """
                SELECT u.username,
                       (SELECT count(*) FROM quiz_attempts a
                        WHERE a.user_id = u.id AND a.correct AND a.answered_at >= ?) AS correct,
                       (SELECT CASE WHEN st.last_day >= ? THEN st.current_days ELSE 0 END
                        FROM quiz_streaks st WHERE st.user_id = u.id) AS streak
                FROM users u WHERE u.id = ? AND u.banned_at IS NULL
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, since, today);
            ps.setLong(3, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Entry(null, rs.getString("username"), 0, rs.getLong("correct"), rs.getInt("streak")));
            }
        }
    }

    private static void bind(PreparedStatement ps, Instant since, LocalDate today) throws SQLException {
        ps.setObject(1, since.atOffset(ZoneOffset.UTC));
        ps.setObject(2, today.minusDays(1));
    }
}
