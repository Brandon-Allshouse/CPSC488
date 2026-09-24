package edu.cpsc488.brainfeed.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordPolicyTest {

    private static final String BREACHED = "known breached passphrase";

    private final PasswordPolicy policy = new PasswordPolicy(BREACHED::equals);

    private boolean accepts(String password) {
        return policy.check(password, "alice_99", "alice@example.com").isEmpty();
    }

    @Test
    void acceptsLongPassphrase() {
        assertTrue(accepts("purple otter juggling teacups"));
    }

    @Test
    void enforcesNistMinimumOf15() {
        assertFalse(accepts("fourteen chars"));      // 14
        assertTrue(accepts("fifteen chars!!"));      // 15
    }

    @Test
    void allowsUpTo128AndRejectsLonger() {
        assertTrue(accepts("abcdefghij".repeat(12) + "abcdefgh"));   // 128
        assertFalse(accepts("abcdefghij".repeat(12) + "abcdefghi")); // 129
    }

    @Test
    void countsUnicodeCharactersNotBytes() {
        // 15 emoji = 15 characters even though each is 4 bytes in UTF-8.
        assertTrue(accepts("🦉🐙🦊🐼🦄".repeat(3)));
    }

    @Test
    void noCompositionRules() {
        // NIST: no required symbols/digits/uppercase. All lowercase with spaces is fine.
        assertTrue(accepts("just some lowercase words"));
    }

    @Test
    void rejectsContextSpecificWords() {
        assertFalse(accepts("my name is alice_99 okay"));
        assertFalse(accepts("alice likes long walks"));
        assertFalse(accepts("i love brainfeed so much"));
    }

    @Test
    void rejectsRepetitivePasswords() {
        assertFalse(accepts("aaaaaaaaaaaaaaaaaaaa"));
        assertFalse(accepts("abababababababababab"));
    }

    @Test
    void rejectsBreachedPasswords() {
        assertFalse(accepts(BREACHED));
    }
}
