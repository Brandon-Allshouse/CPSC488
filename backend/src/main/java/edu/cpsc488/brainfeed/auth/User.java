package edu.cpsc488.brainfeed.auth;

import java.time.OffsetDateTime;

/**
 * Public view of a user. Never includes the password hash, so it's safe to serialize to JSON.
 * {@code admin} tells the frontend whether to show the admin page; the backend still checks it on
 * every admin request (see AdminController.requireAdmin).
 */
public record User(long id, String email, String username, OffsetDateTime createdAt, boolean admin) {
}
