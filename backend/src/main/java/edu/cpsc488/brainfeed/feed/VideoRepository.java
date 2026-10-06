package edu.cpsc488.brainfeed.feed;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Reads and writes the {@code videos} table. */
public class VideoRepository {

    private final DataSource dataSource;

    public VideoRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Saves videos from a YouTube search. Videos we already have are left as they are. */
    void saveAll(List<Video> videos) {
        String sql = """
                INSERT INTO videos (youtube_id, topic_id, title, channel_title, published_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (youtube_id) DO NOTHING
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Video v : videos) {
                ps.setString(1, v.youtubeId());
                ps.setInt(2, v.topicId());
                ps.setString(3, v.title());
                ps.setString(4, v.channelTitle());
                ps.setObject(5, v.publishedAt());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * One page of the feed for the given topics, in a shuffled order. The same {@code seed} always
     * gives the same order, so the frontend keeps its seed while scrolling and pages never repeat
     * or skip videos. A new seed (e.g. on page reload) gives a new order.
     *
     * <p>A subject's id also brings in its sub-subjects' videos, so picking Math includes Algebra 2.
     */
    public List<Video> page(Collection<Integer> topicIds, long seed, int offset, int limit) {
        String sql = """
                SELECT youtube_id, title, channel_title, topic_id, published_at FROM videos
                WHERE topic_id IN (SELECT id FROM topics WHERE id = ANY (?) OR parent_id = ANY (?))
                ORDER BY md5(youtube_id || ?), youtube_id
                LIMIT ? OFFSET ?
                """;
        List<Video> videos = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Array ids = conn.createArrayOf("integer", topicIds.toArray());
            ps.setArray(1, ids);
            ps.setArray(2, ids);
            ps.setString(3, Long.toString(seed));
            ps.setInt(4, limit);
            ps.setInt(5, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    videos.add(new Video(
                            rs.getString("youtube_id"),
                            rs.getString("title"),
                            rs.getString("channel_title"),
                            rs.getInt("topic_id"),
                            rs.getObject("published_at", OffsetDateTime.class)));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return videos;
    }
}
