package edu.cpsc488.brainfeed.progress;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cpsc488.brainfeed.ApiServer;
import edu.cpsc488.brainfeed.auth.AuthController;
import edu.cpsc488.brainfeed.auth.PasswordHasher;
import edu.cpsc488.brainfeed.auth.PasswordPolicy;
import edu.cpsc488.brainfeed.auth.SessionRepository;
import edu.cpsc488.brainfeed.auth.UserRepository;
import edu.cpsc488.brainfeed.db.TestDatabase;
import edu.cpsc488.brainfeed.study.StudyController;
import edu.cpsc488.brainfeed.study.StudyGenerator;
import edu.cpsc488.brainfeed.study.StudyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Answering quizzes and the leaderboard over real HTTP, wired like App.java against a real database.
class QuizApiTest {

    static final String VIDEO = "video000001";
    static final String REGISTER = "{\"email\":\"test@sru.edu\",\"username\":\"testuser\",\"password\":\"quiet lemon river walk\"}";
    static final String RIGHT = "{\"question\":0,\"choice\":1}";
    static final String WRONG = "{\"question\":0,\"choice\":2}";

    ApiServer server;

    /** Two questions: the answers are option 1, then option 0. */
    static void addQuiz(String youtubeId) {
        TestDatabase.exec("""
                INSERT INTO video_study (youtube_id, summary, questions, model) VALUES (?, 'Plants make food.', CAST(? AS jsonb), 'test')
                """, youtubeId, """
                [{"question":"What do plants use?","options":["Sand","Sunlight","Salt","Rocks"],"answerIndex":1,"explanation":"Photosynthesis uses sunlight."},
                 {"question":"Where does it happen?","options":["Leaves","Roots","Seeds","Soil"],"answerIndex":0,"explanation":"Leaves have chloroplasts."}]
                """);
    }

    @BeforeEach
    void setUp() {
        TestDatabase.reset();
        var data = TestDatabase.get();
        AuthController auth = new AuthController(new UserRepository(data),
                new SessionRepository(data, Duration.ofDays(7), Duration.ofHours(24)),
                new PasswordHasher(new byte[32], 2), new PasswordPolicy(password -> false), false);
        StudyRepository studyRepository = new StudyRepository(data);
        // no LLM: the quiz is put in by hand
        StudyGenerator generator = new StudyGenerator(studyRepository, null, id -> Optional.empty(), Runnable::run);
        StudyController study = new StudyController(generator, studyRepository, auth, new QuizAttemptRepository(data));
        LeaderboardController leaderboard = new LeaderboardController(new LeaderboardRepository(data), auth);
        server = new ApiServer(routes -> {
            auth.register(routes);
            study.register(routes);
            leaderboard.register(routes);
        });
        TestDatabase.addVideo(VIDEO, TestDatabase.topicId("Math"));
        addQuiz(VIDEO);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> answer(String json) {
        return server.send("POST", "/api/videos/" + VIDEO + "/study/answers", json);
    }

    @Test
    void theQuizNoLongerGivesAwayTheAnswers() {
        HttpResponse<String> study = server.get("/api/videos/" + VIDEO + "/study");
        assertEquals(200, study.statusCode());
        assertFalse(study.body().contains("answerIndex"));
        assertFalse(study.body().contains("Photosynthesis uses sunlight."));
        assertEquals(4, server.json(study).get("questions").get(0).get("options").size());
    }

    @Test
    void aRightFirstAnswerEarnsTenPoints() {
        server.send("POST", "/api/auth/register", REGISTER);
        JsonNode body = server.json(answer(RIGHT));
        assertTrue(body.get("correct").asBoolean());
        assertTrue(body.get("signedIn").asBoolean());
        assertTrue(body.get("counted").asBoolean());
        assertEquals(QuizAttemptRepository.POINTS_PER_CORRECT_ANSWER, body.get("pointsEarned").asInt());
        assertEquals(1, body.get("streak").asInt());
        assertEquals("Photosynthesis uses sunlight.", body.get("explanation").asText());
    }

    @Test
    void answeringAgainDoesNotCountTwice() {
        server.send("POST", "/api/auth/register", REGISTER);
        answer(WRONG);
        // a retake with the right answer still shows it's right, but earns nothing
        JsonNode retake = server.json(answer(RIGHT));
        assertTrue(retake.get("correct").asBoolean());
        assertFalse(retake.get("counted").asBoolean());
        assertEquals(0, retake.get("pointsEarned").asInt());
        assertEquals(0, TestDatabase.number("SELECT coalesce(sum(points), 0) FROM quiz_attempts"));
        assertEquals(1, TestDatabase.number("SELECT count(*) FROM quiz_attempts"));
    }

    @Test
    void guestsCheckAnswersButNothingIsSaved() {
        JsonNode body = server.json(answer(RIGHT));
        assertTrue(body.get("correct").asBoolean());
        assertFalse(body.get("signedIn").asBoolean());
        assertFalse(body.get("counted").asBoolean());
        assertTrue(body.get("streak").isNull());
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM quiz_attempts"));
    }

    @Test
    void manyFirstAnswersAtOnceCountOnce() throws Exception {
        server.send("POST", "/api/auth/register", REGISTER);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Future<HttpResponse<String>>> sent = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                sent.add(pool.submit(() -> answer(RIGHT)));
            }
            int counted = 0;
            for (Future<HttpResponse<String>> response : sent) {
                assertEquals(200, response.get().statusCode());
                if (server.json(response.get()).get("counted").asBoolean()) {
                    counted++;
                }
            }
            assertEquals(1, counted);
        } finally {
            pool.shutdown();
        }
        assertEquals(10, TestDatabase.number("SELECT sum(points) FROM quiz_attempts"));
    }

    @Test
    void badInputIs400NeverA500() {
        server.send("POST", "/api/auth/register", REGISTER);
        for (String bad : List.of("{\"question\":2,\"choice\":0}", "{\"question\":0,\"choice\":4}", "{}",
                "{\"question\":0,\"choice\":1,\"userId\":1}", "{\"question\":0,\"choice\":1,\"points\":1000}",
                "{\"question\":true,\"choice\":1}", "{\"question\":0,\"choice\":\"1\"}", "", "{")) {
            HttpResponse<String> response = answer(bad);
            assertEquals(400, response.statusCode(), bad);
            assertFalse(response.body().contains("SQL"), bad);
        }
        assertEquals(400, server.send("POST", "/api/videos/bad.id/study/answers", RIGHT).statusCode());
        assertEquals(404, server.send("POST", "/api/videos/video000009/study/answers", RIGHT).statusCode());
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM quiz_attempts"));
    }

    @Test
    void oneUserCantSendEndlessAnswers() {
        server.send("POST", "/api/auth/register", REGISTER);
        for (int i = 0; i < 30; i++) {
            answer(WRONG);
        }
        assertEquals(429, answer(RIGHT).statusCode());
        assertEquals(0, TestDatabase.number("SELECT coalesce(sum(points), 0) FROM quiz_attempts"));
    }

    @Test
    void answersNeedJsonFromOurSite() {
        HttpResponse<String> form = server.send(server.builder("/api/videos/" + VIDEO + "/study/answers")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("question=0&choice=1")));
        assertEquals(415, form.statusCode());
    }

    @Test
    void theLeaderboardShowsPointsAndYourRank() {
        server.send("POST", "/api/auth/register", REGISTER);
        answer(RIGHT);
        answer("{\"question\":1,\"choice\":0}");
        JsonNode board = server.json(server.get("/api/leaderboard"));
        for (String period : List.of("week", "allTime")) {
            JsonNode top = board.get(period).get("top");
            assertEquals(1, top.size());
            assertEquals("testuser", top.get(0).get("username").asText());
            assertEquals(20, top.get(0).get("points").asInt());
            assertEquals(2, top.get(0).get("correct").asInt());
            assertEquals(1, top.get(0).get("streak").asInt());
            assertEquals(1, top.get(0).get("rank").asInt());
            assertEquals(1, board.get(period).get("you").get("rank").asInt());
        }
        assertTrue(board.has("weekStart"));
    }

    @Test
    void theLeaderboardNeverShowsEmailsOrIds() {
        server.send("POST", "/api/auth/register", REGISTER);
        answer(RIGHT);
        server.clearCookies();
        HttpResponse<String> response = server.get("/api/leaderboard");
        assertEquals(200, response.statusCode());
        assertFalse(response.body().contains("@sru.edu"));
        assertFalse(response.body().contains("\"id\""));
        assertFalse(response.body().contains("userId"));
        // guests see the board but have no row of their own
        assertTrue(server.json(response).get("week").get("you").isNull());
    }

    @Test
    void someoneWithNoPointsIsUnranked() {
        server.send("POST", "/api/auth/register", REGISTER);
        JsonNode you = server.json(server.get("/api/leaderboard")).get("week").get("you");
        assertEquals("testuser", you.get("username").asText());
        assertTrue(you.get("rank").isNull());
        assertEquals(0, you.get("points").asInt());
    }

    @Test
    void bannedUsersDropOffTheBoard() {
        server.send("POST", "/api/auth/register", REGISTER);
        answer(RIGHT);
        TestDatabase.exec("UPDATE users SET banned_at = now(), ban_reason = 'cheating' WHERE username = 'testuser'");
        server.clearCookies();
        assertEquals(0, server.json(server.get("/api/leaderboard")).get("allTime").get("top").size());
    }

    @Test
    void banningAVideoKeepsThePointsButFreesTheQuestion() {
        server.send("POST", "/api/auth/register", REGISTER);
        answer(RIGHT);
        // what AdminRepository.banVideo does to the video row
        TestDatabase.exec("DELETE FROM videos WHERE youtube_id = ?", VIDEO);
        assertEquals(10, TestDatabase.number("SELECT sum(points) FROM quiz_attempts"));
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM quiz_attempts WHERE youtube_id IS NOT NULL"));
        assertEquals(404, answer(RIGHT).statusCode());

        // if it ever comes back with a new quiz, that's new questions to answer
        TestDatabase.addVideo(VIDEO, TestDatabase.topicId("Math"));
        addQuiz(VIDEO);
        assertTrue(server.json(answer(RIGHT)).get("counted").asBoolean());
        assertEquals(20, TestDatabase.number("SELECT sum(points) FROM quiz_attempts"));
    }
}
