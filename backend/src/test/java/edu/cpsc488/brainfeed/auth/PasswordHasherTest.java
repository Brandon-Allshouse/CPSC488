package edu.cpsc488.brainfeed.auth;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    private static final String PASSWORD = "simple test password";

    // fake 32 byte pepper, the real one comes from .env
    private static byte[] testPepper(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private final PasswordHasher hasher = new PasswordHasher(testPepper(1), 2);

    @Test
    void rightPasswordMatchesHash() {
        String hash = hasher.hash(PASSWORD);
        assertTrue(hasher.verify(PASSWORD, hash));
    }

    @Test
    void wrongPasswordDoesNotMatch() {
        String hash = hasher.hash(PASSWORD);
        assertFalse(hasher.verify("simple test passwords", hash));
    }

    @Test
    void hashIsArgon2idAndDoesNotContainThePassword() {
        String hash = hasher.hash(PASSWORD);
        // $argon2id$v=19$m=...,t=...,p=...$<salt>$<hash>
        assertTrue(hash.matches("^\\$argon2id\\$v=19\\$m=19456,t=2,p=1\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}$"), hash);
        assertFalse(hash.contains("password"));
    }

    @Test
    void hashingTwiceGivesDifferentResults() {
        // different salt each time, so two users with the same password won't have the same hash
        assertNotEquals(hasher.hash(PASSWORD), hasher.hash(PASSWORD));
    }

    @Test
    void hashFromDifferentPepperDoesNotVerify() {
        // like if someone stole the database but not the pepper
        String hash = hasher.hash(PASSWORD);
        PasswordHasher otherHasher = new PasswordHasher(testPepper(2), 1);
        assertFalse(otherHasher.verify(PASSWORD, hash));
    }

    @Test
    void accentedLettersMatchEitherWay() {
        // é can be typed as one character or as e + an accent mark depending on the
        // keyboard/OS. Written as escapes so an editor can't quietly merge them.
        String oneChar = "café café café café";
        String twoChars = "café café café café";

        String hash = hasher.hash(oneChar);
        assertTrue(hasher.verify(twoChars, hash));
    }

    @Test
    void longPasswordsArentCutOff() {
        // bcrypt only looks at the first 72 bytes, make sure we don't have that problem
        String start = "a".repeat(100);
        String hash = hasher.hash(start + "X");
        assertFalse(hasher.verify(start + "Y", hash));
    }

    @Test
    void badHashValuesReturnFalse() {
        assertFalse(hasher.verify("whatever", null));
        assertFalse(hasher.verify("whatever", ""));
        assertFalse(hasher.verify("whatever", "not a hash"));
        assertFalse(hasher.verify("whatever", "$argon2id$v=19$m=x,t=2,p=1$abc$def"));
        // a bcrypt hash, which we don't support
        assertFalse(hasher.verify("whatever", "$2a$12$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz01234"));
    }

    @Test
    void pepperTooShortThrows() {
        assertThrows(IllegalArgumentException.class, () -> new PasswordHasher(new byte[16], 1));
    }
}
