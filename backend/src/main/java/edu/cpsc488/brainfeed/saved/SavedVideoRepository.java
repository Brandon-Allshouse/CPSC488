package edu.cpsc488.brainfeed.saved;

import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.db.Database;
import edu.cpsc488.brainfeed.feed.Video;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the saved_videos table.
 */
public class SavedVideoRepository {

    private final DataSource dataSource;

    public SavedVideoRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Most videos one user can keep in the notebook, so one account can't fill the database. */
    static final int MAX_SAVED_VIDEOS = 500;

    /**
     * Saves a video for a user. Saving something that is already saved is harmless.
     *
     * Returns false if the video does not exist in the videos table.
     *
     * @throws ApiException 409 if the user already has {@link #MAX_SAVED_VIDEOS} saved (enforced even
     *                      when several saves arrive at once)
     */
    public boolean save(long userId, String youtubeId) {
        String sql = """
                INSERT INTO saved_videos (user_id, youtube_id)
                SELECT ?, youtube_id FROM videos
                WHERE youtube_id = ?
                  AND (SELECT count(*) FROM saved_videos WHERE user_id = ?) < ?
                ON CONFLICT (user_id, youtube_id) DO NOTHING
                """;

        // Locking the user's row first makes two saves at once take turns, so they can't both see
        // 499 and both insert (see Database.inTransaction).
        return Database.inTransaction(dataSource, conn -> {
            lockUser(conn, userId);
            try (PreparedStatement insert = conn.prepareStatement(sql)) {
                insert.setLong(1, userId);
                insert.setString(2, youtubeId);
                insert.setLong(3, userId);
                insert.setInt(4, MAX_SAVED_VIDEOS);
                if (insert.executeUpdate() > 0) {
                    return true;
                }
            }

            // Nothing inserted: it was already saved, the video doesn't exist, or the list is full.
            if (isSaved(conn, userId, youtubeId)) {
                return true;
            }
            if (!videoExists(conn, youtubeId)) {
                return false;
            }
            throw ApiException.conflict("Your Study notebook can hold at most " + MAX_SAVED_VIDEOS + " videos.");
        });
    }

    private static void lockUser(Connection conn, long userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM users WHERE id = ? FOR NO KEY UPDATE")) {
            ps.setLong(1, userId);
            ps.executeQuery().close();
        }
    }

    private static boolean isSaved(Connection conn, long userId, String youtubeId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM saved_videos WHERE user_id = ? AND youtube_id = ?")) {
            ps.setLong(1, userId);
            ps.setString(2, youtubeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean videoExists(Connection conn, String youtubeId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM videos WHERE youtube_id = ?")) {
            ps.setString(1, youtubeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Removes a video from the user's saved list.
     */
    public void remove(long userId, String youtubeId) {
        String sql = """
                DELETE FROM saved_videos
                WHERE user_id = ? AND youtube_id = ?
                """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, userId);
            ps.setString(2, youtubeId);
            ps.executeUpdate();

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns all videos saved by a user, newest first.
     */
    public List<Video> findByUser(long userId) {
        String sql = """
                SELECT
                    v.youtube_id,
                    v.title,
                    v.channel_title,
                    v.topic_id,
                    v.published_at
                FROM saved_videos sv
                JOIN videos v ON v.youtube_id = sv.youtube_id
                WHERE sv.user_id = ?
                ORDER BY sv.saved_at DESC
                """;

        List<Video> videos = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, userId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    videos.add(new Video(
                            rs.getString("youtube_id"),
                            rs.getString("title"),
                            rs.getString("channel_title"),
                            rs.getInt("topic_id"),
                            rs.getObject("published_at", OffsetDateTime.class)
                    ));
                }
            }

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return videos;
    }
}
