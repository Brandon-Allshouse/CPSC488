package edu.cpsc488.brainfeed.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordPolicyTest {

    // stand in for the real breach check so the tests don't call the internet
    private static final String LEAKED_PASSWORD = "this one got leaked";

    private final PasswordPolicy policy = new PasswordPolicy(LEAKED_PASSWORD::equals);

    // true if the password would be allowed for this test user
    private boolean isAllowed(String password) {
        return policy.check(password, "testuser", "test@sru.edu").isEmpty();
    }

    @Test
    void normalPassphraseIsAllowed() {
        assertTrue(isAllowed("this is a long password"));
    }

    @Test
    void mustBeAtLeast15Characters() {
        assertFalse(isAllowed("fourteen chars"));   // 14
        assertTrue(isAllowed("fifteen chars!!"));   // 15
    }

    @Test
    void maxLengthIs128() {
        assertTrue(isAllowed("abcdefghij".repeat(12) + "abcdefgh"));    // 128
        assertFalse(isAllowed("abcdefghij".repeat(12) + "abcdefghi"));  // 129
    }

    @Test
    void emojiCountAsOneCharacterEach() {
        // each emoji is 4 bytes but should only count as 1 character, so this is 15
        assertTrue(isAllowed("🦉🐙🦊🐼🦄".repeat(3)));
    }

    @Test
    void noSymbolsOrNumbersRequired() {
        // NIST says not to force special characters, uppercase, etc.
        assertTrue(isAllowed("just some lowercase words"));
    }

    @Test
    void cantUseUsernameEmailOrSiteName() {
        assertFalse(isAllowed("my name is testuser okay"));
        assertFalse(isAllowed("test is in this password"));
        assertFalse(isAllowed("my brainfeed password"));
    }

    @Test
    void repeatedCharactersAreRejected() {
        assertFalse(isAllowed("aaaaaaaaaaaaaaaaaaaa"));
        assertFalse(isAllowed("abababababababababab"));
    }

    @Test
    void leakedPasswordIsRejected() {
        assertFalse(isAllowed(LEAKED_PASSWORD));
    }
}
