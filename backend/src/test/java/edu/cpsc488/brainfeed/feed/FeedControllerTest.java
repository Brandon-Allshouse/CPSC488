package edu.cpsc488.brainfeed.feed;

import edu.cpsc488.brainfeed.ApiException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Tests the input checks and paging in FeedController. The endpoints themselves need a database,
// so they'll be covered by the API tests later.
class FeedControllerTest {

    private static final Set<Integer> REAL_TOPICS = Set.of(1, 2, 3);

    private static void assertBadRequest(Runnable code) {
        ApiException e = assertThrows(ApiException.class, code::run);
        assertEquals(400, e.status());
    }

    private static List<Video> fakeVideos(int count) {
        List<Video> videos = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            videos.add(new Video("video%06d".formatted(i).substring(0, 11), "Video " + i, "Test Channel", 1, OffsetDateTime.now()));
        }
        return videos;
    }

    @Test
    void readsANormalFeedRequest() {
        FeedController.FeedQuery query = FeedController.parseFeedQuery("1, 2,3", "42", "2");
        assertEquals(List.of(1, 2, 3), query.topicIds());
        assertEquals(42, query.seed());
        assertEquals(2, query.page());
    }

    @Test
    void seedAndPageDefaultToZero() {
        FeedController.FeedQuery query = FeedController.parseFeedQuery("1", null, "");
        assertEquals(0, query.seed());
        assertEquals(0, query.page());
    }

    @Test
    void missingTopicsIsRejected() {
        assertBadRequest(() -> FeedController.parseFeedQuery(null, null, null));
        assertBadRequest(() -> FeedController.parseFeedQuery(" ", null, null));
    }

    @Test
    void topicsThatArentNumbersAreRejected() {
        assertBadRequest(() -> FeedController.parseFeedQuery("1,abc", null, null));
        assertBadRequest(() -> FeedController.parseFeedQuery("1,", null, null));
        assertBadRequest(() -> FeedController.parseFeedQuery("1' OR 1=1--", null, null));
    }

    @Test
    void badSeedOrPageIsRejected() {
        assertBadRequest(() -> FeedController.parseFeedQuery("1", "abc", null));
        assertBadRequest(() -> FeedController.parseFeedQuery("1", null, "-1"));
        assertBadRequest(() -> FeedController.parseFeedQuery("1", null, String.valueOf(FeedController.MAX_PAGE + 1)));
    }

    @Test
    void tooManyTopicsIsRejected() {
        String[] ids = new String[FeedController.MAX_TOPICS_PER_REQUEST + 1];
        Arrays.fill(ids, "1");
        assertBadRequest(() -> FeedController.parseFeedQuery(String.join(",", ids), null, null));
    }

    @Test
    void duplicateTopicsAreRemoved() {
        assertEquals(List.of(2, 1), List.copyOf(FeedController.checkTopicIds(List.of(2, 1, 2), REAL_TOPICS)));
    }

    @Test
    void emptyOrMissingTopicListIsRejected() {
        assertBadRequest(() -> FeedController.checkTopicIds(List.of(), REAL_TOPICS));
        assertBadRequest(() -> FeedController.checkTopicIds(null, REAL_TOPICS));
        assertBadRequest(() -> FeedController.checkTopicIds(Arrays.asList(1, null), REAL_TOPICS));
    }

    @Test
    void topicThatDoesntExistIsRejected() {
        assertBadRequest(() -> FeedController.checkTopicIds(List.of(1, 999), REAL_TOPICS));
    }

    @Test
    void fullPageHasANextPage() {
        // the database gets asked for 11 so we know there's more
        FeedController.FeedPage page = FeedController.toPage(fakeVideos(FeedController.PAGE_SIZE + 1), 0, false);
        assertEquals(FeedController.PAGE_SIZE, page.videos().size());
        assertEquals(1, page.nextPage());
    }

    @Test
    void lastPageHasNoNextPage() {
        FeedController.FeedPage page = FeedController.toPage(fakeVideos(5), 3, false);
        assertEquals(5, page.videos().size());
        assertNull(page.nextPage());
    }

    @Test
    void exactlyOnePageLeftHasNoNextPage() {
        assertNull(FeedController.toPage(fakeVideos(FeedController.PAGE_SIZE), 0, false).nextPage());
    }

    @Test
    void noNextPageAfterTheMaxPage() {
        assertNull(FeedController.toPage(fakeVideos(FeedController.PAGE_SIZE + 1), FeedController.MAX_PAGE, false).nextPage());
    }

    @Test
    void emptyPageSaysWhenVideosAreStillBeingFetched() {
        // the frontend uses this to show "finding videos" instead of "no videos"
        FeedController.FeedPage page = FeedController.toPage(fakeVideos(0), 0, true);
        assertTrue(page.videos().isEmpty());
        assertTrue(page.fetching());
    }
}
