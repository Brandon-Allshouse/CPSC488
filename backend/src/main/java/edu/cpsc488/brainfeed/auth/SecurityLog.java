package edu.cpsc488.brainfeed.auth;

import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Security event log (OWASP Top 10 A09, OWASP Logging Cheat Sheet). Records who did what, from
 * where, and whether it worked, without passwords, session tokens or email addresses. All values
 * are stripped of control characters so user input can't forge fake log lines.
 */
public final class SecurityLog {

    private static final Logger log = LoggerFactory.getLogger("security");

    // Where events are also saved so admins can read them (see SecurityLogFile). Set once at
    // startup by App; null in tests, where events only go to the console.
    private static volatile SecurityLogFile file;

    private SecurityLog() {
    }

    /** Also saves every event to this file from now on. Called once by App at startup. */
    public static void writeTo(SecurityLogFile logFile) {
        file = logFile;
    }

    public static void success(String event, Context ctx, Long userId) {
        log.info("event={} outcome=success user={} ip={} ua=\"{}\"",
                event, userId == null ? "-" : userId, clean(ctx.ip()), clean(ctx.userAgent()));
        saveToFile(event, "success", userId, ctx, null);
    }

    /** Same, plus what the action was done to (e.g. which user an admin banned). */
    public static void success(String event, Context ctx, Long userId, String detail) {
        log.info("event={} outcome=success user={} ip={} detail=\"{}\" ua=\"{}\"",
                event, userId == null ? "-" : userId, clean(ctx.ip()), clean(detail), clean(ctx.userAgent()));
        saveToFile(event, "success", userId, ctx, detail);
    }

    public static void failure(String event, Context ctx, Long userId, String reason) {
        log.warn("event={} outcome=failure user={} ip={} reason=\"{}\" ua=\"{}\"",
                event, userId == null ? "-" : userId, clean(ctx.ip()), clean(reason), clean(ctx.userAgent()));
        saveToFile(event, "failure", userId, ctx, reason);
    }

    private static void saveToFile(String event, String outcome, Long userId, Context ctx, String detail) {
        SecurityLogFile logFile = file;
        if (logFile != null) {
            logFile.append(clean(event), outcome, userId == null ? "-" : userId.toString(), clean(ctx.ip()), clean(detail));
        }
    }

    static String clean(String value) {
        if (value == null) {
            return "-";
        }
        String cleaned = value.replaceAll("[\\p{Cntrl}\"]", "_");
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }
}