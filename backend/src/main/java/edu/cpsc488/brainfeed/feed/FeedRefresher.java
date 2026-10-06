package edu.cpsc488.brainfeed.feed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Fills the videos table from YouTube, in two ways:
 * <ul>
 *   <li>{@link #run()}: App.java runs this when the backend starts and then once an hour. It
 *       re-searches a few topics that haven't been refreshed in a week, picked topics first.</li>
 *   <li>{@link #fetchMissing}: when someone opens the feed for topics that have never been
 *       searched, those get searched right away, so a new pick doesn't sit empty for hours.</li>
 * </ul>
 * Each search costs 100 of our 10,000 free daily quota units, and anyone (even a guest) can open
 * the feed, so both paths share a daily budget. The budget is counted from the database, so
 * restarting the backend doesn't reset it.
 */
public class FeedRefresher implements Runnable, FeedController.MissingVideoFetcher {

    private static final Logger log = LoggerFactory.getLogger(FeedRefresher.class);
    static final Duration REFRESH_EVERY = Duration.ofDays(7);
    static final int MAX_SEARCHES_PER_RUN = 3;
    // 8,000 of the 10,000 daily units, so there's room left for testing and mistakes
    static final int DAILY_SEARCH_BUDGET = 80;
    // so one feed request for 100 topics can't spend the whole budget at once
    static final int MAX_FETCHES_PER_REQUEST = 5;
    // after YouTube errors (usually quota or a bad key), stop retrying on every feed request
    static final Duration PAUSE_AFTER_ERROR = Duration.ofMinutes(15);

    private final TopicRepository topics;
    private final VideoRepository videos;
    private final YouTubeClient youtube;
    // App passes the same single thread the hourly run uses, so two searches never run at once
    // and the budget check can't race.
    private final Executor executor;
    private final Clock clock;
    // topics already waiting to be fetched, so polling the feed doesn't queue them again
    private final Set<Integer> queued = ConcurrentHashMap.newKeySet();
    private volatile Instant pausedUntil = Instant.MIN;

    public FeedRefresher(TopicRepository topics, VideoRepository videos, YouTubeClient youtube, Executor executor) {
        this(topics, videos, youtube, executor, Clock.systemUTC());
    }

    FeedRefresher(TopicRepository topics, VideoRepository videos, YouTubeClient youtube, Executor executor, Clock clock) {
        this.topics = topics;
        this.videos = videos;
        this.youtube = youtube;
        this.executor = executor;
        this.clock = clock;
    }

    @Override
    public void run() {
        // Catch everything here. If a scheduled task throws, Java quietly stops running it.
        try {
            List<TopicRepository.SearchTopic> due = topics.dueForRefresh(REFRESH_EVERY);
            int allowed = Math.min(MAX_SEARCHES_PER_RUN, remainingBudget());
            search(due.subList(0, Math.max(0, Math.min(due.size(), allowed))));
        } catch (RuntimeException e) {
            log.error("Video refresh failed", e);
        }
    }

    /**
     * Starts searching (in the background) any of these topics that have never been searched.
     * Returns true if some of them are being fetched, so the feed can say "finding videos" and
     * check again shortly. Returns false when there's nothing to fetch, the daily budget is used
     * up, or YouTube recently failed.
     */
    @Override
    public boolean fetchMissing(Collection<Integer> topicIds) {
        if (clock.instant().isBefore(pausedUntil)) {
            return false;
        }
        List<TopicRepository.SearchTopic> missing = topics.neverFetched(topicIds);
        if (missing.isEmpty() || remainingBudget() <= 0) {
            return false;
        }
        boolean alreadyQueued = false;
        List<TopicRepository.SearchTopic> batch = new ArrayList<>();
        for (TopicRepository.SearchTopic topic : missing) {
            if (queued.contains(topic.id())) {
                alreadyQueued = true;
            } else if (batch.size() < MAX_FETCHES_PER_REQUEST && queued.add(topic.id())) {
                batch.add(topic);
            }
        }
        if (!batch.isEmpty()) {
            executor.execute(() -> fetchBatch(batch));
        }
        return alreadyQueued || !batch.isEmpty();
    }

    private void fetchBatch(List<TopicRepository.SearchTopic> batch) {
        try {
            // The hourly run may have searched some of these while they waited in line.
            List<Integer> ids = batch.stream().map(TopicRepository.SearchTopic::id).toList();
            List<TopicRepository.SearchTopic> stillMissing = topics.neverFetched(ids);
            int allowed = Math.min(stillMissing.size(), remainingBudget());
            search(stillMissing.subList(0, Math.max(0, allowed)));
        } catch (RuntimeException e) {
            log.error("Fetching videos for new topics failed", e);
        } finally {
            batch.forEach(topic -> queued.remove(topic.id()));
        }
    }

    /** Searches each topic in order, and stops at the first YouTube error. */
    private void search(List<TopicRepository.SearchTopic> toSearch) {
        try {
            for (TopicRepository.SearchTopic topic : toSearch) {
                List<Video> found = youtube.search(topic.id(), topic.searchQuery());
                videos.saveAll(found);
                topics.markFetched(topic.id());
                log.info("Fetched {} videos for topic {}", found.size(), topic.id());
            }
        } catch (IOException e) {
            // Usually means we're out of quota or the key is wrong. The hourly run tries again.
            pausedUntil = clock.instant().plus(PAUSE_AFTER_ERROR);
            log.warn("Video refresh stopped: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Every successful search marks its topic, so topics marked in the last day = searches used.
    private int remainingBudget() {
        return DAILY_SEARCH_BUDGET - topics.searchesInLastDay();
    }
}
