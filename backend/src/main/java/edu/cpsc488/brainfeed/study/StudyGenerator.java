package edu.cpsc488.brainfeed.study;

import edu.cpsc488.brainfeed.llm.Llm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Writes each video's summary and quiz with the LLM, the first time someone asks for it, then
 * serves the saved copy to everyone after that.
 *
 * <p>Running the model takes a CPU 10-60 seconds per video, and anyone (even a guest) can ask, so
 * the work is kept in check: one video at a time on its own thread, at most {@link #MAX_QUEUE}
 * waiting, each video only once, and at most {@link #DAILY_LIMIT} a day (counted in the database,
 * so restarting doesn't reset it). Only videos already in our database can be asked for.
 */
public class StudyGenerator {

    private static final Logger log = LoggerFactory.getLogger(StudyGenerator.class);

    // How many videos can wait in line. Past this, people are told to try again shortly.
    static final int MAX_QUEUE = 20;
    // Keeps a busy day from tying up the machine. Also caps the YouTube quota spent on
    // descriptions (1 unit each, out of the ~2,000 the feed leaves free).
    static final int DAILY_LIMIT = 500;
    // Small models sometimes write a bad answer key; one more try usually fixes it.
    static final int ATTEMPTS = 2;
    // If Ollama is down or the model is still downloading, don't keep queueing work for it.
    static final Duration PAUSE_AFTER_ERROR = Duration.ofMinutes(1);
    // A video the model failed on twice probably has too little to go on. Try again much later.
    static final Duration RETRY_FAILED_AFTER = Duration.ofHours(6);

    /** A video's YouTube description. Empty if there isn't one or YouTube can't be reached. */
    public interface Descriptions {
        Optional<String> describe(String youtubeId);
    }

    public enum Status {
        /** material is filled in */
        READY,
        /** being written now; ask again in a few seconds */
        PENDING,
        /** too many videos waiting; ask again in a minute */
        BUSY,
        /** the LLM is off, down, over its daily limit, or failed on this video */
        UNAVAILABLE,
        /** the video isn't in our database */
        NOT_FOUND
    }

    public record Result(Status status, StudyMaterial material) {
        static Result of(Status status) {
            return new Result(status, null);
        }
    }

    private final StudyRepository repository;
    private final Llm llm;
    private final Descriptions descriptions;
    private final Executor executor;
    private final Clock clock;
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> failedUntil = new ConcurrentHashMap<>();
    private volatile Instant pausedUntil = Instant.MIN;

    /** llm is null when OLLAMA_URL isn't set; saved material is still served, nothing new is written. */
    public StudyGenerator(StudyRepository repository, Llm llm, Descriptions descriptions, Executor executor) {
        this(repository, llm, descriptions, executor, Clock.systemUTC());
    }

    StudyGenerator(StudyRepository repository, Llm llm, Descriptions descriptions, Executor executor, Clock clock) {
        this.repository = repository;
        this.llm = llm;
        this.descriptions = descriptions;
        this.executor = executor;
        this.clock = clock;
    }

    /** Returns the saved material, or starts writing it in the background and says so. */
    public Result request(String youtubeId) {
        Optional<StudyMaterial> saved = repository.find(youtubeId);
        if (saved.isPresent()) {
            return new Result(Status.READY, saved.get());
        }
        Optional<StudyRepository.VideoInfo> video = repository.videoInfo(youtubeId);
        if (video.isEmpty()) {
            return Result.of(Status.NOT_FOUND);
        }
        if (queued.contains(youtubeId)) {
            return Result.of(Status.PENDING);
        }
        Instant now = clock.instant();
        Instant failed = failedUntil.get(youtubeId);
        if (failed != null && now.isBefore(failed)) {
            return Result.of(Status.UNAVAILABLE);
        }
        if (llm == null || now.isBefore(pausedUntil) || repository.writtenInLastDay() >= DAILY_LIMIT) {
            return Result.of(Status.UNAVAILABLE);
        }
        // synchronized so two requests can't both see 19 waiting and make it 21
        synchronized (queued) {
            if (queued.contains(youtubeId)) {
                return Result.of(Status.PENDING);
            }
            if (queued.size() >= MAX_QUEUE) {
                return Result.of(Status.BUSY);
            }
            queued.add(youtubeId);
        }
        failedUntil.remove(youtubeId);
        executor.execute(() -> write(youtubeId, video.get()));
        return Result.of(Status.PENDING);
    }

    private void write(String youtubeId, StudyRepository.VideoInfo video) {
        try {
            // Someone may have asked twice across a restart, or it was written while waiting.
            if (repository.find(youtubeId).isPresent()) {
                return;
            }
            if (clock.instant().isBefore(pausedUntil)) {
                return;
            }
            String prompt = StudyPrompt.userMessage(video, descriptions.describe(youtubeId).orElse(""));
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                Optional<StudyMaterial> material = StudyParser.parse(llm.chat(StudyPrompt.SYSTEM, prompt, StudyPrompt.SCHEMA));
                if (material.isPresent()) {
                    repository.save(youtubeId, material.get(), llm.model());
                    log.info("Wrote study material for video {}", youtubeId);
                    return;
                }
            }
            failedUntil.put(youtubeId, clock.instant().plus(RETRY_FAILED_AFTER));
            log.warn("The model didn't write usable study material for video {}", youtubeId);
        } catch (IOException e) {
            pausedUntil = clock.instant().plus(PAUSE_AFTER_ERROR);
            log.warn("Study material paused: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // a database error; don't let it kill the worker thread
            log.error("Writing study material for video {} failed", youtubeId, e);
        } finally {
            queued.remove(youtubeId);
        }
    }
}
