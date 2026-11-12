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
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

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
        // concurrent, because the API tests read these from the server's threads
        Map<String, VideoInfo> videos = new ConcurrentHashMap<>();
        Map<String, StudyMaterial> saved = new ConcurrentHashMap<>();
        List<String> savedModels = new CopyOnWriteArrayList<>();
        // in the order they were written, to check who went first
        List<String> savedIds = new CopyOnWriteArrayList<>();
        volatile int writtenToday = 0;
        int withMaterialCalls = 0;

        FakeRepository() {
            super(null);
            videos.put(VIDEO, new VideoInfo("What is a Derivative?", "Test Channel", "Calculus"));
        }

        @Override
        Optional<StudyMaterial> find(String youtubeId) {
            return Optional.ofNullable(saved.get(youtubeId));
        }

        @Override
        Set<String> withMaterial(Collection<String> youtubeIds) {
            withMaterialCalls++;
            return youtubeIds.stream().filter(saved::containsKey).collect(Collectors.toSet());
        }

        @Override
        Optional<VideoInfo> videoInfo(String youtubeId) {
            return Optional.ofNullable(videos.get(youtubeId));
        }

        @Override
        void save(String youtubeId, StudyMaterial material, String model) {
            saved.put(youtubeId, material);
            savedModels.add(model);
            savedIds.add(youtubeId);
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
        List<Integer> replyCaps = new ArrayList<>();
        int warmUps = 0;
        Exception warmUpError = null;

        @Override
        public String model() {
            return "qwen3:4b";
        }

        @Override
        public String chat(String system, String user, JsonNode schema, int maxReplyTokens) throws IOException {
            replyCaps.add(maxReplyTokens);
            return chat(system, user, schema);
        }

        @Override
        public void warmUp() throws IOException {
            warmUps++;
            if (warmUpError instanceof IOException e) {
                throw e;
            }
            if (warmUpError instanceof RuntimeException e) {
                throw e;
            }
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

    // adds count videos the fake database knows about, named video000100 and up
    List<String> moreVideos(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = String.format("video%06d", i + 100);
            repository.videos.put(id, new StudyRepository.VideoInfo("Title", "Test Channel", "Math"));
            ids.add(id);
        }
        return ids;
    }

    @Test
    void oneIpCanOnlyStartSoManyNewSummariesAnHour() {
        StudyGenerator generator = generator();
        for (String id : moreVideos(StudyGenerator.NEW_PER_IP_PER_HOUR)) {
            assertEquals(StudyGenerator.Status.PENDING, generator.request(id, "203.0.113.7").status());
            // run each one right away so the queue limit isn't what stops us
            runJobs();
        }
        assertEquals(StudyGenerator.Status.BUSY, generator.request(VIDEO, "203.0.113.7").status());
        assertTrue(jobs.isEmpty());
        // someone else can still start one
        assertEquals(StudyGenerator.Status.PENDING, generator.request(VIDEO, "198.51.100.20").status());
    }

    @Test
    void finishedSummariesAreServedEvenPastThePerIpLimit() {
        StudyGenerator generator = generator();
        List<String> ids = moreVideos(StudyGenerator.NEW_PER_IP_PER_HOUR);
        for (String id : ids) {
            generator.request(id, "203.0.113.7");
            runJobs();
        }
        assertEquals(StudyGenerator.Status.READY, generator.request(ids.get(0), "203.0.113.7").status());
    }

    @Test
    void askingAgainForOneInTheQueueDoesNotCountAgainstTheIp() {
        // the page polls while it waits, and that shouldn't use up the limit
        StudyGenerator generator = generator();
        for (int i = 0; i < StudyGenerator.NEW_PER_IP_PER_HOUR + 5; i++) {
            assertEquals(StudyGenerator.Status.PENDING, generator.request(VIDEO, "203.0.113.7").status());
        }
        runJobs();
        String other = moreVideos(1).get(0);
        assertEquals(StudyGenerator.Status.PENDING, generator.request(other, "203.0.113.7").status());
    }

    @Test
    void aShutDownWorkerIsUnavailableNotStuckPending() {
        StudyGenerator generator = new StudyGenerator(repository, llm, id -> Optional.empty(), job -> {
            throw new RejectedExecutionException("shut down");
        }, clock);
        assertEquals(StudyGenerator.Status.UNAVAILABLE, generator.request(VIDEO).status());
        // and it wasn't left in the queue, which would make it say PENDING forever
        assertEquals(StudyGenerator.Status.UNAVAILABLE, generator.request(VIDEO).status());
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

    @Test
    void summariesUseTheTighterReplyCap() {
        StudyGenerator generator = generator();
        generator.request(VIDEO);
        runJobs();
        assertEquals(List.of(StudyPrompt.MAX_REPLY_TOKENS), llm.replyCaps);
    }

    // --- prefetching from the feed ---

    @Test
    void prefetchQueuesTheFirstFewVideosOfAPage() {
        List<String> page = moreVideos(10);
        assertEquals(StudyGenerator.PREFETCH_PER_PAGE, generator().prefetch(page, "203.0.113.7"));
        assertEquals(StudyGenerator.PREFETCH_PER_PAGE, jobs.size());
        runJobs();
        assertEquals(page.subList(0, StudyGenerator.PREFETCH_PER_PAGE), repository.savedIds);
    }

    @Test
    void prefetchSkipsVideosAlreadyWrittenOrWaiting() {
        List<String> page = moreVideos(3);
        repository.saved.put(page.get(0), new StudyMaterial("Saved before.", List.of()));
        StudyGenerator generator = generator();
        assertEquals(2, generator.prefetch(page, "203.0.113.7"));
        // the same page again (the feed was reloaded) adds nothing
        assertEquals(0, generator.prefetch(page, "203.0.113.7"));
        assertEquals(2, jobs.size());
    }

    @Test
    void prefetchChecksTheWholePageInOneQuery() {
        generator().prefetch(moreVideos(5), "203.0.113.7");
        assertEquals(1, repository.withMaterialCalls);
    }

    @Test
    void aRequestGoesAheadOfPrefetchedVideos() {
        List<String> page = moreVideos(3);
        StudyGenerator generator = generator();
        generator.prefetch(page, "203.0.113.7");
        generator.request(VIDEO, "198.51.100.20");
        jobs.poll().run();
        assertEquals(List.of(VIDEO), repository.savedIds);
    }

    @Test
    void askingForAPrefetchedVideoMovesItUpWithoutQueueingItTwice() {
        List<String> page = moreVideos(3);
        StudyGenerator generator = generator();
        generator.prefetch(page, "203.0.113.7");
        assertEquals(StudyGenerator.Status.PENDING, generator.request(page.get(2), "203.0.113.7").status());
        assertEquals(3, jobs.size());
        jobs.poll().run();
        assertEquals(List.of(page.get(2)), repository.savedIds);
        runJobs();
        assertEquals(3, repository.savedIds.size());
        assertEquals(3, llm.prompts.size());
    }

    @Test
    void aPrefetchThatWaitedLongEnoughGoesAheadOfNewRequests() {
        // aging: prefetches can't be starved by a steady stream of requests
        String waitingLong = moreVideos(1).get(0);
        StudyGenerator generator = generator();
        generator.prefetch(List.of(waitingLong), "203.0.113.7");
        clock.now = clock.now.plus(StudyGenerator.PREFETCH_HANDICAP).plusSeconds(1);
        generator.request(VIDEO, "198.51.100.20");
        jobs.poll().run();
        assertEquals(List.of(waitingLong), repository.savedIds);
    }

    @Test
    void aRecentPrefetchStillWaitsBehindARequest() {
        String prefetched = moreVideos(1).get(0);
        StudyGenerator generator = generator();
        generator.prefetch(List.of(prefetched), "203.0.113.7");
        clock.now = clock.now.plus(StudyGenerator.PREFETCH_HANDICAP).minusSeconds(1);
        generator.request(VIDEO, "198.51.100.20");
        jobs.poll().run();
        assertEquals(List.of(VIDEO), repository.savedIds);
    }

    @Test
    void stalePrefetchesAreDroppedWithoutCallingTheModel() {
        String prefetched = moreVideos(1).get(0);
        StudyGenerator generator = generator();
        generator.prefetch(List.of(prefetched), "203.0.113.7");
        clock.now = clock.now.plus(StudyGenerator.PREFETCH_STALE_AFTER).plusSeconds(1);
        runJobs();
        assertTrue(llm.prompts.isEmpty());
        // not stuck as waiting: asking for it starts it
        assertEquals(StudyGenerator.Status.PENDING, generator.request(prefetched).status());
    }

    @Test
    void aRequestedVideoIsNeverDroppedAsStale() {
        String prefetched = moreVideos(1).get(0);
        StudyGenerator generator = generator();
        generator.prefetch(List.of(prefetched), "203.0.113.7");
        generator.request(prefetched);
        clock.now = clock.now.plus(StudyGenerator.PREFETCH_STALE_AFTER).plusSeconds(1);
        runJobs();
        assertEquals(List.of(prefetched), repository.savedIds);
    }

    @Test
    void prefetchCanOnlyTakePartOfTheQueue() {
        StudyGenerator generator = generator();
        List<String> ids = moreVideos(15 + StudyGenerator.MAX_QUEUE);
        int prefetched = 0;
        for (int start = 0; start < 15; start += 5) {
            prefetched += generator.prefetch(ids.subList(start, start + 5), "203.0.113.7");
        }
        assertEquals(StudyGenerator.MAX_PREFETCH_QUEUED, prefetched);
        // the rest of the line is still open to people who asked
        List<String> requested = ids.subList(15, 15 + StudyGenerator.MAX_QUEUE - StudyGenerator.MAX_PREFETCH_QUEUED);
        for (String id : requested) {
            assertEquals(StudyGenerator.Status.PENDING, generator.request(id).status());
        }
        assertEquals(StudyGenerator.Status.BUSY, generator.request(VIDEO).status());
    }

    @Test
    void prefetchStopsAtItsOwnDailyLimitButRequestsDont() {
        repository.writtenToday = StudyGenerator.PREFETCH_DAILY_LIMIT;
        StudyGenerator generator = generator();
        assertEquals(0, generator.prefetch(moreVideos(5), "203.0.113.7"));
        assertEquals(StudyGenerator.Status.PENDING, generator.request(VIDEO).status());
        assertTrue(StudyGenerator.PREFETCH_DAILY_LIMIT < StudyGenerator.DAILY_LIMIT);
    }

    @Test
    void videosWaitingCountTowardThePrefetchLimit() {
        repository.writtenToday = StudyGenerator.PREFETCH_DAILY_LIMIT - 2;
        assertEquals(2, generator().prefetch(moreVideos(5), "203.0.113.7"));
    }

    @Test
    void thePrefetchLimitIsCheckedAgainWhenItsTurnComes() {
        StudyGenerator generator = generator();
        generator.prefetch(moreVideos(1), "203.0.113.7");
        // people pressing play used up the prefetch share while it waited
        repository.writtenToday = StudyGenerator.PREFETCH_DAILY_LIMIT;
        runJobs();
        assertTrue(llm.prompts.isEmpty());
    }

    @Test
    void oneIpCanOnlyPrefetchSoManyAnHour() {
        StudyGenerator generator = generator();
        List<String> ids = moreVideos(StudyGenerator.PREFETCH_PER_IP_PER_HOUR + 10);
        int prefetched = 0;
        for (int start = 0; start < ids.size(); start += 5) {
            prefetched += generator.prefetch(ids.subList(start, start + 5), "203.0.113.7");
            runJobs();
        }
        assertEquals(StudyGenerator.PREFETCH_PER_IP_PER_HOUR, prefetched);
        // someone else still gets theirs, and scrolling didn't use up this IP's requests
        assertEquals(1, generator.prefetch(List.of(VIDEO), "198.51.100.20"));
        runJobs();
        String other = String.format("video%06d", 900);
        repository.videos.put(other, new StudyRepository.VideoInfo("Title", "Test Channel", "Math"));
        assertEquals(StudyGenerator.Status.PENDING, generator.request(other, "203.0.113.7").status());
    }

    @Test
    void prefetchDoesNothingWithoutAnLlmOrWhilePaused() {
        StudyGenerator noLlm = new StudyGenerator(repository, null, id -> Optional.empty(), jobs::add, clock);
        assertEquals(0, noLlm.prefetch(moreVideos(3), "203.0.113.7"));

        llm.replies.add(new IOException("Ollama request failed: ConnectException"));
        StudyGenerator generator = generator();
        generator.request(VIDEO);
        runJobs();
        assertEquals(0, generator.prefetch(moreVideos(3), "203.0.113.7"));
        assertTrue(jobs.isEmpty());
    }

    @Test
    void prefetchSkipsVideosThatFailedRecently() {
        llm.replies.add("not json");
        llm.replies.add("still not json");
        StudyGenerator generator = generator();
        generator.request(VIDEO);
        runJobs();
        assertEquals(0, generator.prefetch(List.of(VIDEO), "203.0.113.7"));
    }

    @Test
    void aPrefetchedVideoBannedWhileWaitingIsSkipped() {
        String prefetched = moreVideos(1).get(0);
        StudyGenerator generator = generator();
        generator.prefetch(List.of(prefetched), "203.0.113.7");
        repository.videos.remove(prefetched);
        runJobs();
        assertTrue(llm.prompts.isEmpty());
    }

    @Test
    void prefetchWithAShutDownWorkerQueuesNothing() {
        StudyGenerator generator = new StudyGenerator(repository, llm, id -> Optional.empty(), job -> {
            throw new RejectedExecutionException("shut down");
        }, clock);
        List<String> page = moreVideos(3);
        assertEquals(0, generator.prefetch(page, "203.0.113.7"));
        // nothing left behind that would say PENDING forever
        assertEquals(StudyGenerator.Status.UNAVAILABLE, generator.request(page.get(0)).status());
    }

    // --- warming up the model at startup ---

    @Test
    void warmUpRunsOnTheWorker() {
        generator().warmUp();
        assertEquals(0, llm.warmUps);
        runJobs();
        assertEquals(1, llm.warmUps);
    }

    @Test
    void aFailedWarmUpOnlyLogsAndSummariesStillWork() {
        llm.warmUpError = new IOException("Ollama request failed: ConnectException");
        StudyGenerator generator = generator();
        generator.warmUp();
        runJobs();
        // not paused either: Ollama is often just a few seconds behind the backend at startup
        assertEquals(StudyGenerator.Status.PENDING, generator.request(VIDEO).status());
        runJobs();
        assertEquals(StudyGenerator.Status.READY, generator.request(VIDEO).status());
    }

    @Test
    void anUnexpectedWarmUpErrorDoesNotEscape() {
        llm.warmUpError = new IllegalStateException("something odd");
        generator().warmUp();
        runJobs();
        assertEquals(1, llm.warmUps);
    }

    @Test
    void warmUpWithAShutDownWorkerOrNoLlmDoesNotThrow() {
        new StudyGenerator(repository, llm, id -> Optional.empty(), job -> {
            throw new RejectedExecutionException("shut down");
        }, clock).warmUp();
        new StudyGenerator(repository, null, id -> Optional.empty(), jobs::add, clock).warmUp();
        assertTrue(jobs.isEmpty());
        assertEquals(0, llm.warmUps);
    }
}
