package edu.cpsc488.brainfeed.learning;

import edu.cpsc488.brainfeed.ApiException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Learning rules shared by the API: progress totals and a seven-day review schedule. */
public class LearningProgressService {
    public static final int REVIEW_INTERVAL_DAYS = 7;
    private static final Set<String> STATUSES = Set.of("planned", "in_progress", "completed");
    private final LearningProgressRepository repository;
    private final Clock clock;

    public LearningProgressService(LearningProgressRepository repository) {
        this(repository, Clock.systemUTC());
    }

    LearningProgressService(LearningProgressRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public record Summary(int total, long planned, long inProgress, long completed, long dueForReview,
                          int completionPercent) {
    }

    public record Item(LearningProgress progress, OffsetDateTime reviewDueAt, boolean dueForReview) {
    }

    public record Snapshot(List<Item> items, Summary summary, int reviewIntervalDays) {
    }

    public Snapshot snapshot(long userId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Item> items = repository.list(userId).stream().map(progress -> toItem(progress, now))
                .sorted(Comparator.comparing(Item::dueForReview).reversed()
                        .thenComparing(Item::reviewDueAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(item -> item.progress().youtubeId()))
                .toList();
        return new Snapshot(items, summarize(items), REVIEW_INTERVAL_DAYS);
    }

    public void setStatus(long userId, String youtubeId, String status) {
        if (!repository.saveStatus(userId, youtubeId, validateStatus(status))) {
            throw ApiException.notFound("Save this video to your Study notebook first.");
        }
    }

    public void review(long userId, String youtubeId) {
        if (!repository.markReviewed(userId, youtubeId)) {
            // The same error for missing, unowned and unfinished videos avoids exposing ownership.
            throw ApiException.notFound("Only completed videos in your Study notebook can be reviewed.");
        }
    }

    static String validateStatus(String status) {
        if (status == null || !STATUSES.contains(status)) {
            throw ApiException.badRequest("Choose planned, in_progress, or completed.");
        }
        return status;
    }

    static OffsetDateTime reviewDueAt(LearningProgress progress) {
        if (!"completed".equals(progress.status())) {
            return null;
        }
        OffsetDateTime lastReview = progress.reviewedAt() != null ? progress.reviewedAt() : progress.completedAt();
        return lastReview == null ? null : lastReview.plusDays(REVIEW_INTERVAL_DAYS);
    }

    static Item toItem(LearningProgress progress, OffsetDateTime now) {
        OffsetDateTime due = reviewDueAt(progress);
        return new Item(progress, due, due != null && !due.isAfter(now));
    }

    static Summary summarize(List<Item> items) {
        long completed = countStatus(items, "completed");
        return new Summary(items.size(), countStatus(items, "planned"), countStatus(items, "in_progress"),
                completed, items.stream().filter(Item::dueForReview).count(),
                items.isEmpty() ? 0 : (int) Math.round(100.0 * completed / items.size()));
    }

    private static long countStatus(List<Item> items, String status) {
        return items.stream().filter(item -> item.progress().status().equals(status)).count();
    }
}
