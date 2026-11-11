package edu.cpsc488.brainfeed.topics;

import edu.cpsc488.brainfeed.db.Database;
import edu.cpsc488.brainfeed.feed.Topic;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Adds AI-suggested topics to the {@code topics} table (V13), within the limits. */
public class AiTopicRepository {

    /** The limits, passed in so tests can use small ones. */
    record Limits(int perUserPerDay, int perDay, int maxSubjects, int maxTopics) {
    }

    enum Outcome {
        /** the topic (and its new subject, if any) was added */
        ADDED,
        /** someone added a topic with that name first; the existing one is returned */
        EXISTS,
        /** an admin removed a topic with this name */
        BANNED,
        USER_LIMIT,
        DAILY_LIMIT,
        CATALOG_FULL,
        /** the name is taken by a topic that can't be used here, e.g. a subject's name as a topic */
        CONFLICT
    }

    /** topics: what the user should now have, subject first when one was added. */
    record Result(Outcome outcome, List<Topic> topics) {
        static Result of(Outcome outcome) {
            return new Result(outcome, List.of());
        }
    }

    // Any constant works; it just has to be the same for every caller. Taking it makes creates
    // run one at a time, so the limits below can't be overshot by requests racing each other.
    private static final long CREATE_LOCK = 488_013L;

    private final DataSource dataSource;

    public AiTopicRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** The whole catalog, for the prompt and for matching names. */
    List<Topic> catalog() {
        String sql = "SELECT id, name, parent_id, source FROM topics ORDER BY parent_id IS NOT NULL, id";
        List<Topic> topics = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                topics.add(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return topics;
    }

    boolean isBanned(String name) {
        String sql = "SELECT 1 FROM banned_topic_names WHERE name_lower = lower(?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Adds a topic under an existing subject ({@code subjectId}) or under a new subject
     * ({@code newSubject}, with its own search query). Everything is checked again here, inside
     * the transaction, because the catalog may have changed since the suggestion was made.
     */
    Result add(long userId, Integer subjectId, String newSubject, String newSubjectQuery,
               String name, String query, Limits limits) {
        return Database.inTransaction(dataSource, conn -> {
            try (PreparedStatement lock = conn.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                lock.setLong(1, CREATE_LOCK);
                lock.execute();
            }
            Optional<Topic> existing = findByName(conn, name);
            if (existing.isPresent()) {
                // Picking the one we already have is what the user wanted anyway.
                return new Result(Outcome.EXISTS, List.of(existing.get()));
            }
            if (banned(conn, name) || (newSubject != null && banned(conn, newSubject))) {
                return Result.of(Outcome.BANNED);
            }
            // "Cooking" in a new subject "Cooking": that's just the subject (names are unique)
            boolean subjectOnly = newSubject != null && TopicParser.key(newSubject).equals(TopicParser.key(name));
            int needed = newSubject == null || subjectOnly ? 1 : 2;
            if (count(conn, "SELECT count(*) FROM topics WHERE source = 'ai' AND created_by = ? "
                    + "AND created_at > now() - interval '1 day'", userId) + needed > limits.perUserPerDay()) {
                return Result.of(Outcome.USER_LIMIT);
            }
            if (count(conn, "SELECT count(*) FROM topics WHERE source = 'ai' "
                    + "AND created_at > now() - interval '1 day'", null) + needed > limits.perDay()) {
                return Result.of(Outcome.DAILY_LIMIT);
            }
            if (count(conn, "SELECT count(*) FROM topics", null) + needed > limits.maxTopics()) {
                return Result.of(Outcome.CATALOG_FULL);
            }
            List<Topic> added = new ArrayList<>();
            Integer parentId = subjectId;
            if (newSubject != null) {
                Optional<Topic> subject = findByName(conn, newSubject);
                if (subject.isPresent()) {
                    if (subject.get().parentId() != null) {
                        return Result.of(Outcome.CONFLICT);
                    }
                    parentId = subject.get().id();
                } else {
                    if (count(conn, "SELECT count(*) FROM topics WHERE parent_id IS NULL", null) >= limits.maxSubjects()) {
                        return Result.of(Outcome.CATALOG_FULL);
                    }
                    Topic subjectRow = insert(conn, null, newSubject, newSubjectQuery, userId);
                    added.add(subjectRow);
                    if (subjectOnly) {
                        return new Result(Outcome.ADDED, List.copyOf(added));
                    }
                    parentId = subjectRow.id();
                }
            } else if (!isSubject(conn, subjectId)) {
                return Result.of(Outcome.CONFLICT);
            }
            added.add(insert(conn, parentId, name, query, userId));
            return new Result(Outcome.ADDED, List.copyOf(added));
        });
    }

    private static Topic insert(Connection conn, Integer parentId, String name, String query, long userId)
            throws SQLException {
        String sql = """
                INSERT INTO topics (parent_id, name, search_query, source, created_by)
                VALUES (?, ?, ?, 'ai', ?)
                RETURNING id, name, parent_id, source
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, parentId);
            ps.setString(2, name);
            ps.setString(3, query);
            ps.setLong(4, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return map(rs);
            }
        }
    }

    private static Optional<Topic> findByName(Connection conn, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, parent_id, source FROM topics WHERE lower(name) = lower(?)")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private static boolean isSubject(Connection conn, Integer id) throws SQLException {
        if (id == null) {
            return false;
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM topics WHERE id = ? AND parent_id IS NULL")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean banned(Connection conn, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM banned_topic_names WHERE name_lower = lower(?)")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static int count(Connection conn, String sql, Long userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (userId != null) {
                ps.setLong(1, userId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static Topic map(ResultSet rs) throws SQLException {
        return new Topic(rs.getInt("id"), rs.getString("name"), rs.getObject("parent_id", Integer.class),
                "ai".equals(rs.getString("source")));
    }
}
