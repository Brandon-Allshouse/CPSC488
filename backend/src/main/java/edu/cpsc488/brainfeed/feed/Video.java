package edu.cpsc488.brainfeed.feed;

import java.time.OffsetDateTime;

/**
 * A YouTube video in the feed. {@code title} and {@code channelTitle} come from YouTube, so they're
 * untrusted: the frontend shows them as plain text only.
 */
public record Video(String youtubeId, String title, String channelTitle, int topicId, OffsetDateTime publishedAt) {
}
