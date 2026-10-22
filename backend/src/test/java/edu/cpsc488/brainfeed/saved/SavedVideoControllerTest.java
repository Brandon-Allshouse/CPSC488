package edu.cpsc488.brainfeed.saved;

import edu.cpsc488.brainfeed.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SavedVideoControllerTest {

    @Test
    void acceptsValidYoutubeId() {
        assertEquals(
                "dQw4w9WgXcQ",
                SavedVideoController.validateYoutubeId("dQw4w9WgXcQ")
        );
    }

    @Test
    void rejectsInvalidYoutubeId() {
        ApiException e = assertThrows(
                ApiException.class,
                () -> SavedVideoController.validateYoutubeId("not-valid")
        );

        assertEquals(400, e.status());
    }

    @Test
    void rejectsSqlInjectionStyleInput() {
        ApiException e = assertThrows(
                ApiException.class,
                () -> SavedVideoController.validateYoutubeId("' OR 1=1 --")
        );

        assertEquals(400, e.status());
    }
}
