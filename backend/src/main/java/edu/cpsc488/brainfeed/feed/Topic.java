package edu.cpsc488.brainfeed.feed;

/**
 * A topic users can pick as an interest. Subjects (Math) have a null {@code parentId};
 * sub-subjects (Algebra 2) have their subject's id. {@code ai} is true for topics a user added
 * with the AI topic finder; the frontend marks those. The YouTube search query stays server-side,
 * and so does who added it.
 */
public record Topic(int id, String name, Integer parentId, boolean ai) {
}
