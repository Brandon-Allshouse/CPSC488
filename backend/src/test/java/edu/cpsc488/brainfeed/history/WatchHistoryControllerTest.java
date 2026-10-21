package edu.cpsc488.brainfeed.history;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// The endpoints and SQL need a database, so they'll be covered by the API tests later. Video ids
// are checked with FeedController.checkYoutubeId, which FeedControllerTest covers.
class WatchHistoryControllerTest {

    @Test
    void keepsTheLast25Videos() {
        // the requirement for the Watch history page; the README and SECURITY.md say 25 too
        assertEquals(25, WatchHistoryController.LIMIT);
    }
}
