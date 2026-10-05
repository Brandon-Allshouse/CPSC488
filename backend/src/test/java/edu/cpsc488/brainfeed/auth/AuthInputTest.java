package edu.cpsc488.brainfeed.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The email and username rules from AuthController. The endpoints themselves need a database,
// so they'll be covered by the API tests later.
class AuthInputTest {

    @Test
    void emailsAreTrimmedAndLowercased() {
        assertEquals("test@sru.edu", AuthController.normalizeEmail("  Test@SRU.edu "));
    }

    @Test
    void missingEmailBecomesEmpty() {
        assertEquals("", AuthController.normalizeEmail(null));
    }

    @Test
    void normalEmailIsValid() {
        assertTrue(AuthController.isValidEmail("test@sru.edu"));
        assertTrue(AuthController.isValidEmail("test.user+feed@sru.edu"));
    }

    @Test
    void badEmailsAreRejected() {
        assertFalse(AuthController.isValidEmail(""));
        assertFalse(AuthController.isValidEmail("testuser"));
        assertFalse(AuthController.isValidEmail("test@sru"));
        assertFalse(AuthController.isValidEmail("test @sru.edu"));
        assertFalse(AuthController.isValidEmail("test@sru.edu<script>"));
    }

    @Test
    void emailOver254CharactersIsRejected() {
        String email = "a".repeat(246) + "@sru.edu"; // 254
        assertTrue(AuthController.isValidEmail(email));
        assertFalse(AuthController.isValidEmail("a" + email));
    }

    @Test
    void usernameRules() {
        assertTrue(AuthController.isValidUsername("testuser"));
        assertTrue(AuthController.isValidUsername("test_user_2"));
        assertFalse(AuthController.isValidUsername("ab"));              // too short
        assertFalse(AuthController.isValidUsername("a".repeat(31)));    // too long
        assertFalse(AuthController.isValidUsername("test user"));       // space
        assertFalse(AuthController.isValidUsername("test-user"));       // dash
    }
}
