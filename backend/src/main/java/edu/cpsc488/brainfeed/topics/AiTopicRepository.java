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
import java.util.function.Function;

/**
 * Adds the topics users type to the {@code topics} table (V13, V15), within the limits, and
 * applies what the background review decided (moving, re-querying or removing them).
 */
public class AiTopicRepository {

    /** The limits, passed in so tests can use small ones. */
    record Limits(int perUserPerDay, int perDay, int maxSubjects, int maxTopics) {
    }

    enum Outcome {
        /** the topic was added */
        ADDED,
        /** the topic (or a close match) was already there; that one is returned */
        EXISTS,
        /** an admin or the review removed a topic with this name */
        BANNED,
        USER_LIMIT,
        DAILY_LIMIT,
        CATALOG_FULL,
        /** there's no subject to put it under (the Explore subject is missing) */
        NO_SUBJECT
    }

    /** What happened, the topic the user should now have, and how it matched (null when added). */
    record Result(Outcome outcome, Topic topic, TopicMatcher.Kind kind) {
        static Result of(Outcome outcome) {
            return new Result(outcome, null, null);
        }
    }

    /** An AI-made topic waiting for its review. createdBy is null if that account was deleted. */
    record Pending(int id, String name, Integer parentId, Long createdBy) {
    }

    /** What the review decided for a topic that's allowed to stay. */
    record Placement(String subject, String searchQuery) {
    }

    // Any constant works; it just has to be the same for every caller. Taking it makes creates
    // run one at a time, so the limits below can't be overshot by requests racing each other,
    // and two people typing "Black Hole" and "Black Holes" at once get one topic, not two.
    private static final long CREATE_LOCK = 488_013L;

    private final DataSource dataSource;

    public AiTopicRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** The whole catalog, subjects first, for matching names and for the review prompt. */
    List<Topic> catalog() {
        try (Connection conn = dataSource.getConnection()) {
            return catalog(conn);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** True if this name, or its singular or plural, was removed by an admin or the review. */
    boolean isBanned(String name) {
        try (Connection conn = dataSource.getConnection()) {
            return banned(conn, name);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Adds {@code name} under the subject {@code subjectFor} picks, unless {@code match} finds it
     * in the catalog first. Both run again here, inside the lock, on the catalog as it is now,
     * because someone may have added the same topic since the caller last looked.
     */
    Result add(long userId, String name, String query, Function<List<Topic>, Optional<TopicMatcher.Match>> match,
               Function<List<Topic>, Optional<Topic>> subjectFor, Limits limits) {
        return Database.inTransaction(dataSource, conn -> {
            try (PreparedStatement lock = conn.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                lock.setLong(1, CREATE_LOCK);
                lock.execute();
            }
            List<Topic> catalog = catalog(conn);
            Optional<TopicMatcher.Match> existing = match.apply(catalog);
            if (existing.isPresent()) {
                return new Result(Outcome.EXISTS, existing.get().topic(), existing.get().kind());
            }
            if (banned(conn, name)) {
                return Result.of(Outcome.BANNED);
            }
            if (count(conn, "SELECT count(*) FROM topics WHERE source = 'ai' AND created_by = ? "
                    + "AND created_at > now() - interval '1 day'", userId) >= limits.perUserPerDay()) {
                return Result.of(Outcome.USER_LIMIT);
            }
            if (count(conn, "SELECT count(*) FROM topics WHERE source = 'ai' "
                    + "AND created_at > now() - interval '1 day'", null) >= limits.perDay()) {
                return Result.of(Outcome.DAILY_LIMIT);
            }
            if (catalog.size() >= limits.maxTopics()) {
                return Result.of(Outcome.CATALOG_FULL);
            }
            Optional<Topic> subject = subjectFor.apply(catalog);
            if (subject.isEmpty() || subject.get().parentId() != null) {
                return Result.of(Outcome.NO_SUBJECT);
            }
            return new Result(Outcome.ADDED, insert(conn, subject.get().id(), name, query, userId), null);
        });
    }

    /**
     * Adds the topic to the user's interests, unless they follow {@code maxInterests} already.
     * Returns false only when they're at that limit; following something twice is fine.
     */
    boolean follow(long userId, int topicId, int maxInterests) {
        String sql = """
                INSERT INTO user_interests (user_id, topic_id)
                SELECT ?, ? WHERE (SELECT count(*) FROM user_interests WHERE user_id = ?) < ?
                ON CONFLICT DO NOTHING
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setInt(2, topicId);
            ps.setLong(3, userId);
            ps.setInt(4, maxInterests);
            if (ps.executeUpdate() == 1) {
                return true;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        // Nothing inserted: either they already follow it (fine) or they're at the limit.
        return isFollowing(userId, topicId);
    }

    /** AI-made topics the review hasn't looked at yet, oldest first. */
    List<Pending> unreviewed(int limit) {
        String sql = """
                SELECT id, name, parent_id, created_by FROM topics
                WHERE source = 'ai' AND reviewed_at IS NULL ORDER BY id LIMIT ?
                """;
        List<Pending> pending = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pending.add(new Pending(rs.getInt("id"), rs.getString("name"),
                            rs.getObject("parent_id", Integer.class), rs.getObject("created_by", Long.class)));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return pending;
    }

    /** One waiting topic, or empty if it's gone (an admin removed it) or was already reviewed. */
    Optional<Pending> pending(int topicId) {
        String sql = """
                SELECT id, name, parent_id, created_by FROM topics
                WHERE id = ? AND source = 'ai' AND reviewed_at IS NULL
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, topicId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new Pending(rs.getInt("id"), rs.getString("name"),
                        rs.getObject("parent_id", Integer.class), rs.getObject("created_by", Long.class)))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Applies a review that let the topic stay: a better YouTube search, and a better subject.
     * The subject is an existing one (matched by name), or a new one made for it, or, when the
     * review says the topic is a subject in its own right ("Cooking"), the topic becomes that
     * subject. A subject that can't be used (banned, over the limit, taken by a sub-topic) just
     * leaves the topic where it is. Always marks the topic reviewed. Returns false if it's gone.
     */
    boolean place(int topicId, Placement placement, Limits limits) {
        return Database.inTransaction(dataSource, conn -> {
            try (PreparedStatement lock = conn.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                lock.setLong(1, CREATE_LOCK);
                lock.execute();
            }
            Optional<Pending> found = Optional.empty();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, name, parent_id, created_by FROM topics WHERE id = ? AND source = 'ai' FOR UPDATE")) {
                ps.setInt(1, topicId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        found = Optional.of(new Pending(rs.getInt("id"), rs.getString("name"),
                                rs.getObject("parent_id", Integer.class), rs.getObject("created_by", Long.class)));
                    }
                }
            }
            if (found.isEmpty()) {
                return false;
            }
            Pending topic = found.get();
            Integer parentId = topic.parentId();
            String subject = placement.subject();
            if (subject != null && topic.parentId() != null && !hasChildren(conn, topicId)) {
                Move move = newParent(conn, topic, subject, limits);
                if (!move.stay()) {
                    parentId = move.parentId();
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE topics SET parent_id = ?, search_query = coalesce(?, search_query), reviewed_at = now() "
                            + "WHERE id = ?")) {
                ps.setObject(1, parentId);
                ps.setString(2, placement.searchQuery());
                ps.setInt(3, topicId);
                ps.executeUpdate();
            }
            return true;
        });
    }

    /** Where the review moves a topic. stay: leave it. Otherwise parentId, which is null to make it a subject. */
    private record Move(boolean stay, Integer parentId) {
        static final Move STAY = new Move(true, null);
    }

    private static Move newParent(Connection conn, Pending topic, String subject, Limits limits) throws SQLException {
        if (SubjectGuesser.FALLBACK.equalsIgnoreCase(subject) || banned(conn, subject) || TopicDenylist.blocks(subject)) {
            return Move.STAY;
        }
        if (TopicNames.matchKey(subject).equals(TopicNames.matchKey(topic.name()))) {
            // "Cooking" in Explore becomes the subject Cooking, if there's room for one more.
            return subjectCount(conn) < limits.maxSubjects() ? new Move(false, null) : Move.STAY;
        }
        Optional<Topic> existing = findByMatchKey(conn, subject);
        if (existing.isPresent()) {
            return existing.get().parentId() == null ? new Move(false, existing.get().id()) : Move.STAY;
        }
        if (subjectCount(conn) >= limits.maxSubjects()
                || count(conn, "SELECT count(*) FROM topics", null) >= limits.maxTopics()) {
            return Move.STAY;
        }
        return new Move(false, insert(conn, null, subject, TopicParser.query(null, subject), topic.createdBy()).id());
    }

    /**
     * Removes a topic the review flagged and blocks its name, the same way an admin removal does
     * (AdminRepository.removeAiTopic), and logs it in admin_actions with no admin, so admins see
     * it next to their own removals. Returns false if it was already gone.
     */
    boolean removeFlagged(int topicId, String reason) {
        return Database.inTransaction(dataSource, conn -> {
            String name;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT name FROM topics WHERE id = ? AND source = 'ai' FOR UPDATE")) {
                ps.setInt(1, topicId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    name = rs.getString(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO banned_topic_names (name_lower, reason)
                    VALUES (lower(?), ?)
                    ON CONFLICT (name_lower) DO UPDATE SET reason = EXCLUDED.reason
                    """)) {
                ps.setString(1, name);
                ps.setString(2, reason);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM topics WHERE id = ?")) {
                ps.setInt(1, topicId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO admin_actions (admin_id, action, target, reason) VALUES (NULL, 'remove_topic', ?, ?)")) {
                ps.setString(1, cut(name + " (#" + topicId + ")", 100));
                ps.setString(2, reason);
                ps.executeUpdate();
            }
            return true;
        });
    }

    /** Marks a topic reviewed without changing it, e.g. when the model's reply was unusable twice. */
    void markReviewed(int topicId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE topics SET reviewed_at = now() WHERE id = ?")) {
            ps.setInt(1, topicId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Topic> catalog(Connection conn) throws SQLException {
        String sql = "SELECT id, name, parent_id, source FROM topics ORDER BY parent_id IS NOT NULL, id";
        List<Topic> topics = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                topics.add(map(rs));
            }
        }
        return topics;
    }

    private static Topic insert(Connection conn, Integer parentId, String name, String query, Long userId)
            throws SQLException {
        String sql = """
                INSERT INTO topics (parent_id, name, search_query, source, created_by, reviewed_at)
                VALUES (?, ?, ?, 'ai', ?, CASE WHEN ? THEN now() END)
                RETURNING id, name, parent_id, source
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, parentId);
            ps.setString(2, name);
            ps.setString(3, query);
            ps.setObject(4, userId);
            // A subject the review made is already reviewed; a typed topic waits for its review.
            ps.setBoolean(5, parentId == null);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return map(rs);
            }
        }
    }

    private static Optional<Topic> findByMatchKey(Connection conn, String name) throws SQLException {
        String key = TopicNames.matchKey(name);
        return catalog(conn).stream().filter(topic -> TopicNames.matchKey(topic.name()).equals(key)).findFirst();
    }

    private static boolean hasChildren(Connection conn, int topicId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM topics WHERE parent_id = ? LIMIT 1")) {
            ps.setInt(1, topicId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static int subjectCount(Connection conn) throws SQLException {
        return count(conn, "SELECT count(*) FROM topics WHERE parent_id IS NULL", null);
    }

    private static boolean banned(Connection conn, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM banned_topic_names WHERE name_lower = ANY (?)")) {
            ps.setArray(1, conn.createArrayOf("text", TopicNames.banKeys(name).toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    boolean isFollowing(long userId, int topicId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM user_interests WHERE user_id = ? AND topic_id = ?")) {
            ps.setLong(1, userId);
            ps.setInt(2, topicId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
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

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static Topic map(ResultSet rs) throws SQLException {
        return new Topic(rs.getInt("id"), rs.getString("name"), rs.getObject("parent_id", Integer.class),
                "ai".equals(rs.getString("source")));
    }
}
