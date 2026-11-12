package edu.cpsc488.brainfeed.progress;

import edu.cpsc488.brainfeed.db.Database;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Records quiz answers and daily streaks in the {@code quiz_attempts} and {@code quiz_streaks}
 * tables (V14). Only a user's first answer to each question is stored and scored.
 */
public class QuizAttemptRepository {

    /** Points for a correct first answer. Copied into each row, so changing it never rewrites old scores. */
    public static final int POINTS_PER_CORRECT_ANSWER = 10;

    /**
     * What happened to an answer.
     *
     * @param counted       true if this was the user's first answer to the question (so it was saved)
     * @param pointsEarned  {@link #POINTS_PER_CORRECT_ANSWER} for a correct first answer, otherwise 0
     * @param streak        the user's current streak in days after this answer (0 if it has ended)
     */
    public record Recorded(boolean counted, int pointsEarned, int streak) {
    }

    private final DataSource dataSource;
    private final Clock clock;

    public QuizAttemptRepository(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    /** The clock decides "today" for streaks and the answer time, so tests can move days. */
    public QuizAttemptRepository(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    /**
     * Saves the user's answer if it's their first for this question, and updates their streak when
     * it's also correct. Returns empty if the video's quiz is gone (banned while they were
     * answering), so nothing could be saved.
     *
     * <p>Runs in one transaction that first locks the user's row with {@code FOR NO KEY UPDATE}, so
     * one user's answers take turns: a double click or two tabs can't both be "first", and the
     * streak is updated from a consistent state. The unique constraint backs that up.
     */
    public Optional<Recorded> record(long userId, String youtubeId, int questionIndex, int choice, boolean correct) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        int points = correct ? POINTS_PER_CORRECT_ANSWER : 0;
        return Database.inTransaction(dataSource, conn -> {
            lockUser(conn, userId);
            boolean inserted = insert(conn, userId, youtubeId, questionIndex, choice, correct, points, now);
            if (!inserted) {
                if (!alreadyAnswered(conn, userId, youtubeId, questionIndex)) {
                    // Nothing clashed and nothing was inserted: the quiz row doesn't exist anymore.
                    return Optional.empty();
                }
                return Optional.of(new Recorded(false, 0, streak(conn, userId, today)));
            }
            int streak = correct ? extendStreak(conn, userId, today) : streak(conn, userId, today);
            return Optional.of(new Recorded(true, points, streak));
        });
    }

    private static void lockUser(Connection conn, long userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM users WHERE id = ? FOR NO KEY UPDATE")) {
            ps.setLong(1, userId);
            ps.executeQuery().close();
        }
    }

    // INSERT ... SELECT from video_study, so an answer for a quiz that was just deleted inserts nothing
    // instead of failing on the foreign key.
    private static boolean insert(Connection conn, long userId, String youtubeId, int questionIndex, int choice,
                                  boolean correct, int points, Instant now) throws SQLException {
        String sql = """
                INSERT INTO quiz_attempts (user_id, youtube_id, question_index, choice, correct, points, answered_at)
                SELECT ?, youtube_id, ?, ?, ?, ?, ? FROM video_study WHERE youtube_id = ?
                ON CONFLICT ON CONSTRAINT quiz_attempts_one_per_question DO NOTHING
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setInt(2, questionIndex);
            ps.setInt(3, choice);
            ps.setBoolean(4, correct);
            ps.setInt(5, points);
            ps.setObject(6, now.atOffset(ZoneOffset.UTC));
            ps.setString(7, youtubeId);
            return ps.executeUpdate() == 1;
        }
    }

    private static boolean alreadyAnswered(Connection conn, long userId, String youtubeId, int questionIndex)
            throws SQLException {
        String sql = "SELECT 1 FROM quiz_attempts WHERE user_id = ? AND youtube_id = ? AND question_index = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, youtubeId);
            ps.setInt(3, questionIndex);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Counts today toward the streak: same day keeps it, the day after the last one adds a day, and
     * a gap starts over at 1. If the clock ever goes backwards the streak is left alone.
     */
    private static int extendStreak(Connection conn, long userId, LocalDate today) throws SQLException {
        String sql = """
                INSERT INTO quiz_streaks (user_id, current_days, last_day) VALUES (?, 1, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    current_days = CASE
                        WHEN quiz_streaks.last_day >= EXCLUDED.last_day THEN quiz_streaks.current_days
                        WHEN quiz_streaks.last_day = EXCLUDED.last_day - 1 THEN quiz_streaks.current_days + 1
                        ELSE 1 END,
                    last_day = GREATEST(quiz_streaks.last_day, EXCLUDED.last_day)
                RETURNING current_days
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setObject(2, today);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static int streak(Connection conn, long userId, LocalDate today) throws SQLException {
        String sql = "SELECT current_days FROM quiz_streaks WHERE user_id = ? AND last_day >= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setObject(2, today.minusDays(1));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
