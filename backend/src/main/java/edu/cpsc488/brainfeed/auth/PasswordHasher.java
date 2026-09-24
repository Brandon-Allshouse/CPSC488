package edu.cpsc488.brainfeed.auth;

import edu.cpsc488.brainfeed.ApiException;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Password hashing per the OWASP Password Storage Cheat Sheet and NIST SP 800-63B §3.1.1.2:
 * <ul>
 *   <li>Argon2id (OWASP's first choice) with m=19 MiB, t=2, p=1 (OWASP's recommended minimum).</li>
 *   <li>A unique 128-bit random salt per password, stored in the hash string.</li>
 *   <li>A secret pepper (HMAC-SHA256 key) kept outside the database, so a stolen database alone
 *       can't be brute-forced.</li>
 *   <li>Unicode NFKC normalization, so the same password typed on different devices matches.</li>
 * </ul>
 * Hashes are stored in the standard PHC string format: {@code $argon2id$v=19$m=..,t=..,p=..$salt$hash}.
 */
public final class PasswordHasher {

    // Cost settings for new hashes. Each stored hash records the settings it was made with, so
    // raising these later is safe: old hashes still verify. They just won't be upgraded
    // automatically; that would mean re-hashing at the next successful login.
    static final int MEMORY_KB = 19_456;
    static final int ITERATIONS = 2;
    static final int PARALLELISM = 1;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64_ENCODE = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODE = Base64.getDecoder();

    private final byte[] pepper;
    // Each hash uses ~19 MiB of memory; cap concurrent hashes so a login flood can't exhaust RAM.
    private final Semaphore permits;

    public PasswordHasher(byte[] pepper, int maxConcurrentHashes) {
        if (pepper == null || pepper.length < 32) {
            throw new IllegalArgumentException("Password pepper must be at least 32 bytes.");
        }
        this.pepper = pepper.clone();
        this.permits = new Semaphore(maxConcurrentHashes, true);
    }

    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = argon2(password, salt, MEMORY_KB, ITERATIONS, PARALLELISM, HASH_BYTES);
        return "$argon2id$v=19$m=" + MEMORY_KB + ",t=" + ITERATIONS + ",p=" + PARALLELISM
                + "$" + B64_ENCODE.encodeToString(salt) + "$" + B64_ENCODE.encodeToString(hash);
    }

    public boolean verify(String password, String encoded) {
        // ["", "argon2id", "v=19", "m=..,t=..,p=..", salt, hash]
        String[] parts = encoded == null ? new String[0] : encoded.split("\\$");
        if (parts.length != 6 || !parts[1].equals("argon2id") || !parts[2].equals("v=19")) {
            return false;
        }
        try {
            int memory = 0, iterations = 0, parallelism = 0;
            for (String param : parts[3].split(",")) {
                String[] kv = param.split("=", 2);
                int value = Integer.parseInt(kv[1]);
                switch (kv[0]) {
                    case "m" -> memory = value;
                    case "t" -> iterations = value;
                    case "p" -> parallelism = value;
                    default -> {
                        return false;
                    }
                }
            }
            byte[] salt = B64_DECODE.decode(parts[4]);
            byte[] expected = B64_DECODE.decode(parts[5]);
            byte[] actual = argon2(password, salt, memory, iterations, parallelism, expected.length);
            // Constant-time comparison so response timing doesn't leak how many bytes matched.
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            return false;
        }
    }

    /** NFKC normalization per NIST SP 800-63B; also used by the password policy for length checks. */
    static String normalize(String password) {
        return Normalizer.normalize(password, Normalizer.Form.NFKC);
    }

    private byte[] argon2(String password, byte[] salt, int memoryKb, int iterations, int parallelism, int length) {
        byte[] input = pepper(normalize(password).getBytes(StandardCharsets.UTF_8));
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memoryKb)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build();
        byte[] out = new byte[length];

        acquire();
        try {
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(params);
            generator.generateBytes(input, out);
            return out;
        } finally {
            permits.release();
            Arrays.fill(input, (byte) 0);
        }
    }

    private byte[] pepper(byte[] normalizedPassword) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return mac.doFinal(normalizedPassword);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            Arrays.fill(normalizedPassword, (byte) 0);
        }
    }

    private void acquire() {
        try {
            if (!permits.tryAcquire(10, TimeUnit.SECONDS)) {
                throw new ApiException(503, "The server is busy. Please try again in a moment.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "The server is busy. Please try again in a moment.");
        }
    }
}
