package edu.cpsc488.brainfeed.study;

import edu.cpsc488.brainfeed.db.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Runs against the real test database (see TestDatabase).
class StudyRepositoryTest {

    static final StudyMaterial MATERIAL = new StudyMaterial("Plants use sunlight.", List.of(
            new StudyMaterial.Question("What do plants use?", List.of("Sunlight", "Sand", "Salt", "Snow"), 0, "Photosynthesis.")));

    StudyRepository repository;

    @BeforeEach
    void setUp() {
        repository = new StudyRepository(TestDatabase.get());
        TestDatabase.reset();
        TestDatabase.addVideo("video000001", TestDatabase.topicId("Biology"));
    }

    @Test
    void savesAndReadsBackTheSameMaterial() {
        repository.save("video000001", MATERIAL, "gemma4:e4b");
        assertEquals(MATERIAL, repository.find("video000001").orElseThrow());
        assertEquals("gemma4:e4b", TestDatabase.text("SELECT model FROM video_study"));
        assertEquals(1, repository.writtenInLastDay());
    }

    @Test
    void findsWhichVideosOfAPageAlreadyHaveMaterial() {
        TestDatabase.addVideo("video000002", TestDatabase.topicId("Biology"));
        repository.save("video000001", MATERIAL, "gemma4:e4b");
        assertEquals(Set.of("video000001"), repository.withMaterial(List.of("video000001", "video000002", "unknown0001")));
        assertEquals(Set.of(), repository.withMaterial(List.of()));
    }

    @Test
    void theFirstOneWins() {
        repository.save("video000001", MATERIAL, "gemma4:e4b");
        repository.save("video000001", new StudyMaterial("Different.", MATERIAL.questions()), "other");
        assertEquals("Plants use sunlight.", repository.find("video000001").orElseThrow().summary());
    }

    @Test
    void nothingIsSavedForAVideoWeDontHave() {
        // e.g. it was banned while the model was writing
        repository.save("unknown0001", MATERIAL, "gemma4:e4b");
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM video_study"));
    }

    @Test
    void banningTheVideoDeletesItsSummary() {
        repository.save("video000001", MATERIAL, "gemma4:e4b");
        TestDatabase.exec("DELETE FROM videos");
        assertTrue(repository.find("video000001").isEmpty());
    }

    @Test
    void videoInfoIncludesTheTopicName() {
        StudyRepository.VideoInfo info = repository.videoInfo("video000001").orElseThrow();
        assertEquals("Video video000001", info.title());
        assertEquals("Biology", info.topic());
        assertTrue(repository.videoInfo("unknown0001").isEmpty());
    }

    @Test
    void theDatabaseRefusesBadShapes() {
        assertThrows(RuntimeException.class, () -> TestDatabase.exec(
                "INSERT INTO video_study (youtube_id, summary, questions, model) VALUES ('video000001', 's', '{}', 'm')"));
        assertThrows(RuntimeException.class, () -> TestDatabase.exec(
                "INSERT INTO video_study (youtube_id, summary, questions, model) VALUES ('video000001', '', '[{}]', 'm')"));
    }
}
