package edu.cpsc488.brainfeed.saved;

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

    /**
     * Saves a video for a user.
     *
     * Returns false if the video does not exist in the videos table.
     * Saving something that is already saved is harmless.
     */
    public boolean save(long userId, String youtubeId) {
        String checkSql = """
                SELECT 1
                FROM videos
                WHERE youtube_id = ?
                """;

        String insertSql = """
                INSERT INTO saved_videos (user_id, youtube_id)
                VALUES (?, ?)
                ON CONFLICT (user_id, youtube_id) DO NOTHING
                """;

        try (Connection conn = dataSource.getConnection()) {

            try (PreparedStatement check = conn.prepareStatement(checkSql)) {
                check.setString(1, youtubeId);

                try (ResultSet rs = check.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                }
            }

            try (PreparedStatement insert = conn.prepareStatement(insertSql)) {
                insert.setLong(1, userId);
                insert.setString(2, youtubeId);
                insert.executeUpdate();
            }

            return true;

        } catch (SQLException e) {
            throw new RuntimeException(e);
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
