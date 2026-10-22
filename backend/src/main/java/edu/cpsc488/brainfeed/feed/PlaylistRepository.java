package edu.cpsc488.brainfeed.feed;

import edu.cpsc488.brainfeed.ApiException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the {@code playlists} and {@code playlist_videos} tables (see
 * V9__playlists.sql). Every query includes the user's id, so one user can never see or change
 * another user's playlists, even with a guessed playlist id.
 */
public class PlaylistRepository {

    static final String WATCH_LATER = "Watch later";
    // Plenty for a person, and stops one account from filling the database.
    static final int MAX_PLAYLISTS = 50;
    static final int MAX_VIDEOS_PER_PLAYLIST = 500;

    private static final String UNIQUE_VIOLATION = "23505";

    private final DataSource dataSource;

    public PlaylistRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** All of the user's playlists with their videos: Watch later first, then by name. */
    public List<Playlist> list(long userId) {
        ensureWatchLater(userId);
        String sql = """
                SELECT p.id, p.name, p.watch_later, v.youtube_id, v.title, v.channel_title
                FROM playlists p
                LEFT JOIN playlist_videos pv ON pv.playlist_id = p.id
                LEFT JOIN videos v ON v.youtube_id = pv.youtube_id
                WHERE p.user_id = ?
                ORDER BY p.watch_later DESC, lower(p.name), p.id, pv.added_at DESC, v.youtube_id
                """;
        Map<Long, Playlist> playlists = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    Playlist playlist = playlists.get(id);
                    if (playlist == null) {
                        playlist = new Playlist(id, rs.getString("name"), rs.getBoolean("watch_later"), new ArrayList<>());
                        playlists.put(id, playlist);
                    }
                    String youtubeId = rs.getString("youtube_id");
                    if (youtubeId != null) {
                        playlist.videos().add(new SavedVideo(youtubeId, rs.getString("title"), rs.getString("channel_title")));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return List.copyOf(playlists.values());
    }

    /**
     * Creates an empty playlist. {@code name} must already be checked (PlaylistController.checkName).
     *
     * @throws ApiException 409 if the user already has a playlist with that name, or has too many
     */
    public Playlist create(long userId, String name) {
        if (WATCH_LATER.equalsIgnoreCase(name)) {
            throw ApiException.conflict("You already have a playlist with that name.");
        }
        // The limit check is part of the insert, so it can't be skipped. (Two requests at the exact
        // same moment could still end one over the limit, which is harmless.)
        String sql = """
                INSERT INTO playlists (user_id, name)
                SELECT ?, ?
                WHERE (SELECT count(*) FROM playlists WHERE user_id = ?) < ?
                RETURNING id
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, name);
            ps.setLong(3, userId);
            ps.setInt(4, MAX_PLAYLISTS);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw ApiException.conflict("You can have at most " + MAX_PLAYLISTS + " playlists.");
                }
                return new Playlist(rs.getLong("id"), name, false, new ArrayList<>());
            }
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                throw ApiException.conflict("You already have a playlist with that name.");
            }
            throw new RuntimeException(e);
        }
    }

    /** Deletes one of the user's playlists. Returns false if it isn't theirs or is Watch later. */
    public boolean delete(long userId, long playlistId) {
        String sql = "DELETE FROM playlists WHERE id = ? AND user_id = ? AND NOT watch_later";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playlistId);
            ps.setLong(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Adds a video to one of the user's playlists. Adding a video that's already there does nothing.
     *
     * @throws ApiException 404 if the playlist isn't theirs, 400 if the video isn't in our database,
     *                      409 if the playlist is full
     */
    public void addVideo(long userId, long playlistId, String youtubeId) {
        if (!owns(userId, playlistId)) {
            throw ApiException.notFound("Playlist not found.");
        }
        String sql = """
                INSERT INTO playlist_videos (playlist_id, youtube_id)
                SELECT ?, youtube_id FROM videos
                WHERE youtube_id = ?
                  AND (SELECT count(*) FROM playlist_videos WHERE playlist_id = ?) < ?
                ON CONFLICT (playlist_id, youtube_id) DO NOTHING
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playlistId);
            ps.setString(2, youtubeId);
            ps.setLong(3, playlistId);
            ps.setInt(4, MAX_VIDEOS_PER_PLAYLIST);
            if (ps.executeUpdate() == 0 && !contains(conn, playlistId, youtubeId)) {
                // Nothing added and it wasn't already there: either the video doesn't exist or the
                // playlist is full.
                if (!videoExists(conn, youtubeId)) {
                    throw ApiException.badRequest("Video not found.");
                }
                throw ApiException.conflict("A playlist can hold at most " + MAX_VIDEOS_PER_PLAYLIST + " videos.");
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Removes a video from one of the user's playlists. Returns false if the playlist isn't theirs. */
    public boolean removeVideo(long userId, long playlistId, String youtubeId) {
        if (!owns(userId, playlistId)) {
            return false;
        }
        String sql = "DELETE FROM playlist_videos WHERE playlist_id = ? AND youtube_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playlistId);
            ps.setString(2, youtubeId);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Makes the user's Watch later playlist if they don't have one yet. */
    private void ensureWatchLater(long userId) {
        // The NOT EXISTS skips the insert (and doesn't use up an id) when it's already there; ON
        // CONFLICT covers two requests making it at the same moment.
        String sql = """
                INSERT INTO playlists (user_id, name, watch_later)
                SELECT ?, ?, true
                WHERE NOT EXISTS (SELECT 1 FROM playlists WHERE user_id = ? AND watch_later)
                ON CONFLICT DO NOTHING
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, WATCH_LATER);
            ps.setLong(3, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean owns(long userId, long playlistId) {
        String sql = "SELECT 1 FROM playlists WHERE id = ? AND user_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playlistId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean contains(Connection conn, long playlistId, String youtubeId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM playlist_videos WHERE playlist_id = ? AND youtube_id = ?")) {
            ps.setLong(1, playlistId);
            ps.setString(2, youtubeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean videoExists(Connection conn, String youtubeId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM videos WHERE youtube_id = ?")) {
            ps.setString(1, youtubeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}