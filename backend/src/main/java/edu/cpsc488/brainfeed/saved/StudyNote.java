package edu.cpsc488.brainfeed.saved;

import java.time.OffsetDateTime;

/** Private note data returned only to the signed-in owner. */
public record StudyNote(String youtubeId, String note, OffsetDateTime updatedAt) {
}
