package edu.cpsc488.brainfeed.auth;

import java.time.OffsetDateTime;

/** Public view of a user. Never includes the password hash, so it's safe to serialize to JSON. */
public record User(long id, String email, String username, OffsetDateTime createdAt) {
}
