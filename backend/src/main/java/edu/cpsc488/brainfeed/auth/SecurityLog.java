package edu.cpsc488.brainfeed.auth;

import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Security event log (OWASP Top 10 A09, OWASP Logging Cheat Sheet). Records who did what, from
 * where, and whether it worked, without passwords, session tokens or email addresses. All values
 * are stripped of control characters so user input can't forge fake log lines.
 */
final class SecurityLog {

    private static final Logger log = LoggerFactory.getLogger("security");

    private SecurityLog() {
    }

    static void success(String event, Context ctx, Long userId) {
        log.info("event={} outcome=success user={} ip={} ua=\"{}\"",
                event, userId == null ? "-" : userId, clean(ctx.ip()), clean(ctx.userAgent()));
    }

    static void failure(String event, Context ctx, Long userId, String reason) {
        log.warn("event={} outcome=failure user={} ip={} reason=\"{}\" ua=\"{}\"",
                event, userId == null ? "-" : userId, clean(ctx.ip()), clean(reason), clean(ctx.userAgent()));
    }

    static String clean(String value) {
        if (value == null) {
            return "-";
        }
        String cleaned = value.replaceAll("[\\p{Cntrl}\"]", "_");
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }
}
