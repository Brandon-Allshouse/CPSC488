package edu.cpsc488.brainfeed.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Predicate;

/**
 * Checks new passwords against the Have I Been Pwned breach corpus (NIST SP 800-63B blocklist
 * requirement) using its k-anonymity API: only the first 5 hex characters of the password's SHA-1
 * are sent, so neither the password nor its full hash ever leaves the server.
 *
 * <p>If the service is unreachable the check is skipped (fails open) and a warning is logged, so an
 * outage doesn't block sign-ups. The other policy rules still apply.
 */
public class BreachedPasswordChecker implements Predicate<String> {

    private static final Logger log = LoggerFactory.getLogger(BreachedPasswordChecker.class);
    private static final String RANGE_URL = "https://api.pwnedpasswords.com/range/";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public boolean test(String password) {
        String sha1 = sha1Hex(PasswordHasher.normalize(password));
        String prefix = sha1.substring(0, 5);
        String suffix = sha1.substring(5);

        HttpRequest request = HttpRequest.newBuilder(URI.create(RANGE_URL + prefix))
                .timeout(Duration.ofSeconds(3))
                // Padding hides how many real matches the prefix has from anyone watching traffic.
                .header("Add-Padding", "true")
                .header("User-Agent", "brainfeed-backend")
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Breached-password check skipped: HTTP {}", response.statusCode());
                return false;
            }
            for (String line : response.body().split("\r?\n")) {
                String[] parts = line.split(":");
                if (parts.length == 2 && parts[0].equalsIgnoreCase(suffix)) {
                    // Padding entries have a count of 0.
                    return Long.parseLong(parts[1].trim()) > 0;
                }
            }
            return false;
        } catch (IOException | NumberFormatException e) {
            log.warn("Breached-password check skipped: {}", e.toString());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String sha1Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
