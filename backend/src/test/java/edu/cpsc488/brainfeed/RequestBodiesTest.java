package edu.cpsc488.brainfeed;

import io.javalin.http.Context;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestBodiesTest {

    record Body(String name) {
    }

    // A stand-in Context where bodyAsClass returns or throws whatever the test wants. Nothing else
    // on it is used by RequestBodies, so every other method just fails loudly.
    private static Context contextWhere(Supplier<Object> bodyAsClass) {
        return (Context) Proxy.newProxyInstance(
                Context.class.getClassLoader(),
                new Class<?>[] {Context.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("bodyAsClass")) {
                        return bodyAsClass.get();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void returnsTheParsedBody() {
        Body body = new Body("testuser");
        assertSame(body, RequestBodies.parse(contextWhere(() -> body), Body.class));
    }

    @Test
    void anEmptyBodyIsABadRequest() {
        ApiException e = assertThrows(ApiException.class,
                () -> RequestBodies.parse(contextWhere(() -> null), Body.class));
        assertEquals(400, e.status());
        assertEquals("Request body is required.", e.getMessage());
    }

    @Test
    void badJsonIsA400NotA500() {
        // e.g. Jackson rejecting an unknown field; the details must not reach the client
        ApiException e = assertThrows(ApiException.class, () -> RequestBodies.parse(
                contextWhere(() -> {
                    throw new IllegalArgumentException("Unrecognized field \"isAdmin\" at line 1");
                }),
                Body.class));
        assertEquals(400, e.status());
        assertEquals("Malformed request body.", e.getMessage());
    }

    @Test
    void anApiExceptionFromParsingIsPassedOnUnchanged() {
        ApiException original = ApiException.unsupportedMediaType("Send JSON.");
        ApiException e = assertThrows(ApiException.class, () -> RequestBodies.parse(
                contextWhere(() -> {
                    throw original;
                }),
                Body.class));
        assertSame(original, e);
    }
}
