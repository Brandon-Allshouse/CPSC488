package edu.cpsc488.brainfeed.study;

import edu.cpsc488.brainfeed.auth.LoginRateLimiter;
import edu.cpsc488.brainfeed.llm.Llm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes each video's summary and quiz with the LLM, then serves the saved copy to everyone after
 * that.
 *
 * <p>Running the model takes a CPU about 10 seconds per video, so waiting for it after pressing
 * play feels slow. To hide that, serving a feed page also lines up its first few videos at low
 * priority ({@link #prefetch}), so their quizzes are usually ready by the time someone gets to
 * them. Waiting videos are kept in a priority queue:
 * <ul>
 *   <li>Someone asking for a video ({@link #request}) goes ahead of prefetched ones. If it was
 *       already waiting as a prefetch, it's moved up instead of being added twice.</li>
 *   <li>Prefetched videos are sorted as if they'd been asked for {@link #PREFETCH_HANDICAP} later,
 *       so once one has waited that long it goes ahead of newer requests and can't starve.</li>
 *   <li>A prefetched video nobody asked for in {@link #PREFETCH_STALE_AFTER} is dropped when its
 *       turn comes: whoever was scrolling has moved on.</li>
 * </ul>
 *
 * <p>Anyone (even a guest) can cause this work, so it's kept in check (OWASP LLM10): one video at a
 * time on its own thread, at most {@link #MAX_QUEUE} waiting (and only {@link #MAX_PREFETCH_QUEUED}
 * of them prefetched), each video only once, and at most {@link #DAILY_LIMIT} a day (counted in
 * the database, so restarting doesn't reset it). Prefetching stops at {@link #PREFETCH_DAILY_LIMIT},
 * which leaves the rest of the day's budget for people who actually asked. One IP can start at
 * most {@link #NEW_PER_IP_PER_HOUR} new ones an hour by asking and {@link #PREFETCH_PER_IP_PER_HOUR}
 * by loading feed pages. Only videos already in our database can be written; saved ones are
 * always served.
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
    // Far more than someone watching videos starts in an hour, but it takes 10 IPs to use up the
    // whole day's DAILY_LIMIT in an hour instead of one.
    static final int NEW_PER_IP_PER_HOUR = 50;

    // How many of a feed page's videos to line up. At ~10 seconds each that's under a minute of
    // work, about the time it takes to watch the first few Shorts; the rest start on play.
    static final int PREFETCH_PER_PAGE = 5;
    // Prefetching can't fill the line: the other 12 places are for people who pressed play.
    static final int MAX_PREFETCH_QUEUED = 8;
    // Prefetching stops once this many were written in 24 hours, so guests scrolling can't use up
    // DAILY_LIMIT; the last 200 are only for videos someone asked for.
    static final int PREFETCH_DAILY_LIMIT = 300;
    // A person scrolling loads a page every few minutes at most. Separate from NEW_PER_IP_PER_HOUR,
    // so scrolling doesn't use up someone's requests.
    static final int PREFETCH_PER_IP_PER_HOUR = 30;
    // Prefetched videos wait behind requests made up to this much later (the "aging" rule).
    static final Duration PREFETCH_HANDICAP = Duration.ofMinutes(2);
    // Scrolling past ten videos takes a few minutes, so a prefetch this old is for a page nobody
    // is looking at any more.
    static final Duration PREFETCH_STALE_AFTER = Duration.ofMinutes(10);

    /** A video's YouTube description. Empty if there isn't one or YouTube can't be reached. */
    public interface Descriptions {
        Optional<String> describe(String youtubeId);
    }

    /** What {@link #request} found. Sent to the frontend in lowercase. */
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

    /** The status, plus the material when it's READY (null otherwise). */
    public record Result(Status status, StudyMaterial material) {
        static Result of(Status status) {
            return new Result(status, null);
        }
    }

    /**
     * One video waiting in line. Sorted by rank (when it was asked for, plus the handicap for a
     * prefetch), then by seq so videos from the same feed page keep their order. video is null
     * for a prefetch; it's looked up when its turn comes, in case it was banned meanwhile.
     */
    record Job(String youtubeId, StudyRepository.VideoInfo video, boolean prefetch, Instant queuedAt,
               Instant rank, long seq) {

        static final Comparator<Job> ORDER = Comparator.comparing(Job::rank).thenComparingLong(Job::seq);

        /** The same job, now asked for by someone, so it goes in line by when it was first queued. */
        Job requested(StudyRepository.VideoInfo info) {
            return new Job(youtubeId, info, false, queuedAt, queuedAt, seq);
        }
    }

    private final StudyRepository repository;
    private final Llm llm;
    private final Descriptions descriptions;
    private final Executor executor;
    private final Clock clock;
    // Guarded by itself. Every video waiting or being written, so each is only queued once.
    private final Map<String, Job> queued = new HashMap<>();
    // Guarded by queued. Only the ones still waiting, best first.
    private final PriorityQueue<Job> waiting = new PriorityQueue<>(Job.ORDER);
    private final AtomicLong nextSeq = new AtomicLong();
    private final Map<String, Instant> failedUntil = new ConcurrentHashMap<>();
    private volatile Instant pausedUntil = Instant.MIN;
    private final LoginRateLimiter newPerIp = new LoginRateLimiter(NEW_PER_IP_PER_HOUR, Duration.ofHours(1));
    private final LoginRateLimiter prefetchPerIp = new LoginRateLimiter(PREFETCH_PER_IP_PER_HOUR, Duration.ofHours(1));

    /**
     * llm is null when OLLAMA_URL isn't set; saved material is still served, nothing new is written.
     * The executor should run one task at a time: each task writes whichever video is first in
     * line when it starts, which is how a request gets ahead of prefetches queued before it.
     */
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

    /** Same as {@link #request(String, String)} without the per-IP limit. */
    public Result request(String youtubeId) {
        return request(youtubeId, null);
    }

    /**
     * Returns the saved material, or starts writing it in the background and says so.
     *
     * @param ip who is asking (the client IP), for the per-IP limit on new summaries. Null skips it.
     */
    public Result request(String youtubeId, String ip) {
        Optional<StudyMaterial> saved = repository.find(youtubeId);
        if (saved.isPresent()) {
            return new Result(Status.READY, saved.get());
        }
        Optional<StudyRepository.VideoInfo> video = repository.videoInfo(youtubeId);
        if (video.isEmpty()) {
            return Result.of(Status.NOT_FOUND);
        }
        if (moveUp(youtubeId, video.get())) {
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
        String ipKey = ip == null ? null : "ip:" + ip;
        if (ipKey != null && newPerIp.isLimited(ipKey)) {
            // BUSY, so the page tries again later instead of giving up on this video.
            return Result.of(Status.BUSY);
        }
        Status status = enqueue(new Job(youtubeId, video.get(), false, now, now, nextSeq.getAndIncrement()));
        if (status == Status.PENDING && ipKey != null) {
            newPerIp.record(ipKey);
        }
        return Result.of(status);
    }

    /**
     * Lines up summaries for the first {@link #PREFETCH_PER_PAGE} of these videos (a feed page,
     * in the order shown) at low priority. Skips ones already written, waiting or recently
     * failed, and stops at any of the prefetch limits. Never throws for a full queue or a limit;
     * it just does less.
     *
     * @param ip who loaded the page, for {@link #PREFETCH_PER_IP_PER_HOUR}. Null skips it.
     * @return how many videos were newly queued
     */
    public int prefetch(List<String> youtubeIds, String ip) {
        Instant now = clock.instant();
        if (llm == null || now.isBefore(pausedUntil) || youtubeIds == null || youtubeIds.isEmpty()) {
            return 0;
        }
        String ipKey = ip == null ? null : "prefetch:" + ip;
        Set<String> wanted = new LinkedHashSet<>();
        // Only the first few places on the page, even if some are already done. Otherwise
        // reloading the page would reach further down it each time.
        for (String id : youtubeIds.subList(0, Math.min(youtubeIds.size(), PREFETCH_PER_PAGE))) {
            Instant failed = failedUntil.get(id);
            if (!isQueued(id) && (failed == null || !now.isBefore(failed))) {
                wanted.add(id);
            }
        }
        if (wanted.isEmpty() || (ipKey != null && prefetchPerIp.isLimited(ipKey))) {
            return 0;
        }
        wanted.removeAll(repository.withMaterial(wanted));
        int written = wanted.isEmpty() ? 0 : repository.writtenInLastDay();
        int added = 0;
        for (String id : wanted) {
            if (ipKey != null && prefetchPerIp.isLimited(ipKey)) {
                break;
            }
            // counts what's already waiting too (this page's included), so a burst of pages
            // can't overshoot it
            if (written + queuedCount() >= PREFETCH_DAILY_LIMIT) {
                break;
            }
            Status status = enqueue(new Job(id, null, true, now, now.plus(PREFETCH_HANDICAP), nextSeq.getAndIncrement()));
            if (status != Status.PENDING) {
                // the line is full or the worker is gone; the rest wouldn't fit either
                break;
            }
            added++;
            if (ipKey != null) {
                prefetchPerIp.record(ipKey);
            }
        }
        return added;
    }

    /**
     * Loads the model in the background now, so the first person to ask doesn't wait for it.
     * Runs on the worker before any summary. If Ollama isn't up yet that's only logged: the app
     * still starts, and the model loads with the first summary instead.
     */
    public void warmUp() {
        if (llm == null) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    llm.warmUp();
                } catch (IOException e) {
                    log.warn("Couldn't load the model ahead of time ({}); the first summary will take longer",
                            e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    log.warn("Couldn't load the model ahead of time", e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Couldn't load the model ahead of time: the worker is shut down");
        }
    }

    /** True if it's already waiting or being written. A waiting prefetch is moved up to a request. */
    private boolean moveUp(String youtubeId, StudyRepository.VideoInfo video) {
        synchronized (queued) {
            Job job = queued.get(youtubeId);
            if (job == null) {
                return false;
            }
            if (job.prefetch() && waiting.remove(job)) {
                Job requested = job.requested(video);
                waiting.add(requested);
                queued.put(youtubeId, requested);
            }
            return true;
        }
    }

    private boolean isQueued(String youtubeId) {
        synchronized (queued) {
            return queued.containsKey(youtubeId);
        }
    }

    private int queuedCount() {
        synchronized (queued) {
            return queued.size();
        }
    }

    /** Puts the job in line and hands the worker one more turn. PENDING if it was added. */
    private Status enqueue(Job job) {
        // synchronized so two requests can't both see 19 waiting and make it 21
        synchronized (queued) {
            if (queued.containsKey(job.youtubeId())) {
                return Status.PENDING;
            }
            if (queued.size() >= MAX_QUEUE) {
                return Status.BUSY;
            }
            if (job.prefetch() && waiting.stream().filter(Job::prefetch).count() >= MAX_PREFETCH_QUEUED) {
                return Status.BUSY;
            }
            queued.put(job.youtubeId(), job);
            waiting.add(job);
        }
        failedUntil.remove(job.youtubeId());
        try {
            executor.execute(this::writeNext);
        } catch (RejectedExecutionException e) {
            // The worker is shutting down. Un-queue it, or it would say PENDING forever.
            synchronized (queued) {
                queued.remove(job.youtubeId());
                waiting.removeIf(waitingJob -> waitingJob.youtubeId().equals(job.youtubeId()));
            }
            log.warn("Couldn't queue study material for video {}: the worker is shut down", job.youtubeId());
            return Status.UNAVAILABLE;
        }
        return Status.PENDING;
    }

    /** One turn of the worker: writes whichever video is first in line right now. */
    private void writeNext() {
        Job job;
        synchronized (queued) {
            job = waiting.poll();
        }
        if (job == null) {
            return;
        }
        try {
            if (job.prefetch() && skipPrefetch(job)) {
                return;
            }
            StudyRepository.VideoInfo video = job.video() != null
                    ? job.video()
                    : repository.videoInfo(job.youtubeId()).orElse(null);
            if (video != null) {
                write(job, video);
            }
        } catch (RuntimeException e) {
            // a database error; don't let it kill the worker thread
            log.error("Writing study material for video {} failed", job.youtubeId(), e);
        } finally {
            synchronized (queued) {
                queued.remove(job.youtubeId());
            }
        }
    }

    // Checked again when its turn comes, since a prefetch can wait a while.
    private boolean skipPrefetch(Job job) {
        if (Duration.between(job.queuedAt(), clock.instant()).compareTo(PREFETCH_STALE_AFTER) > 0) {
            log.debug("Dropped a stale prefetch for video {}", job.youtubeId());
            return true;
        }
        return repository.writtenInLastDay() >= PREFETCH_DAILY_LIMIT;
    }

    private void write(Job job, StudyRepository.VideoInfo video) {
        String youtubeId = job.youtubeId();
        try {
            // Someone may have asked twice across a restart, or it was written while waiting.
            if (repository.find(youtubeId).isPresent()) {
                return;
            }
            if (clock.instant().isBefore(pausedUntil)) {
                return;
            }
            long start = System.nanoTime();
            String prompt = StudyPrompt.userMessage(video, descriptions.describe(youtubeId).orElse(""));
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                Optional<StudyMaterial> material = StudyParser.parse(
                        llm.chat(StudyPrompt.SYSTEM, prompt, StudyPrompt.SCHEMA, StudyPrompt.MAX_REPLY_TOKENS));
                if (material.isPresent()) {
                    repository.save(youtubeId, material.get(), llm.model());
                    log.info("Wrote study material for video {} in {} ms ({}, attempt {})", youtubeId,
                            (System.nanoTime() - start) / 1_000_000, job.prefetch() ? "prefetch" : "requested", attempt);
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
        }
    }
}
