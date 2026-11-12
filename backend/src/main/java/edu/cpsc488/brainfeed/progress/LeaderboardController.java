package edu.cpsc488.brainfeed.progress;

import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.auth.AuthController;
import edu.cpsc488.brainfeed.auth.LoginRateLimiter;
import edu.cpsc488.brainfeed.auth.SecurityLog;
import edu.cpsc488.brainfeed.auth.User;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * GET /api/leaderboard: the top 20 for this week and for all time, plus the caller's own row
 * (see the README's "How scoring works").
 *
 * <p>Public, so guests can see what they'd be playing for. It only ever shows usernames, points,
 * correct answers and streaks: never emails, IPs or user ids. The caller's own row comes from the
 * session cookie, not from anything in the request.
 */
public class LeaderboardController {

    // Each request runs two ranking queries over every answer, so one IP can't keep the database
    // busy with it. Refreshing by hand never gets near this.
    static final int PER_IP_PER_MINUTE = 30;

    private final LeaderboardRepository repository;
    private final AuthController auth;
    private final Clock clock;
    private final LoginRateLimiter perIp = new LoginRateLimiter(PER_IP_PER_MINUTE, Duration.ofMinutes(1));

    public LeaderboardController(LeaderboardRepository repository, AuthController auth) {
        this(repository, auth, Clock.systemUTC());
    }

    LeaderboardController(LeaderboardRepository repository, AuthController auth, Clock clock) {
        this.repository = repository;
        this.auth = auth;
        this.clock = clock;
    }

    /** Adds the leaderboard route. */
    public void register(RoutesConfig routes) {
        routes.get("/api/leaderboard", this::handleGet);
    }

    private void handleGet(Context ctx) {
        String ipKey = "ip:" + ctx.ip();
        if (perIp.isLimited(ipKey)) {
            SecurityLog.failure("leaderboard_rate_limited", ctx, null, "too many leaderboard requests");
            throw ApiException.tooManyRequests("Too many requests. Wait a minute and try again.");
        }
        perIp.record(ipKey);

        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Instant weekStart = LeaderboardRepository.weekStart(now);
        Optional<User> user = auth.currentUser(ctx);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("weekStart", weekStart.toString());
        body.put("week", board(weekStart, today, user));
        body.put("allTime", board(Instant.EPOCH, today, user));
        ctx.json(body);
    }

    private Map<String, Object> board(Instant since, LocalDate today, Optional<User> user) {
        Map<String, Object> board = new LinkedHashMap<>();
        board.put("top", repository.top(since, today));
        // null for guests; a signed-in user with no points gets rank null
        board.put("you", user.flatMap(u -> repository.forUser(u.id(), since, today)).orElse(null));
        return board;
    }
}
