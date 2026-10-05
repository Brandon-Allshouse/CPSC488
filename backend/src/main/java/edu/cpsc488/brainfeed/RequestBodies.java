package edu.cpsc488.brainfeed;

import io.javalin.http.Context;

/** Shared helper for reading JSON request bodies in controllers. */
public final class RequestBodies {

    private RequestBodies() {
    }

    /**
     * Parses the JSON body into {@code type}, turning any parse problem (bad JSON, wrong types,
     * unknown fields) into a 400 instead of a 500.
     */
    public static <T> T parse(Context ctx, Class<T> type) {
        try {
            T body = ctx.bodyAsClass(type);
            if (body == null) {
                throw ApiException.badRequest("Request body is required.");
            }
            return body;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Malformed request body.");
        }
    }
}
