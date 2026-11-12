package edu.cpsc488.brainfeed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ApiExceptionTest {

    @Test
    void eachHelperHasTheRightStatus() {
        assertEquals(400, ApiException.badRequest("x").status());
        assertEquals(401, ApiException.unauthorized("x").status());
        assertEquals(403, ApiException.forbidden("x").status());
        assertEquals(404, ApiException.notFound("x").status());
        assertEquals(409, ApiException.conflict("x").status());
        assertEquals(415, ApiException.unsupportedMediaType("x").status());
        assertEquals(429, ApiException.tooManyRequests("x").status());
    }

    @Test
    void theMessageIsKeptExactly() {
        // the frontend shows this text to the user as-is
        assertEquals("Playlist not found.", ApiException.notFound("Playlist not found.").getMessage());
    }

    @Test
    void anyOtherStatusCanBeUsed() {
        ApiException busy = new ApiException(503, "The server is busy.");
        assertEquals(503, busy.status());
        assertEquals("The server is busy.", busy.getMessage());
    }

    @Test
    void itsUnchecked() {
        // handlers throw it without declaring it, and Database.inTransaction rolls back on it
        assertInstanceOf(RuntimeException.class, ApiException.badRequest("x"));
    }
}
