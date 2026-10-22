package edu.cpsc488.brainfeed.feed;

import java.util.List;

/**
 * One of a user's playlists, as sent to the frontend. {@code watchLater} is true for the built-in
 * "Watch later" playlist, which can't be deleted. Videos are newest-added first.
 */
public record Playlist(long id, String name, boolean watchLater, List<SavedVideo> videos) {
}
