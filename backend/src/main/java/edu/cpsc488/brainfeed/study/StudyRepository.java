package edu.cpsc488.brainfeed.study;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Reads and writes the {@code video_study} table (V12). */
public class StudyRepository {

    /** What the prompt needs to know about a video. All of it came from YouTube, so it's untrusted. */
    public record VideoInfo(String title, String channel, String topic) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<StudyMaterial.Question>> QUESTIONS = new TypeReference<>() {
    };

    private final DataSource dataSource;

    public StudyRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    Optional<StudyMaterial> find(String youtubeId) {
        String sql = "SELECT summary, questions FROM video_study WHERE youtube_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, youtubeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                List<StudyMaterial.Question> questions = MAPPER.readValue(rs.getString("questions"), QUESTIONS);
                return Optional.of(new StudyMaterial(rs.getString("summary"), questions));
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /** The video's title, channel and topic name, or empty if it isn't in our database (or was banned). */
    Optional<VideoInfo> videoInfo(String youtubeId) {
        String sql = """
                SELECT v.title, v.channel_title, t.name FROM videos v
                JOIN topics t ON t.id = v.topic_id
                WHERE v.youtube_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, youtubeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new VideoInfo(rs.getString(1), rs.getString(2), rs.getString(3)))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Saves the material. Does nothing if the video already has some, or if it was banned (and so
     * deleted) while the model was writing.
     */
    void save(String youtubeId, StudyMaterial material, String model) {
        String sql = """
                INSERT INTO video_study (youtube_id, summary, questions, model)
                SELECT youtube_id, ?, CAST(? AS jsonb), ? FROM videos WHERE youtube_id = ?
                ON CONFLICT (youtube_id) DO NOTHING
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, material.summary());
            ps.setString(2, MAPPER.writeValueAsString(material.questions()));
            ps.setString(3, model);
            ps.setString(4, youtubeId);
            ps.executeUpdate();
        } catch (SQLException | JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /** How many videos got study material in the last 24 hours, for the daily limit. */
    int writtenInLastDay() {
        String sql = "SELECT count(*) FROM video_study WHERE created_at > now() - interval '1 day'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
