package edu.cpsc488.brainfeed.export;

import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.admin.AdminRepository;
import edu.cpsc488.brainfeed.admin.AdminRepository.AdminAction;
import edu.cpsc488.brainfeed.auth.AuthController;
import edu.cpsc488.brainfeed.auth.SecurityLog;
import edu.cpsc488.brainfeed.auth.User;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * File downloads. For now: GET /api/admin/actions/export, the admin audit log as CSV.
 *
 * <p>The route sits under /api/admin/, so AdminController's before-handler already rejects
 * non-admins. This controller checks again anyway, so moving the route can't open it up.
 */
public class ExportController {

    private static final Logger log = LoggerFactory.getLogger(ExportController.class);

    /** Most actions in one export. Keeps the file small enough to build in one request. */
    static final int EXPORT_LIMIT = 10_000;

    private final Supplier<List<AdminAction>> loadActions;
    private final Function<Context, Optional<User>> currentUser;
    private final Path tempDirectory;

    public ExportController(AdminRepository admins, AuthController auth) {
        this(() -> admins.recentActions(EXPORT_LIMIT), auth::currentUser,
                Path.of(System.getProperty("java.io.tmpdir")));
    }

    ExportController(Supplier<List<AdminAction>> loadActions, Function<Context, Optional<User>> currentUser,
                     Path tempDirectory) {
        this.loadActions = loadActions;
        this.currentUser = currentUser;
        this.tempDirectory = tempDirectory;
    }

    public void register(RoutesConfig routes) {
        routes.get("/api/admin/actions/export", this::handleAuditLog);
    }

    private User requireAdmin(Context ctx) {
        User user = currentUser.apply(ctx).orElseThrow(() -> ApiException.unauthorized("Not logged in."));
        if (!user.admin()) {
            throw ApiException.forbidden("Admins only.");
        }
        return user;
    }

    void handleAuditLog(Context ctx) {
        User admin = requireAdmin(ctx);
        List<AdminAction> actions = loadActions.get();

        // Build the whole file first, then send it. If writing fails halfway, the admin gets a
        // clear error instead of a cut-off download that looks complete.
        Path file = null;
        try {
            file = AuditLogExport.writeTempFile(tempDirectory, actions);
            byte[] csv = Files.readAllBytes(file);
            ctx.contentType("text/csv; charset=utf-8");
            ctx.header("Content-Disposition",
                    "attachment; filename=\"" + AuditLogExport.fileName(LocalDate.now(ZoneOffset.UTC)) + "\"");
            ctx.result(csv);
        } catch (IOException e) {
            log.error("Couldn't build the audit log export", e);
            throw new ApiException(500, "Couldn't build the export file. Try again.");
        } finally {
            deleteQuietly(file);
        }
        // The export contains IP addresses, so who downloaded it is itself worth logging.
        SecurityLog.success("audit_log_export", ctx, admin.id(), actions.size() + " rows");
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // The download already worked; a leftover temp file is cleaned up when the container restarts.
            log.warn("Couldn't delete temp export file {}", file, e);
        }
    }
}
