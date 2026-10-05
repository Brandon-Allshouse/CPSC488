package edu.cpsc488.brainfeed.feed;

/** A topic users can pick as an interest. The YouTube search query stays server-side. */
public record Topic(int id, String name) {
}
