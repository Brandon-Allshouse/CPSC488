package edu.cpsc488.brainfeed.saved;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Every query is scoped to the user id supplied by the session. */
public class StudyNoteRepository {
    private final DataSource dataSource;

    public StudyNoteRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public List<StudyNote> list(long userId) {
        String sql = """
                SELECT youtube_id, note, updated_at
                FROM study_notes
                WHERE user_id = ?
                ORDER BY updated_at DESC, youtube_id
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            List<StudyNote> notes = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) notes.add(read(result));
            }
            return notes;
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    /** Empty notes clear the user's text. Missing notebook entries return empty, including other users' entries. */
    public Optional<StudyNote> save(long userId, String youtubeId, String note) {
        String sql = """
                INSERT INTO study_notes (user_id, youtube_id, note)
                SELECT user_id, youtube_id, ? FROM saved_videos
                WHERE user_id = ? AND youtube_id = ?
                ON CONFLICT (user_id, youtube_id)
                DO UPDATE SET note = EXCLUDED.note, updated_at = now()
                RETURNING youtube_id, note, updated_at
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, note);
            statement.setLong(2, userId);
            statement.setString(3, youtubeId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static StudyNote read(ResultSet result) throws SQLException {
        return new StudyNote(result.getString("youtube_id"), result.getString("note"),
                result.getObject("updated_at", OffsetDateTime.class));
    }
}
