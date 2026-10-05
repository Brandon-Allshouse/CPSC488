package edu.cpsc488.brainfeed;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Checks how App reads PASSWORD_PEPPER from .env.
class PepperTest {

    @Test
    void base64PepperIsDecoded() {
        byte[] pepper = new byte[32];
        pepper[0] = 7;
        String fromEnv = Base64.getEncoder().encodeToString(pepper);
        assertArrayEquals(pepper, App.decodePepper(fromEnv));
    }

    @Test
    void extraSpacesAreIgnored() {
        String fromEnv = Base64.getEncoder().encodeToString(new byte[32]);
        assertArrayEquals(new byte[32], App.decodePepper("  " + fromEnv + "  "));
    }

    @Test
    void notBase64Fails() {
        assertThrows(IllegalStateException.class, () -> App.decodePepper("this is not base64!"));
    }

    @Test
    void tooShortFails() {
        // 16 bytes isn't enough, we want at least 32 (256 bits)
        String fromEnv = Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(IllegalStateException.class, () -> App.decodePepper(fromEnv));
    }
}
