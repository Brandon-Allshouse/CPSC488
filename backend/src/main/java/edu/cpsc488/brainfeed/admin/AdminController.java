package edu.cpsc488.brainfeed.admin;

import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.RequestBodies;
import edu.cpsc488.brainfeed.auth.AuthController;
import edu.cpsc488.brainfeed.auth.SecurityLog;
import edu.cpsc488.brainfeed.auth.User;
import edu.cpsc488.brainfeed.feed.FeedController;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;

import java.util.Map;
import java.util.Optional;

/**
 * Endpoints behind the admin page: ban and unban users and videos, search for them, and see the
 * log of what admins did. The request and response shapes are in the README's "Admin API" table.
 *
 * <p>Every route under /api/admin/ goes through {@link #requireAdmin} first, including any added
 * later, so a new admin endpoint can't accidentally be left open. The admin flag is re-read from
 * the database on each request, so removing someone's admin access works right away.
 */
public class AdminController {

    static final int MAX_REASON = 200;
    static final int MAX_SEARCH = 100;
    static final int SEARCH_LIMIT = 50;
    static final int LIST_LIMIT = 200;

    private static final String ADMIN_ATTRIBUTE = "brainfeed.admin";

    record BanRequest(String reason) {
    }

    private final AdminRepository admins;
    private final AuthController auth;

    public AdminController(AdminRepository admins, AuthController auth) {
        this.admins = admins;
        this.auth = auth;
    }

    public void register(RoutesConfig routes) {
        routes.before("/api/admin/*", this::requireAdmin);
        routes.get("/api/admin/stats", this::handleStats);
        routes.get("/api/admin/users", this::handleSearchUsers);
        routes.put("/api/admin/users/{id}/ban", this::handleBanUser);
        routes.delete("/api/admin/users/{id}/ban", this::handleUnbanUser);
        routes.get("/api/admin/videos", this::handleSearchVideos);
        routes.get("/api/admin/banned-videos", this::handleBannedVideos);
        routes.put("/api/admin/banned-videos/{youtubeId}", this::handleBanVideo);
        routes.delete("/api/admin/banned-videos/{youtubeId}", this::handleUnbanVideo);
        routes.get("/api/admin/actions", this::handleActions);
    }

    /** 401 if not logged in, 403 if logged in but not an admin. Failed attempts are logged. */
    private void requireAdmin(Context ctx) {
        Optional<User> user = auth.currentUser(ctx);
        try {
            ctx.attribute(ADMIN_ATTRIBUTE, checkAdmin(user));
        } catch (ApiException e) {
            if (e.status() == 403) {
                SecurityLog.failure("admin_access", ctx, user.get().id(), "not an admin");
            }
            throw e;
        }
    }

    /** The access rule on its own, so it can be unit tested: 401 for guests, 403 for non-admins. */
    static User checkAdmin(Optional<User> user) {
        User found = user.orElseThrow(() -> ApiException.unauthorized("Not logged in."));
        if (!found.admin()) {
            throw ApiException.forbidden("Admins only.");
        }
        return found;
    }

    /** The admin that requireAdmin let through. Refuses if it somehow didn't run. */
    private static User admin(Context ctx) {
        User user = ctx.attribute(ADMIN_ATTRIBUTE);
        if (user == null || !user.admin()) {
            throw ApiException.forbidden("Admins only.");
        }
        return user;
    }

    private void handleStats(Context ctx) {
        admin(ctx);
        ctx.json(admins.stats());
    }

    /** GET /api/admin/users?q=name — matches username or email; no q lists the newest users. */
    private void handleSearchUsers(Context ctx) {
        admin(ctx);
        ctx.json(Map.of("users", admins.searchUsers(likePattern(ctx.queryParam("q")), SEARCH_LIMIT)));
    }

    private void handleBanUser(Context ctx) {
        User admin = admin(ctx);
        long userId = parseUserId(ctx.pathParam("id"));
        String reason = checkReason(RequestBodies.parse(ctx, BanRequest.class).reason());
        AdminRepository.AdminUser target = admins.findUser(userId)
                .orElseThrow(() -> ApiException.notFound("User not found."));
        checkCanBan(admin, target);
        if (!admins.banUser(admin.id(), ctx.ip(), userId, reason)) {
            throw ApiException.notFound("User not found.");
        }
        SecurityLog.success("admin_ban_user", ctx, admin.id(), "target=" + userId);
        ctx.status(204);
    }

    private void handleUnbanUser(Context ctx) {
        User admin = admin(ctx);
        long userId = parseUserId(ctx.pathParam("id"));
        if (!admins.unbanUser(admin.id(), ctx.ip(), userId)) {
            throw ApiException.notFound("That user isn't banned.");
        }
        SecurityLog.success("admin_unban_user", ctx, admin.id(), "target=" + userId);
        ctx.status(204);
    }

    /** GET /api/admin/videos?q=words — videos in the feed by title, channel or id. */
    private void handleSearchVideos(Context ctx) {
        admin(ctx);
        ctx.json(Map.of("videos", admins.searchVideos(likePattern(ctx.queryParam("q")), SEARCH_LIMIT)));
    }

    private void handleBannedVideos(Context ctx) {
        admin(ctx);
        ctx.json(Map.of("videos", admins.bannedVideos(LIST_LIMIT)));
    }

    private void handleBanVideo(Context ctx) {
        User admin = admin(ctx);
        String youtubeId = FeedController.checkYoutubeId(ctx.pathParam("youtubeId"));
        String reason = checkReason(RequestBodies.parse(ctx, BanRequest.class).reason());
        admins.banVideo(admin.id(), ctx.ip(), youtubeId, reason);
        SecurityLog.success("admin_ban_video", ctx, admin.id(), "target=" + youtubeId);
        ctx.status(204);
    }

    private void handleUnbanVideo(Context ctx) {
        User admin = admin(ctx);
        String youtubeId = FeedController.checkYoutubeId(ctx.pathParam("youtubeId"));
        if (!admins.unbanVideo(admin.id(), ctx.ip(), youtubeId)) {
            throw ApiException.notFound("That video isn't banned.");
        }
        SecurityLog.success("admin_unban_video", ctx, admin.id(), "target=" + youtubeId);
        ctx.status(204);
    }

    private void handleActions(Context ctx) {
        admin(ctx);
        ctx.json(Map.of("actions", admins.recentActions(SEARCH_LIMIT)));
    }

    /**
     * Admins can't ban themselves (they'd lock themselves out) or other admins (so one admin
     * can't lock out the rest). To ban an admin, remove their admin access in the database first.
     */
    static void checkCanBan(User admin, AdminRepository.AdminUser target) {
        if (target.id() == admin.id()) {
            throw ApiException.badRequest("You can't ban your own account.");
        }
        if (target.admin()) {
            throw ApiException.badRequest("Admins can't be banned. Remove their admin access first.");
        }
    }

    /** A ban reason is required, so the log always says why. Control characters are refused. */
    static String checkReason(String reason) {
        String trimmed = reason == null ? "" : reason.strip();
        if (trimmed.isEmpty()) {
            throw ApiException.badRequest("Please give a reason.");
        }
        if (trimmed.length() > MAX_REASON || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest("Reason must be at most " + MAX_REASON + " characters on one line.");
        }
        return trimmed;
    }

    static long parseUserId(String id) {
        try {
            long value = Long.parseLong(id);
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException e) {
            // falls through to the error below
        }
        throw ApiException.badRequest("Invalid user id.");
    }

    /**
     * Turns a search box value into an ILIKE pattern that matches it anywhere. %, _ and \ are
     * escaped so they're searched for literally instead of acting as wildcards.
     */
    static String likePattern(String search) {
        String trimmed = search == null ? "" : search.strip();
        if (trimmed.length() > MAX_SEARCH) {
            throw ApiException.badRequest("Search is too long.");
        }
        String escaped = trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
