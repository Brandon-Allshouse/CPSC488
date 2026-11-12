package edu.cpsc488.brainfeed.topics;

import edu.cpsc488.brainfeed.llm.Llm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Looks at each typed topic after it's been added, on a background thread, so adding stays
 * instant even though the model takes 10-30 seconds. The model:
 * <ul>
 *   <li>flags harmful topics: those are removed and their names blocked, like an admin removal,
 *       and logged in admin_actions and the security log</li>
 *   <li>picks a better subject than {@link SubjectGuesser}'s quick guess, moving the topic</li>
 *   <li>writes a better YouTube search than "name explained"</li>
 * </ul>
 * Topics wait in the database (reviewed_at is NULL), so a restart doesn't skip any:
 * {@link #queueWaiting()} runs at startup. Without Ollama nothing is reviewed and topics stay
 * where they were put; the denylist and admins still apply.
 */
public class TopicReviewer {

    private static final Logger log = LoggerFactory.getLogger(TopicReviewer.class);
    private static final Logger security = LoggerFactory.getLogger("security");

    // How many waiting topics a restart picks up at once; the rest wait for the next restart.
    static final int QUEUE_AT_STARTUP = 200;

    private final AiTopicRepository repository;
    private final Llm llm;
    // One thread, so only one review talks to the model at a time.
    private final Executor executor;
    private final AiTopicRepository.Limits limits;
    // so the same topic isn't queued twice (a restart scan racing a new add)
    private final Set<Integer> queued = ConcurrentHashMap.newKeySet();

    /** llm is null when OLLAMA_URL isn't set; nothing is reviewed then. */
    public TopicReviewer(AiTopicRepository repository, Llm llm, Executor executor) {
        this(repository, llm, executor, TopicFinder.LIMITS);
    }

    TopicReviewer(AiTopicRepository repository, Llm llm, Executor executor, AiTopicRepository.Limits limits) {
        this.repository = repository;
        this.llm = llm;
        this.executor = executor;
        this.limits = limits;
    }

    /** Queues every topic that's still waiting for a review, e.g. ones added before a restart. */
    public void queueWaiting() {
        if (llm == null) {
            return;
        }
        try {
            repository.unreviewed(QUEUE_AT_STARTUP).forEach(topic -> queue(topic.id()));
        } catch (RuntimeException e) {
            log.error("Couldn't load topics waiting for review", e);
        }
    }

    /** Queues one new topic for review. Returns right away. */
    void queue(int topicId) {
        if (llm == null || !queued.add(topicId)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    review(topicId);
                } finally {
                    queued.remove(topicId);
                }
            });
        } catch (RejectedExecutionException e) {
            // Shutting down. It stays unreviewed in the database and is picked up next start.
            queued.remove(topicId);
        }
    }

    /** Reviews one topic now, on this thread. Package-private so tests can run it directly. */
    void review(int topicId) {
        // Catch everything: this runs on a worker thread, where an exception would only be lost.
        try {
            Optional<AiTopicRepository.Pending> pending = repository.pending(topicId);
            if (pending.isEmpty()) {
                return;
            }
            AiTopicRepository.Pending topic = pending.get();
            Optional<TopicParser.Review> review = ask(topic.name());
            if (review.isEmpty()) {
                // Two bad replies in a row. Leave the topic as it is rather than retry forever.
                repository.markReviewed(topicId);
                return;
            }
            if (review.get().harmful()) {
                if (repository.removeFlagged(topicId, "Removed automatically: the AI review flagged it as harmful.")) {
                    security.warn("event=topic_flagged outcome=removed user={} detail=\"topic={}\"",
                            topic.createdBy() == null ? "-" : topic.createdBy(), topicId);
                }
                return;
            }
            repository.place(topicId, new AiTopicRepository.Placement(review.get().subject(),
                    review.get().searchQuery()), limits);
        } catch (IOException e) {
            // Ollama is down or slow. It stays unreviewed and is tried again at the next start.
            log.warn("Topic review failed for topic {}: {}", topicId, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("Topic review failed for topic {}", topicId, e);
        }
    }

    // one more try if the reply wasn't valid JSON; small models occasionally slip
    private Optional<TopicParser.Review> ask(String name) throws IOException, InterruptedException {
        String prompt = TopicPrompt.userMessage(name, repository.catalog());
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<TopicParser.Review> review = TopicParser.parse(llm.chat(TopicPrompt.SYSTEM, prompt, TopicPrompt.SCHEMA));
            if (review.isPresent()) {
                return review;
            }
        }
        return Optional.empty();
    }
}
