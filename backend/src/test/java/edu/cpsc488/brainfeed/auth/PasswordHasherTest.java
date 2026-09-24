package edu.cpsc488.brainfeed.auth;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    private static byte[] pepper(int fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return bytes;
    }

    private final PasswordHasher hasher = new PasswordHasher(pepper(1), 2);

    @Test
    void correctPasswordVerifies() {
        String hash = hasher.hash("correct horse battery staple");
        assertTrue(hasher.verify("correct horse battery staple", hash));
    }

    @Test
    void wrongPasswordFails() {
        String hash = hasher.hash("correct horse battery staple");
        assertFalse(hasher.verify("correct horse battery stapler", hash));
    }

    @Test
    void usesArgon2idPhcFormatAndNeverContainsPlaintext() {
        String hash = hasher.hash("correct horse battery staple");
        assertTrue(hash.matches("^\\$argon2id\\$v=19\\$m=19456,t=2,p=1\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}$"), hash);
        assertFalse(hash.contains("horse"));
    }

    @Test
    void samePasswordGetsDifferentSalts() {
        assertNotEquals(hasher.hash("correct horse battery staple"), hasher.hash("correct horse battery staple"));
    }

    @Test
    void differentPepperCannotVerify() {
        String hash = hasher.hash("correct horse battery staple");
        PasswordHasher otherServer = new PasswordHasher(pepper(2), 1);
        assertFalse(otherServer.verify("correct horse battery staple", hash));
    }

    @Test
    void unicodeIsNormalized() {
        // "é" as one code point vs "e" + combining accent: the same password to a human.
        String hash = hasher.hash("café café café café");
        assertTrue(hasher.verify("café café café café", hash));
    }

    @Test
    void longPasswordsAreNotTruncated() {
        String base = "a".repeat(100);
        String hash = hasher.hash(base + "X");
        assertFalse(hasher.verify(base + "Y", hash));
    }

    @Test
    void malformedHashesAreRejected() {
        assertFalse(hasher.verify("anything", null));
        assertFalse(hasher.verify("anything", ""));
        assertFalse(hasher.verify("anything", "plaintext"));
        assertFalse(hasher.verify("anything", "$argon2id$v=19$m=x,t=2,p=1$abc$def"));
        assertFalse(hasher.verify("anything", "$2a$12$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz01234"));
    }

    @Test
    void shortPepperIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new PasswordHasher(new byte[16], 1));
    }
}
