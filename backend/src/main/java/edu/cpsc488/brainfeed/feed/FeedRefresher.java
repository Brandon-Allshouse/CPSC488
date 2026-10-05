package edu.cpsc488.brainfeed.feed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Fills the videos table from YouTube. App.java runs this when the backend starts and then once an
 * hour. It only searches topics that haven't been refreshed in the last day, so 10 topics use about
 * 1,000 of the 10,000 daily quota units no matter how many people use the app or how often the
 * backend restarts.
 */
public class FeedRefresher implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(FeedRefresher.class);
    static final Duration REFRESH_EVERY = Duration.ofDays(1);

    private final TopicRepository topics;
    private final VideoRepository videos;
    private final YouTubeClient youtube;

    public FeedRefresher(TopicRepository topics, VideoRepository videos, YouTubeClient youtube) {
        this.topics = topics;
        this.videos = videos;
        this.youtube = youtube;
    }

    @Override
    public void run() {
        // Catch everything here. If a scheduled task throws, Java quietly stops running it.
        try {
            List<TopicRepository.SearchTopic> due = topics.dueForRefresh(REFRESH_EVERY);
            for (TopicRepository.SearchTopic topic : due) {
                List<Video> found = youtube.search(topic.id(), topic.searchQuery());
                videos.saveAll(found);
                topics.markFetched(topic.id());
                log.info("Fetched {} videos for topic {}", found.size(), topic.id());
            }
        } catch (IOException e) {
            // Usually means we're out of quota or the key is wrong. Try again next hour.
            log.warn("Video refresh stopped: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("Video refresh failed", e);
        }
    }
}
