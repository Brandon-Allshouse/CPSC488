package edu.cpsc488.brainfeed;

/**
 * Throw from any handler to stop the request and send the client an HTTP error with the body
 * {@code {"error": "<message>"}}. App.java turns it into the response.
 *
 * <p><b>The message is shown to users as-is</b> (the login page displays it), so write it for a
 * human, and never include internal details such as SQL, stack traces, file paths, or whether an
 * account exists.
 */
public class ApiException extends RuntimeException {

    private final int status;

    /** For statuses without a helper below, like 503 when the server is busy. */
    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** The HTTP status code to send. */
    public int status() {
        return status;
    }

    /** 400: the request itself is wrong (bad input, missing field). */
    public static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    /** 401: not logged in. */
    public static ApiException unauthorized(String message) {
        return new ApiException(401, message);
    }

    /** 403: logged in, but not allowed to do this. */
    public static ApiException forbidden(String message) {
        return new ApiException(403, message);
    }

    /** 404: it doesn't exist, or isn't the caller's (so ids can't be probed). */
    public static ApiException notFound(String message) {
        return new ApiException(404, message);
    }

    /** 409: clashes with what's already there (taken name, full list). */
    public static ApiException conflict(String message) {
        return new ApiException(409, message);
    }

    /** 415: the body isn't JSON. */
    public static ApiException unsupportedMediaType(String message) {
        return new ApiException(415, message);
    }

    /** 429: a rate limit was hit. */
    public static ApiException tooManyRequests(String message) {
        return new ApiException(429, message);
    }
}
