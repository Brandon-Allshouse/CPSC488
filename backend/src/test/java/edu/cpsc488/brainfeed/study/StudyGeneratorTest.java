package edu.cpsc488.brainfeed.study;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cpsc488.brainfeed.llm.Llm;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Uses a fake database, fake model and a "run it later" executor, so nothing real is needed.
class StudyGeneratorTest {

    static final String VIDEO = "video000001";
    static final String GOOD_REPLY = """
            {"summary": "The video explains derivatives.", "questions": [
              {"question": "What does a derivative measure?",
               "options": ["Total change", "Rate of change at one instant", "Average change", "Area"],
               "explanation": "It is the instantaneous rate of change.",
               "answer": "Rate of change at one instant"}]}""";

    // pretends to be the video_study and videos tables
    static class FakeRepository extends StudyRepository {
        Map<String, VideoInfo> videos = new HashMap<>();
        Map<String, StudyMaterial> saved = new HashMap<>();
        List<String> savedModels = new ArrayList<>();
        int writtenToday = 0;

        FakeRepository() {
            super(null);
            videos.put(VIDEO, new VideoInfo("What is a Derivative?", "Test Channel", "Calculus"));
        }

        @Override
        Optional<StudyMaterial> find(String youtubeId) {
            return Optional.ofNullable(saved.get(youtubeId));
        }

        @Override
        Optional<VideoInfo> videoInfo(String youtubeId) {
            return Optional.ofNullable(videos.get(youtubeId));
        }

        @Override
        void save(String youtubeId, StudyMaterial material, String model) {
            saved.put(youtubeId, material);
            savedModels.add(model);
            writtenToday++;
        }

        @Override
        int writtenInLastDay() {
            return writtenToday;
        }
    }

    // gives back the replies we line up, or throws if we line up an error
    static class FakeLlm implements Llm {
        Deque<Object> replies = new ArrayDeque<>();
        List<String> prompts = new ArrayList<>();

        @Override
        public String model() {
            return "qwen3:4b";
        }

        @Override
        public String chat(String system, String user, JsonNode schema) throws IOException {
            prompts.add(user);
            Object next = replies.isEmpty() ? GOOD_REPLY : replies.poll();
            if (next instanceof IOException e) {
                throw e;
            }
            return (String) next;
        }
    }

    // a clock we can move forward by hand
    static class FakeClock extends Clock {
        Instant now = Instant.parse("2026-11-10T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final FakeRepository repository = new FakeRepository();
    final FakeLlm llm = new FakeLlm();
    final FakeClock clock = new FakeClock();
    // jobs wait here until the test runs them, like the real worker thread would later
    final Deque<Runnable> jobs = new ArrayDeque<>();
    StudyGenerator.Descriptions descriptions = id -> Optional.of("Slopes of tangent lines.");

    StudyGenerator generator() {
        return new StudyGenerator(repository, llm, id -> descriptions.describe(id), jobs::add, clock);
    }

    void runJobs() {
        while (!jobs.isEmpty()) {
            jobs.poll().run();
        }
    }

    @Test
    void writesOnTheFirstRequestThenServesTheSavedCopy() {
        StudyGenerator generator = generator();
        assertEquals(StudyGenerator.Status.PENDING, generator.request(VIDEO).status());
        runJobs();
        StudyGenerator.Result result = generator.request(VIDEO);
        assertEquals(StudyGenerator.Status.READY, result.status());
        assertNotNull(result.material());
        assertEquals(List.of("qwen3:4b"), repository.savedModels);
        // the second request didn't call the model again
        assertEquals(1, llm.prompts.size());
    }

    @Test
    void thePromptIncludesTheDescription() {
        StudyGenerator generator = generator();
        generator.request(VIDEO);
        runJobs();
        assertTrue(llm.prompts.get(0).contains("Description: Slopes of tangent lines."));
    }

    @Test
    void stillWritesOneWhenTheDescriptionIsMissing() {
        descriptions = id -> Optional.empty();
        StudyGenerator generator = generator();
        generator.request(VIDEO);
        runJobs();
        assertTrue(llm.prompts.get(0).contains("Description: (none)"));
        assertEquals(StudyGenerator.Status.READY, generator.request(VIDEO).status());
    }

    @Test
    void unknownVideosAreNotFound() {
        assertEquals(StudyGenerator.Status.NOT_FOUND, generator().request("unknown0001").status());
        assertTrue(jobs.isEmpty());
    }

    @Test
    void askingAgainWhileWaitingDoesNotQueueItTwice() {
        StudyGenerator generator = generator();
        generator.request(VIDEO);
        assertEquals(StudyGenerator.Status.PENDING, generator.request(VIDEO).status());
        assertEquals(1, jobs.size());
    }

    @Test
    void saysBusyWhenTheQueueIsFull() {
        StudyGenerator generator = generator();
        for (int i = 0; i < StudyGenerator.MAX_QUEUE; i++) {
            String id = String.format("video%06d", i + 100);
            repository.videos.put(id, new StudyRepository.VideoInfo("Title", "Test Channel", "Math"));
            assertEquals(StudyGenerator.Status.PENDING, generator.request(id).status());
        }
        assertEquals(StudyGenerator.Status.BUSY, generator.request(VIDEO).status());
        // once the line moves, there's room again
        runJobs();
        assertEquals(StudyGenerator.Status.PENDING, generator.request(VIDEO).status());
    }

    @Test
    void stopsAtTheDailyLimit() {
        repository.writtenToday = StudyGenerator.DAILY_LIMIT;
        assertEquals(StudyGenerator.Status.UNAVAILABLE, generator().request(VIDEO).status());
        assertTrue(jobs.isEmpty());
    }

    @Test
    void savedOnesAreStillShownAtTheDailyLimit() {
        StudyGenerator generator = generator();
        generator.request(VIDEO);
        runJobs();
        repository.writtenToday = StudyGenerator.DAILY_LIMIT;
        assertEquals(StudyGenerator.Status.READY, generator.request(VIDEO).status());
    }

    @Test
    void withoutAnLlmOnlySavedOnesAreShown() {
        StudyGenerator generator = new StudyGenerator(repository, null, id -> Optional.empty(), jobs::add, clock);
        assertEquals(StudyGenerator.Status.UNAVAILABLE, generator.request(VIDEO).status());
        repository.saved.put(VIDEO, new StudyMaterial("Saved before.", List.of()));
        assertEquals(StudyGenerator.Status.READY, generator.request(VIDEO).status());
    }

    @Test
    void triesOnceMoreAfterABadReply() {
        llm.replies.add("{\"summary\": \"no questions\"}");
        StudyGenerator generator = generator();
        generator.request(VIDEO);
        runJobs();
        assertEquals(2, llm.prompts.size());
        assertEquals(StudyGenerator.Status.READY, generator.request(VIDEO).status());
    }

    @Test
    void givesUpOnAVideoForAWhileAfterTwoBadReplies() {
        llm.replies.add("not json");
        llm.replies.add("still not json");
        StudyGenerator generator = generator();
        generator.request(VIDEO);
        runJobs();
        assertEquals(StudyGenerator.Status.UNAVAILABLE, generator.request(VIDEO).status());
        assertTrue(jobs.isEmpty());
        clock.now = clock.now.plus(StudyGenerator.RETRY_FAILED_AFTER).plusSeconds(1);
        assertEquals(StudyGenerator.Status.PENDING, generator.request(VIDEO).status());
    }

    @Test
    void pausesAfterOllamaFails() {
        llm.replies.add(new IOException("Ollama request failed: ConnectException"));
        StudyGenerator generator = generator();
        generator.request(VIDEO);
        runJobs();
        assertEquals(StudyGenerator.Status.UNAVAILABLE, generator.request(VIDEO).status());
        clock.now = clock.now.plus(StudyGenerator.PAUSE_AFTER_ERROR).plus(Duration.ofSeconds(1));
        assertEquals(StudyGenerator.Status.PENDING, generator.request(VIDEO).status());
        runJobs();
        assertEquals(StudyGenerator.Status.READY, generator.request(VIDEO).status());
    }

    @Test
    void aDatabaseErrorDoesNotLeaveTheVideoStuckAsPending() {
        FakeRepository broken = new FakeRepository() {
            @Override
            void save(String youtubeId, StudyMaterial material, String model) {
                throw new RuntimeException("database is down");
            }
        };
        StudyGenerator generator = new StudyGenerator(broken, llm, id -> Optional.empty(), jobs::add, clock);
        generator.request(VIDEO);
        runJobs();
        // it was taken out of the queue, so asking again tries again
        assertEquals(StudyGenerator.Status.PENDING, generator.request(VIDEO).status());
        assertEquals(1, jobs.size());
    }
}
