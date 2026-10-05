package edu.cpsc488.brainfeed.feed;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Reads and writes the {@code topics} and {@code user_interests} tables. */
public class TopicRepository {

    /** A topic plus the query used to search YouTube for it. Only used by FeedRefresher. */
    record SearchTopic(int id, String searchQuery) {
    }

    private final DataSource dataSource;

    public TopicRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public List<Topic> listAll() {
        List<Topic> topics = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM topics ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                topics.add(new Topic(rs.getInt("id"), rs.getString("name")));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return topics;
    }

    /** The topic ids the user picked, or an empty list if they haven't picked any yet. */
    public List<Integer> findInterests(long userId) {
        List<Integer> ids = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT topic_id FROM user_interests WHERE user_id = ? ORDER BY topic_id")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getInt("topic_id"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return ids;
    }

    /**
     * Replaces all of the user's interests with {@code topicIds}, in one transaction, so a failure
     * halfway through can't leave them with no interests. Callers must check the ids exist first.
     */
    public void replaceInterests(long userId, Collection<Integer> topicIds) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement delete = conn.prepareStatement("DELETE FROM user_interests WHERE user_id = ?");
                 PreparedStatement insert = conn.prepareStatement(
                         "INSERT INTO user_interests (user_id, topic_id) VALUES (?, ?)")) {
                delete.setLong(1, userId);
                delete.executeUpdate();
                for (int topicId : topicIds) {
                    insert.setLong(1, userId);
                    insert.setInt(2, topicId);
                    insert.addBatch();
                }
                insert.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Topics that have never been fetched, or not within {@code maxAge}. */
    List<SearchTopic> dueForRefresh(Duration maxAge) {
        String sql = """
                SELECT id, search_query FROM topics
                WHERE videos_fetched_at IS NULL OR videos_fetched_at < now() - make_interval(secs => ?)
                ORDER BY videos_fetched_at NULLS FIRST
                """;
        List<SearchTopic> due = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, maxAge.toSeconds());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    due.add(new SearchTopic(rs.getInt("id"), rs.getString("search_query")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return due;
    }

    void markFetched(int topicId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE topics SET videos_fetched_at = now() WHERE id = ?")) {
            ps.setInt(1, topicId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
