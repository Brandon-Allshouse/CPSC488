package edu.cpsc488.brainfeed.learning;

import java.time.OffsetDateTime;

/** A notebook video and the session owner's self-reported learning state. */
public record LearningProgress(String youtubeId, String title, String creator, String status,
                               OffsetDateTime completedAt, OffsetDateTime reviewedAt,
                               OffsetDateTime updatedAt) {
}
