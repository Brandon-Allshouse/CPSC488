package edu.cpsc488.brainfeed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The protections every route gets from App.configure (SECURITY.md, "Injection and web security").
class AppSecurityTest {

    record Body(String name) {
    }

    private ApiServer server;

    private ApiServer start(boolean https) {
        server = new ApiServer(https, routes -> {
            routes.get("/api/ok", ctx -> ctx.json(Map.of("ok", true)));
            routes.post("/api/echo", ctx -> ctx.json(RequestBodies.parse(ctx, Body.class)));
            routes.get("/api/boom", ctx -> {
                throw new IllegalStateException("SELECT secret FROM internals");
            });
        });
        return server;
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void everyResponseHasTheSecurityHeaders() {
        HttpResponse<String> response = start(false).get("/api/ok");
        assertEquals("default-src 'none'; frame-ancestors 'none'", response.headers().firstValue("Content-Security-Policy").orElse(""));
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElse(""));
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElse(""));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        // no server name or version for attackers
        assertTrue(response.headers().firstValue("Server").isEmpty());
        // HSTS only makes sense over HTTPS
        assertTrue(response.headers().firstValue("Strict-Transport-Security").isEmpty());
    }

    @Test
    void hstsIsSentBehindHttps() {
        assertTrue(start(true).get("/api/ok").headers().firstValue("Strict-Transport-Security").orElse("").contains("max-age=31536000"));
    }

    @Test
    void headersAreThereOnErrorsToo() {
        HttpResponse<String> response = start(false).get("/api/no-such-route");
        assertEquals(404, response.statusCode());
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""));
    }

    @Test
    void writesMustBeJson() {
        // a plain HTML form on another site can only send form or text bodies
        HttpResponse<String> response = start(false).send(server.builder("/api/echo")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("name=x")));
        assertEquals(415, response.statusCode());
        assertEquals(415, server.send(server.builder("/api/echo").POST(HttpRequest.BodyPublishers.ofString("{}"))).statusCode());
    }

    @Test
    void writesFromAnotherSiteAreBlocked() {
        HttpResponse<String> response = start(false).send(server.builder("/api/echo")
                .header("Content-Type", "application/json")
                .header("Origin", "https://evil.example")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}")));
        assertEquals(403, response.statusCode());
        assertEquals("Cross-site request blocked.", server.json(response).get("error").asText());
    }

    @Test
    void writesFromOurFrontendOrWithNoOriginWork() {
        assertEquals(200, start(false).send("POST", "/api/echo", "{\"name\":\"x\"}").statusCode());
        HttpResponse<String> noOrigin = server.send(server.builder("/api/echo")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}")));
        assertEquals(200, noOrigin.statusCode());
    }

    @Test
    void unknownFieldsAndBrokenJsonAre400WithoutParserDetails() {
        start(false);
        HttpResponse<String> extra = server.send("POST", "/api/echo", "{\"name\":\"x\",\"admin\":true}");
        assertEquals(400, extra.statusCode());
        assertFalse(extra.body().contains("admin"));
        HttpResponse<String> broken = server.send("POST", "/api/echo", "{\"name\":");
        assertEquals(400, broken.statusCode());
        assertFalse(broken.body().toLowerCase().contains("jackson"));
    }

    @Test
    void hugeBodiesAreRefused() {
        start(false);
        String big = "{\"name\":\"" + "a".repeat(20_000) + "\"}";
        HttpResponse<String> response = server.send("POST", "/api/echo", big);
        // 413 from Jetty, or 400 when RequestBodies turns the unreadable body into its usual error
        assertTrue(response.statusCode() == 413 || response.statusCode() == 400, "status " + response.statusCode());
        assertFalse(response.body().contains("aaaa"));
    }

    @Test
    void bugsGiveAGenericMessageWithNoInternals() {
        HttpResponse<String> response = start(false).get("/api/boom");
        assertEquals(500, response.statusCode());
        assertEquals("Something went wrong on our end.", server.json(response).get("error").asText());
        assertFalse(response.body().contains("SELECT"));
        assertFalse(response.body().contains("IllegalStateException"));
    }
}
