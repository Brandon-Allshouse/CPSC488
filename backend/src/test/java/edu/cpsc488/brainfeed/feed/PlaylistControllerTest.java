package edu.cpsc488.brainfeed.feed;

import edu.cpsc488.brainfeed.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Tests the input checks in PlaylistController. The endpoints themselves need a database.
class PlaylistControllerTest {

    private static void assertBadRequest(Runnable code) {
        ApiException e = assertThrows(ApiException.class, code::run);
        assertEquals(400, e.status());
    }

    @Test
    void trimsPlaylistNames() {
        assertEquals("Study", PlaylistController.checkName("  Study  "));
    }

    @Test
    void acceptsNamesUpToTheLimit() {
        String longest = "x".repeat(PlaylistController.MAX_NAME);
        assertEquals(longest, PlaylistController.checkName(longest));
    }

    @Test
    void rejectsMissingBlankLongAndMultiLineNames() {
        assertBadRequest(() -> PlaylistController.checkName(null));
        assertBadRequest(() -> PlaylistController.checkName("   "));
        assertBadRequest(() -> PlaylistController.checkName("x".repeat(PlaylistController.MAX_NAME + 1)));
        assertBadRequest(() -> PlaylistController.checkName("two\nlines"));
    }

    @Test
    void parsesPlaylistIds() {
        assertEquals(42L, PlaylistController.parsePlaylistId("42"));
    }

    @Test
    void rejectsBadPlaylistIds() {
        assertBadRequest(() -> PlaylistController.parsePlaylistId("0"));
        assertBadRequest(() -> PlaylistController.parsePlaylistId("-5"));
        assertBadRequest(() -> PlaylistController.parsePlaylistId("abc"));
        assertBadRequest(() -> PlaylistController.parsePlaylistId(""));
    }
}