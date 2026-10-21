package edu.cpsc488.brainfeed.history;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Reads and writes the {@code watch_history} table. Every query is limited to one user's rows. */
public class WatchHistoryRepository {

    /** One watched video, as shown on the Watch history page. */
    public record WatchedVideo(String youtubeId, String title, String creator, OffsetDateTime watchedAt) {
    }

    private final DataSource dataSource;
    private final int limit;

    /** @param limit how many videos each user's history keeps; older ones are deleted */
    public WatchHistoryRepository(DataSource dataSource, int limit) {
        this.dataSource = dataSource;
        this.limit = limit;
    }

    /**
     * Records that the user started watching a video, or moves it to the top if it's already there,
     * then deletes anything past the newest {@code limit}. Returns false if the video isn't in our
     * database (so only real feed videos can be recorded).
     */
    public boolean record(long userId, String youtubeId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int added;
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO watch_history (user_id, youtube_id, watched_at)
                        SELECT ?, youtube_id, now() FROM videos WHERE youtube_id = ?
                        ON CONFLICT (user_id, youtube_id) DO UPDATE SET watched_at = EXCLUDED.watched_at
                        """)) {
                    ps.setLong(1, userId);
                    ps.setString(2, youtubeId);
                    added = ps.executeUpdate();
                }
                if (added > 0) {
                    try (PreparedStatement ps = conn.prepareStatement("""
                            DELETE FROM watch_history
                            WHERE user_id = ? AND youtube_id NOT IN (
                                SELECT youtube_id FROM watch_history
                                WHERE user_id = ?
                                ORDER BY watched_at DESC, youtube_id
                                LIMIT ?)
                            """)) {
                        ps.setLong(1, userId);
                        ps.setLong(2, userId);
                        ps.setInt(3, limit);
                        ps.executeUpdate();
                    }
                }
                conn.commit();
                return added > 0;
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

    /** The user's history, most recently watched first. */
    public List<WatchedVideo> list(long userId) {
        String sql = """
                SELECT v.youtube_id, v.title, v.channel_title, h.watched_at
                FROM watch_history h
                JOIN videos v ON v.youtube_id = h.youtube_id
                WHERE h.user_id = ?
                ORDER BY h.watched_at DESC, h.youtube_id
                LIMIT ?
                """;
        List<WatchedVideo> videos = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    videos.add(new WatchedVideo(
                            rs.getString("youtube_id"),
                            rs.getString("title"),
                            rs.getString("channel_title"),
                            rs.getObject("watched_at", OffsetDateTime.class)));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return videos;
    }

    /** Removes one video from the user's history. Returns false if it wasn't there. */
    public boolean remove(long userId, String youtubeId) {
        return update("DELETE FROM watch_history WHERE user_id = ? AND youtube_id = ?", userId, youtubeId) > 0;
    }

    /** Deletes the user's whole history. */
    public void clear(long userId) {
        update("DELETE FROM watch_history WHERE user_id = ?", userId, null);
    }

    private int update(String sql, long userId, String youtubeId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            if (youtubeId != null) {
                ps.setString(2, youtubeId);
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
