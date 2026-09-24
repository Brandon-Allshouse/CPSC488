package edu.cpsc488.brainfeed.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * New-password rules from NIST SP 800-63B §3.1.1.2 (rev. 4):
 * <ul>
 *   <li>At least 15 characters, since the password is the only authentication factor.</li>
 *   <li>Allow at least 64 characters; we allow 128 (OWASP ASVS 2.1.2), counted in Unicode code points.</li>
 *   <li>All printable characters, spaces and Unicode are allowed; nothing is truncated.</li>
 *   <li>No composition rules (no "must contain a symbol") and no forced periodic changes.</li>
 *   <li>Reject passwords that appear in breach corpuses, or that are context-specific or repetitive.</li>
 * </ul>
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 15;
    public static final int MAX_LENGTH = 128;

    private static final String[] SERVICE_WORDS = {"brainfeed", "brain feed"};

    private final Predicate<String> isBreached;

    /** @param isBreached returns true if the password is known to be compromised (see BreachedPasswordChecker). */
    public PasswordPolicy(Predicate<String> isBreached) {
        this.isBreached = isBreached;
    }

    public static int length(String password) {
        String normalized = PasswordHasher.normalize(password);
        return normalized.codePointCount(0, normalized.length());
    }

    /** Returns a user-facing reason the password is rejected, or empty if it's acceptable. */
    public Optional<String> check(String password, String username, String email) {
        int length = length(password);
        if (length < MIN_LENGTH) {
            return Optional.of("Password must be at least " + MIN_LENGTH + " characters. A few random words works well.");
        }
        if (length > MAX_LENGTH) {
            return Optional.of("Password must be at most " + MAX_LENGTH + " characters.");
        }

        String lower = PasswordHasher.normalize(password).toLowerCase(Locale.ROOT);
        String emailLocal = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        if (containsWord(lower, username) || containsWord(lower, emailLocal)) {
            return Optional.of("Password can't contain your username or email.");
        }
        for (String word : SERVICE_WORDS) {
            if (lower.contains(word)) {
                return Optional.of("Password can't contain the name of this site.");
            }
        }
        if (lower.codePoints().distinct().count() < 4) {
            return Optional.of("Password is too repetitive. Try a few unrelated words.");
        }
        if (isBreached.test(password)) {
            return Optional.of("This password has appeared in a data breach, so it isn't safe to use. Please choose another.");
        }
        return Optional.empty();
    }

    private static boolean containsWord(String haystack, String word) {
        return word != null && word.length() >= 3 && haystack.contains(word.toLowerCase(Locale.ROOT));
    }
}
