package edu.cpsc488.brainfeed.study;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.RequestBodies;
import edu.cpsc488.brainfeed.auth.AuthController;
import edu.cpsc488.brainfeed.auth.LoginRateLimiter;
import edu.cpsc488.brainfeed.auth.SecurityLog;
import edu.cpsc488.brainfeed.auth.User;
import edu.cpsc488.brainfeed.progress.QuizAttemptRepository;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A video's summary and quiz (see the README's "Study API" and "How scoring works").
 * <ul>
 *   <li>GET /api/videos/{youtubeId}/study: the summary and the questions, without the answers.</li>
 *   <li>POST /api/videos/{youtubeId}/study/answers: checks one answer here on the server and only
 *       then says which option was right. A signed-in user's first answer to each question is
 *       saved and scored; guests just find out if they were right.</li>
 * </ul>
 *
 * <p>Both are public, like the feed, so guests get summaries too. Asking for a summary that
 * doesn't exist yet starts writing it; {@link StudyGenerator} keeps that bounded.
 */
public class StudyController {

    private static final Pattern YOUTUBE_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    // Answering takes reading a question and clicking, so 30 a minute is far more than a person
    // does. It stops a script from hammering the endpoint. The IP limit is higher because a class
    // on one network shares an IP.
    static final int ANSWERS_PER_USER_PER_MINUTE = 30;
    static final int ANSWERS_PER_IP_PER_MINUTE = 120;
    // Matches StudyParser.MAX_QUESTIONS and StudyParser.OPTIONS (and the V14 CHECKs).
    private static final int MAX_QUESTIONS = 3;
    private static final int OPTIONS = 4;

    /** The answer body: {"question": 0-2, "choice": 0-3}. Read as JSON nodes so "1", 1.5 and null are refused. */
    record AnswerRequest(JsonNode question, JsonNode choice) {
    }

    private final StudyGenerator generator;
    private final StudyRepository repository;
    private final AuthController auth;
    private final QuizAttemptRepository attempts;
    private final LoginRateLimiter perUser = new LoginRateLimiter(ANSWERS_PER_USER_PER_MINUTE, Duration.ofMinutes(1));
    private final LoginRateLimiter perIp = new LoginRateLimiter(ANSWERS_PER_IP_PER_MINUTE, Duration.ofMinutes(1));

    public StudyController(StudyGenerator generator, StudyRepository repository, AuthController auth,
                           QuizAttemptRepository attempts) {
        this.generator = generator;
        this.repository = repository;
        this.auth = auth;
        this.attempts = attempts;
    }

    /** Adds the study routes. */
    public void register(RoutesConfig routes) {
        routes.get("/api/videos/{youtubeId}/study", this::handleGet);
        routes.post("/api/videos/{youtubeId}/study/answers", this::handleAnswer);
    }

    private void handleGet(Context ctx) {
        String youtubeId = youtubeId(ctx);
        StudyGenerator.Result result = generator.request(youtubeId, ctx.ip());
        if (result.status() == StudyGenerator.Status.NOT_FOUND) {
            throw ApiException.notFound("Video not found.");
        }
        ctx.json(body(result));
    }

    /**
     * The GET body. Questions leave out answerIndex and the explanation (SECURITY.md gap 15):
     * those only come back from the answers endpoint, after the answer is in.
     */
    static Map<String, Object> body(StudyGenerator.Result result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", result.status().name().toLowerCase(Locale.ROOT));
        if (result.material() != null) {
            body.put("summary", result.material().summary());
            body.put("questions", result.material().questions().stream()
                    .map(q -> Map.of("question", q.question(), "options", q.options()))
                    .toList());
        }
        return body;
    }

    private void handleAnswer(Context ctx) {
        Optional<User> user = auth.currentUser(ctx);
        limit(ctx, user);

        String youtubeId = youtubeId(ctx);
        AnswerRequest request = RequestBodies.parse(ctx, AnswerRequest.class);
        int questionIndex = smallInt(request.question(), MAX_QUESTIONS, "question");
        int choice = smallInt(request.choice(), OPTIONS, "choice");

        StudyMaterial material = repository.find(youtubeId)
                .orElseThrow(() -> ApiException.notFound("This video has no quiz yet."));
        List<StudyMaterial.Question> questions = material.questions();
        if (questionIndex >= questions.size()) {
            throw ApiException.badRequest("That question doesn't exist.");
        }
        StudyMaterial.Question question = questions.get(questionIndex);
        if (choice >= question.options().size()) {
            throw ApiException.badRequest("That option doesn't exist.");
        }
        boolean correct = choice == question.answerIndex();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("correct", correct);
        body.put("answerIndex", question.answerIndex());
        body.put("explanation", question.explanation());
        body.put("signedIn", user.isPresent());
        if (user.isEmpty()) {
            body.put("counted", false);
            body.put("pointsEarned", 0);
            body.put("streak", null);
        } else {
            QuizAttemptRepository.Recorded recorded = attempts
                    .record(user.get().id(), youtubeId, questionIndex, choice, correct)
                    .orElseThrow(() -> ApiException.notFound("This video has no quiz yet."));
            body.put("counted", recorded.counted());
            body.put("pointsEarned", recorded.pointsEarned());
            body.put("streak", recorded.streak());
        }
        ctx.json(body);
    }

    // Counted before the input is checked, so a flood of bad requests is limited too.
    private void limit(Context ctx, Optional<User> user) {
        String ipKey = "ip:" + ctx.ip();
        String userKey = user.map(u -> "user:" + u.id()).orElse(null);
        if (perIp.isLimited(ipKey) || (userKey != null && perUser.isLimited(userKey))) {
            SecurityLog.failure("quiz_answer_rate_limited", ctx, user.map(User::id).orElse(null),
                    "too many quiz answers");
            throw ApiException.tooManyRequests("You're answering very fast. Wait a minute and try again.");
        }
        perIp.record(ipKey);
        if (userKey != null) {
            perUser.record(userKey);
        }
    }

    private static String youtubeId(Context ctx) {
        String youtubeId = ctx.pathParam("youtubeId");
        if (!YOUTUBE_ID.matcher(youtubeId).matches()) {
            throw ApiException.badRequest("Invalid video id.");
        }
        return youtubeId;
    }

    /** A whole number from 0 to below {@code limit}. Strings, decimals, null and big numbers are a 400. */
    static int smallInt(JsonNode node, int limit, String name) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()
                || node.intValue() < 0 || node.intValue() >= limit) {
            throw ApiException.badRequest("Invalid " + name + ".");
        }
        return node.intValue();
    }
}
