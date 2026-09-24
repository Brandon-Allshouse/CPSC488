-- Initial schema: accounts and login sessions.
-- Flyway runs this automatically on backend startup (see Database.migrate). Once it has run on
-- anyone's machine, don't edit it; put schema changes in a new V<n>__description.sql file.

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         TEXT        NOT NULL,
    username      TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Emails are stored lowercased by the app; usernames keep their casing but must be unique ignoring
-- case ("Alice" and "alice" can't both exist). UserRepository.create looks for the name
-- users_username_key in duplicate-key errors, so keep the two in sync if you rename it.
CREATE UNIQUE INDEX users_email_key    ON users (email);
CREATE UNIQUE INDEX users_username_key ON users (lower(username));

-- Server-side login sessions (see SessionRepository). Only a SHA-256 hash of the cookie token is
-- stored, so a leaked database can't be used to hijack sessions. A session is valid while
-- expires_at is in the future AND last_seen_at is within the idle timeout. Deleting a user
-- deletes their sessions.
CREATE TABLE sessions (
    token_hash   TEXT        PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX sessions_user_id_idx    ON sessions (user_id);
CREATE INDEX sessions_expires_at_idx ON sessions (expires_at);
