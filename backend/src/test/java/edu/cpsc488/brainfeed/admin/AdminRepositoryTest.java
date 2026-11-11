package edu.cpsc488.brainfeed.admin;

import edu.cpsc488.brainfeed.db.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Runs against the real test database (see TestDatabase).
class AdminRepositoryTest {

    AdminRepository admins;
    long admin;
    long user;
    int math;

    @BeforeEach
    void setUp() {
        admins = new AdminRepository(TestDatabase.get());
        TestDatabase.reset();
        admin = TestDatabase.addUser("testadmin");
        TestDatabase.makeAdmin(admin);
        user = TestDatabase.addUser("testuser");
        math = TestDatabase.topicId("Math");
    }

    @Test
    void banningLogsTheUserOutAndWritesTheLog() {
        TestDatabase.exec("INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (repeat('a', 64), ?, now() + interval '1 day')", user);
        assertTrue(admins.banUser(admin, "203.0.113.5", user, "spam"));
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM sessions WHERE user_id = ?", user));
        assertEquals("spam", admins.findUser(user).orElseThrow().banReason());
        AdminRepository.AdminAction action = admins.recentActions(10).get(0);
        assertEquals("ban_user", action.action());
        assertEquals("testuser (#" + user + ")", action.target());
        assertEquals("203.0.113.5", action.ip());
        assertEquals("testadmin", action.admin());
    }

    @Test
    void adminsCantBeBanned() {
        assertFalse(admins.banUser(admin, null, admin, "oops"));
        // the database backs this up too
        assertThrows(RuntimeException.class,
                () -> TestDatabase.exec("UPDATE users SET banned_at = now(), ban_reason = 'x' WHERE id = ?", admin));
    }

    @Test
    void unbanning() {
        assertFalse(admins.unbanUser(admin, null, user));
        admins.banUser(admin, null, user, "spam");
        assertTrue(admins.unbanUser(admin, null, user));
        assertNull(admins.findUser(user).orElseThrow().bannedAt());
        assertEquals("unban_user", admins.recentActions(10).get(0).action());
    }

    @Test
    void searchTreatsWildcardsAsPlainText() {
        TestDatabase.addUser("test_user2");
        // "_" escaped, so it only matches a real underscore, not any character
        List<AdminRepository.AdminUser> found = admins.searchUsers(AdminController.likePattern("t_u"), 50);
        assertEquals(List.of("test_user2"), found.stream().map(AdminRepository.AdminUser::username).toList());
        assertTrue(admins.searchUsers(AdminController.likePattern("%"), 50).isEmpty());
    }

    @Test
    void banningAVideoRemovesItAndKeepsItOut() {
        TestDatabase.addVideo("video000001", math);
        TestDatabase.exec("INSERT INTO user_video_feedback (user_id, youtube_id, feedback) VALUES (?, 'video000001', 'liked')", user);
        admins.banVideo(admin, null, "video000001", "off-topic");
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM videos"));
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM user_video_feedback"));
        AdminRepository.BannedVideo banned = admins.bannedVideos(10).get(0);
        assertEquals("Video video000001", banned.title());
        assertEquals("off-topic", banned.reason());
        assertTrue(admins.unbanVideo(admin, null, "video000001"));
        assertFalse(admins.unbanVideo(admin, null, "video000001"));
    }

    @Test
    void statsCountEverything() {
        TestDatabase.addVideo("video000001", math);
        admins.banUser(admin, null, user, "spam");
        AdminRepository.Stats stats = admins.stats();
        assertEquals(2, stats.users());
        assertEquals(1, stats.bannedUsers());
        assertEquals(1, stats.admins());
        assertEquals(1, stats.videos());
    }

    @Test
    void removingAnAiTopicBlocksTheNameAndLogsIt() {
        int space = TestDatabase.topicId("Space");
        int topic = (int) TestDatabase.number("INSERT INTO topics (parent_id, name, search_query, source, created_by) "
                + "VALUES (?, 'Comets', 'comets', 'ai', ?) RETURNING id", space, user);
        TestDatabase.addVideo("video000001", topic);
        TestDatabase.exec("INSERT INTO user_interests (user_id, topic_id) VALUES (?, ?)", user, topic);

        AdminRepository.AiTopic listed = admins.aiTopics(10).get(0);
        assertEquals("Comets", listed.name());
        assertEquals("Space", listed.subject());
        assertEquals("testuser", listed.addedBy());
        assertEquals(1, listed.picks());

        assertTrue(admins.removeAiTopic(admin, "203.0.113.5", topic, "not educational"));
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM topics WHERE id = ?", topic));
        // its videos and picks go with it
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM videos"));
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM user_interests"));
        assertEquals("comets", TestDatabase.text("SELECT name_lower FROM banned_topic_names"));
        AdminRepository.AdminAction action = admins.recentActions(10).get(0);
        assertEquals("remove_topic", action.action());
        assertEquals("Comets (#" + topic + ")", action.target());
    }

    @Test
    void removingASubjectRemovesItsTopics() {
        int subject = (int) TestDatabase.number("INSERT INTO topics (name, search_query, source, created_by) "
                + "VALUES ('Cooking', 'cooking', 'ai', ?) RETURNING id", user);
        TestDatabase.exec("INSERT INTO topics (parent_id, name, search_query, source, created_by) VALUES (?, 'Baking Bread', 'bread', 'ai', ?)", subject, user);
        assertTrue(admins.removeAiTopic(admin, null, subject, "spam"));
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM topics WHERE source = 'ai'"));
    }

    @Test
    void seededTopicsCantBeRemoved() {
        assertFalse(admins.removeAiTopic(admin, null, math, "nope"));
        assertEquals(1, TestDatabase.number("SELECT count(*) FROM topics WHERE id = ?", math));
        assertTrue(admins.recentActions(10).isEmpty());
    }
}
