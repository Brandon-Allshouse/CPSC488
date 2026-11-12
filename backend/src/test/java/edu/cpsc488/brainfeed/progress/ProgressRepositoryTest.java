package edu.cpsc488.brainfeed.progress;

import edu.cpsc488.brainfeed.db.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// QuizAttemptRepository and LeaderboardRepository against a real Postgres, with a clock we can move.
class ProgressRepositoryTest {

    // a Wednesday
    static final Instant WEDNESDAY = Instant.parse("2026-11-11T15:00:00Z");

    /** A clock the test can move forward a day at a time. */
    static class MovableClock extends Clock {
        Instant now = WEDNESDAY;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void plusDays(int days) {
            now = now.plus(Duration.ofDays(days));
        }

        LocalDate today() {
            return LocalDate.ofInstant(now, ZoneOffset.UTC);
        }
    }

    MovableClock clock;
    QuizAttemptRepository attempts;
    LeaderboardRepository leaderboard;
    int math;

    @BeforeEach
    void setUp() {
        TestDatabase.reset();
        clock = new MovableClock();
        attempts = new QuizAttemptRepository(TestDatabase.get(), clock);
        leaderboard = new LeaderboardRepository(TestDatabase.get());
        math = TestDatabase.topicId("Math");
    }

    private String quiz(int n) {
        String id = String.format("video%06d", n);
        TestDatabase.addVideo(id, math);
        QuizApiTest.addQuiz(id);
        return id;
    }

    private long allTimePoints(long userId) {
        return leaderboard.forUser(userId, Instant.EPOCH, clock.today()).orElseThrow().points();
    }

    @Test
    void onlyTheFirstAnswerIsSaved() {
        long user = TestDatabase.addUser("testuser");
        String video = quiz(1);
        var first = attempts.record(user, video, 0, 2, false).orElseThrow();
        assertTrue(first.counted());
        assertEquals(0, first.pointsEarned());
        var second = attempts.record(user, video, 0, 1, true).orElseThrow();
        assertFalse(second.counted());
        assertEquals(0, second.pointsEarned());
        // the other question is separate
        assertEquals(10, attempts.record(user, video, 1, 0, true).orElseThrow().pointsEarned());
        assertEquals(10, allTimePoints(user));
    }

    @Test
    void answersForAQuizThatIsGoneSaveNothing() {
        long user = TestDatabase.addUser("testuser");
        assertTrue(attempts.record(user, "video000099", 0, 1, true).isEmpty());
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM quiz_attempts"));
    }

    @Test
    void parallelFirstAnswersCountOnce() throws Exception {
        long user = TestDatabase.addUser("testuser");
        String video = quiz(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<QuizAttemptRepository.Recorded>> results = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                results.add(pool.submit(() -> attempts.record(user, video, 0, 1, true).orElseThrow()));
            }
            int counted = 0;
            for (Future<QuizAttemptRepository.Recorded> result : results) {
                if (result.get().counted()) {
                    counted++;
                }
            }
            assertEquals(1, counted);
        } finally {
            pool.shutdown();
        }
        assertEquals(10, allTimePoints(user));
        assertEquals(1, TestDatabase.number("SELECT current_days FROM quiz_streaks WHERE user_id = ?", user));
    }

    @Test
    void theDatabaseRefusesMadeUpScores() {
        long user = TestDatabase.addUser("testuser");
        String video = quiz(1);
        // points for a wrong answer, and an option that doesn't exist
        assertThrows(RuntimeException.class, () -> TestDatabase.exec(
                "INSERT INTO quiz_attempts (user_id, youtube_id, question_index, choice, correct, points) VALUES (?, ?, 0, 1, false, 10)",
                user, video));
        assertThrows(RuntimeException.class, () -> TestDatabase.exec(
                "INSERT INTO quiz_attempts (user_id, youtube_id, question_index, choice, correct, points) VALUES (?, ?, 0, 7, true, 10)",
                user, video));
    }

    @Test
    void streakGrowsOnDaysInARowAndResetsAfterAGap() {
        long user = TestDatabase.addUser("testuser");
        assertEquals(1, attempts.record(user, quiz(1), 0, 1, true).orElseThrow().streak());
        // a second right answer the same day doesn't add a day
        assertEquals(1, attempts.record(user, quiz(2), 0, 1, true).orElseThrow().streak());
        clock.plusDays(1);
        assertEquals(2, attempts.record(user, quiz(3), 0, 1, true).orElseThrow().streak());
        clock.plusDays(1);
        // a wrong answer doesn't extend it, but it's still alive
        assertEquals(2, attempts.record(user, quiz(4), 0, 2, false).orElseThrow().streak());
        assertEquals(3, attempts.record(user, quiz(5), 0, 1, true).orElseThrow().streak());
        clock.plusDays(2);
        // missed a day: the board shows 0 until they answer, then it starts over
        assertEquals(0, leaderboard.forUser(user, Instant.EPOCH, clock.today()).orElseThrow().streak());
        assertEquals(1, attempts.record(user, quiz(6), 0, 1, true).orElseThrow().streak());
    }

    @Test
    void theWeekStartsMondayUtc() {
        assertEquals(Instant.parse("2026-11-09T00:00:00Z"), LeaderboardRepository.weekStart(WEDNESDAY));
        assertEquals(Instant.parse("2026-11-09T00:00:00Z"), LeaderboardRepository.weekStart(Instant.parse("2026-11-09T00:00:00Z")));
        assertEquals(Instant.parse("2026-11-09T00:00:00Z"), LeaderboardRepository.weekStart(Instant.parse("2026-11-15T23:59:59Z")));
    }

    @Test
    void thisWeekOnlyCountsPointsSinceMonday() {
        long user = TestDatabase.addUser("testuser");
        clock.now = Instant.parse("2026-11-06T12:00:00Z"); // last Friday
        attempts.record(user, quiz(1), 0, 1, true);
        clock.now = WEDNESDAY;
        attempts.record(user, quiz(2), 0, 1, true);
        Instant week = LeaderboardRepository.weekStart(WEDNESDAY);
        assertEquals(10, leaderboard.top(week, clock.today()).get(0).points());
        assertEquals(20, leaderboard.top(Instant.EPOCH, clock.today()).get(0).points());
    }

    @Test
    void tiesShareARankAndTheNextRankSkips() {
        long amy = TestDatabase.addUser("amy");
        long ben = TestDatabase.addUser("ben");
        long cal = TestDatabase.addUser("cal");
        long dan = TestDatabase.addUser("dan");
        String a = quiz(1);
        String b = quiz(2);
        attempts.record(cal, a, 0, 1, true);
        attempts.record(cal, b, 0, 1, true);
        attempts.record(ben, a, 0, 1, true);
        attempts.record(amy, a, 0, 1, true);
        attempts.record(dan, a, 0, 2, false);
        List<LeaderboardRepository.Entry> top = leaderboard.top(Instant.EPOCH, clock.today());
        // cal 20, then amy and ben tied on 10 (listed by name); dan has no points so isn't ranked
        assertEquals(List.of("cal", "amy", "ben"), top.stream().map(LeaderboardRepository.Entry::username).toList());
        assertEquals(List.of(1, 2, 2), top.stream().map(LeaderboardRepository.Entry::rank).toList());
        assertNull(leaderboard.forUser(dan, Instant.EPOCH, clock.today()).orElseThrow().rank());
    }

    @Test
    void theTopListHasTwentyAndYouCanStillSeeYourRank() {
        String video = quiz(1);
        String second = quiz(2);
        for (int i = 0; i < 21; i++) {
            long user = TestDatabase.addUser(String.format("student%02d", i));
            attempts.record(user, video, 0, 1, true);
            attempts.record(user, second, 0, 1, true);
        }
        long me = TestDatabase.addUser("testuser");
        attempts.record(me, video, 0, 1, true);
        assertEquals(LeaderboardRepository.TOP, leaderboard.top(Instant.EPOCH, clock.today()).size());
        LeaderboardRepository.Entry mine = leaderboard.forUser(me, Instant.EPOCH, clock.today()).orElseThrow();
        assertEquals(22, mine.rank());
        assertEquals(10, mine.points());
    }

    @Test
    void bannedUsersAreLeftOutBeforeRanking() {
        long cheater = TestDatabase.addUser("cheater");
        long me = TestDatabase.addUser("testuser");
        String a = quiz(1);
        String b = quiz(2);
        attempts.record(cheater, a, 0, 1, true);
        attempts.record(cheater, b, 0, 1, true);
        attempts.record(me, a, 0, 1, true);
        TestDatabase.exec("UPDATE users SET banned_at = now(), ban_reason = 'farming' WHERE id = ?", cheater);
        List<LeaderboardRepository.Entry> top = leaderboard.top(Instant.EPOCH, clock.today());
        assertEquals(1, top.size());
        assertEquals(1, top.get(0).rank());
        assertTrue(leaderboard.forUser(cheater, Instant.EPOCH, clock.today()).isEmpty());
    }

    @Test
    void deletingAnAccountDeletesItsScores() {
        long user = TestDatabase.addUser("testuser");
        attempts.record(user, quiz(1), 0, 1, true);
        TestDatabase.exec("DELETE FROM users WHERE id = ?", user);
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM quiz_attempts"));
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM quiz_streaks"));
    }
}
