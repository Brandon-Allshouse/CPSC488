package edu.cpsc488.brainfeed.feed;

/** The video details shown in a user's private liked/disliked lists. */
public record SavedVideo(String youtubeId, String title, String creator) {
}
