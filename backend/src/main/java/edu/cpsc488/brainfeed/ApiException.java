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

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(401, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, message);
    }

    public static ApiException unsupportedMediaType(String message) {
        return new ApiException(415, message);
    }

    public static ApiException tooManyRequests(String message) {
        return new ApiException(429, message);
    }
}
