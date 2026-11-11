package edu.cpsc488.brainfeed.auth;

import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.db.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Runs against the real test database (see TestDatabase).
class UserRepositoryTest {

    UserRepository users;

    @BeforeEach
    void setUp() {
        users = new UserRepository(TestDatabase.get());
        TestDatabase.reset();
    }

    @Test
    void createsAUserAndFindsThemAgain() {
        User user = users.create("test@sru.edu", "testuser", TestDatabase.FAKE_HASH, "192.0.2.10");
        assertEquals("testuser", users.findById(user.id()).orElseThrow().username());
        UserRepository.Credentials found = users.findByEmail("test@sru.edu").orElseThrow();
        assertEquals(TestDatabase.FAKE_HASH, found.passwordHash());
        assertFalse(found.banned());
        assertEquals("192.0.2.10", TestDatabase.text("SELECT host(signup_ip) FROM users WHERE id = ?", user.id()));
    }

    @Test
    void emailsAndUsernamesAreUnique() {
        users.create("test@sru.edu", "testuser", TestDatabase.FAKE_HASH, null);
        ApiException email = assertThrows(ApiException.class,
                () -> users.create("test@sru.edu", "otheruser", TestDatabase.FAKE_HASH, null));
        assertEquals(409, email.status());
        assertTrue(email.getMessage().contains("email"));
        // usernames are compared ignoring case
        ApiException name = assertThrows(ApiException.class,
                () -> users.create("other@sru.edu", "TESTUSER", TestDatabase.FAKE_HASH, null));
        assertTrue(name.getMessage().contains("username"));
    }

    @Test
    void theDatabaseOnlyAcceptsArgon2idHashes() {
        // a backstop in case code ever tried to store a plain password
        assertThrows(RuntimeException.class, () -> users.create("test@sru.edu", "testuser", "simple test password", null));
    }

    @Test
    void theDatabaseRefusesBadEmailsAndUsernames() {
        assertThrows(RuntimeException.class, () -> users.create("Test@SRU.edu", "testuser", TestDatabase.FAKE_HASH, null));
        assertThrows(RuntimeException.class, () -> users.create("test@sru.edu", "no spaces", TestDatabase.FAKE_HASH, null));
    }

    @Test
    void bannedUsersAreInvisibleToSessionsButLoginKnows() {
        User user = users.create("test@sru.edu", "testuser", TestDatabase.FAKE_HASH, null);
        TestDatabase.exec("UPDATE users SET banned_at = now(), ban_reason = 'spam' WHERE id = ?", user.id());
        assertTrue(users.findById(user.id()).isEmpty());
        assertTrue(users.findByEmail("test@sru.edu").orElseThrow().banned());
    }

    @Test
    void unknownIpsAreStoredAsNull() {
        User user = users.create("test@sru.edu", "testuser", TestDatabase.FAKE_HASH, "not an ip");
        assertEquals(null, TestDatabase.text("SELECT signup_ip::text FROM users WHERE id = ?", user.id()));
        users.recordLogin(user.id(), "198.51.100.7");
        assertEquals("198.51.100.7", TestDatabase.text("SELECT host(last_login_ip) FROM users WHERE id = ?", user.id()));
    }
}
