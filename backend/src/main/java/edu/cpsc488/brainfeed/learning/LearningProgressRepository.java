package edu.cpsc488.brainfeed.learning;

import edu.cpsc488.brainfeed.db.Database;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Owner-scoped, parameterized PostgreSQL operations. Never accepts an owner from JSON. */
public class LearningProgressRepository {
    private final DataSource dataSource;

    public LearningProgressRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** All notebook entries participate, including those never explicitly assigned a state. */
    public List<LearningProgress> list(long userId) {
        String sql = """
                SELECT v.youtube_id, v.title, v.channel_title,
                       COALESCE(p.status, 'planned') AS status,
                       p.completed_at, p.reviewed_at, COALESCE(p.updated_at, s.saved_at) AS updated_at
                FROM saved_videos s JOIN videos v ON v.youtube_id = s.youtube_id
                LEFT JOIN learning_progress p ON p.user_id = s.user_id AND p.youtube_id = s.youtube_id
                WHERE s.user_id = ? ORDER BY s.saved_at DESC, s.youtube_id
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            List<LearningProgress> items = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    items.add(read(result));
                }
            }
            return items;
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    /** Repeated completion keeps its original date; reopening clears completion/review dates. */
    public boolean saveStatus(long userId, String youtubeId, String status) {
        String sql = """
                INSERT INTO learning_progress (user_id, youtube_id, status, completed_at, updated_at)
                VALUES (?, ?, ?, CASE WHEN ? = 'completed' THEN clock_timestamp() END, clock_timestamp())
                ON CONFLICT (user_id, youtube_id) DO UPDATE SET
                  status = EXCLUDED.status,
                  completed_at = CASE WHEN EXCLUDED.status = 'completed'
                    THEN COALESCE(learning_progress.completed_at, EXCLUDED.completed_at) END,
                  reviewed_at = CASE WHEN EXCLUDED.status = 'completed'
                    THEN learning_progress.reviewed_at END,
                  updated_at = CASE WHEN learning_progress.status = EXCLUDED.status
                    THEN learning_progress.updated_at ELSE clock_timestamp() END
                """;
        return Database.inTransaction(dataSource, connection -> {
            // The parent lock serializes edits and removal of this notebook entry. Its FK also
            // prevents orphan progress if a user or a blacklisted video is deleted concurrently.
            if (!lockNotebookEntry(connection, userId, youtubeId)) {
                return false;
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, userId);
                statement.setString(2, youtubeId);
                statement.setString(3, status);
                statement.setString(4, status);
                return statement.executeUpdate() > 0;
            }
        });
    }

    /** Reviews only completed entries; viewing or playing a video does not imply completion. */
    public boolean markReviewed(long userId, String youtubeId) {
        return Database.inTransaction(dataSource, connection -> {
            if (!lockNotebookEntry(connection, userId, youtubeId)) {
                return false;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE learning_progress SET reviewed_at = clock_timestamp(), updated_at = clock_timestamp()
                    WHERE user_id = ? AND youtube_id = ? AND status = 'completed'
                    """)) {
                statement.setLong(1, userId);
                statement.setString(2, youtubeId);
                return statement.executeUpdate() > 0;
            }
        });
    }

    private static boolean lockNotebookEntry(Connection connection, long userId, String youtubeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM saved_videos WHERE user_id = ? AND youtube_id = ? FOR UPDATE")) {
            statement.setLong(1, userId);
            statement.setString(2, youtubeId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static LearningProgress read(ResultSet result) throws SQLException {
        return new LearningProgress(result.getString("youtube_id"), result.getString("title"),
                result.getString("channel_title"), result.getString("status"),
                result.getObject("completed_at", OffsetDateTime.class),
                result.getObject("reviewed_at", OffsetDateTime.class),
                result.getObject("updated_at", OffsetDateTime.class));
    }
}
