package edu.cpsc488.brainfeed.feed;

/**
 * A topic users can pick as an interest. Subjects (Math) have a null {@code parentId};
 * sub-subjects (Algebra 2) have their subject's id. The YouTube search query stays server-side.
 */
public record Topic(int id, String name, Integer parentId) {
}
