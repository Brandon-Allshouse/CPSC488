-- Admin accounts, banned users, blacklisted videos, and a log of what admins did.
-- Like every migration: once it has run on anyone's machine, don't edit it; add a new file.

-- There's deliberately no endpoint that makes someone an admin, so a bug or a stolen session can't
-- be used to grant it. Admins are made by hand in the database (see "Admin accounts" in the README).
-- A banned user can't log in, and banning ends all of their sessions (AdminRepository.banUser).
ALTER TABLE users
    ADD COLUMN is_admin   BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN banned_at  TIMESTAMPTZ,
    ADD COLUMN ban_reason TEXT CHECK (char_length(ban_reason) <= 200),
    -- Admins can't be banned (so nobody can lock every admin out); take admin away first.
    ADD CONSTRAINT users_admin_not_banned CHECK (NOT (is_admin AND banned_at IS NOT NULL)),
    ADD CONSTRAINT users_ban_has_reason CHECK ((banned_at IS NULL) = (ban_reason IS NULL));

-- Videos that must never show up. Not a foreign key to videos on purpose: banning deletes the
-- video row (and everyone's likes of it), and this table is what stops FeedRefresher from saving it
-- again. It also lets an admin ban a video we haven't fetched yet. The title is copied at ban time
-- so the admin page can still show what was banned.
CREATE TABLE banned_videos (
    youtube_id TEXT        PRIMARY KEY CHECK (youtube_id ~ '^[A-Za-z0-9_-]{11}$'),
    title      TEXT        CHECK (char_length(title) <= 300),
    reason     TEXT        NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 200),
    banned_by  BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    banned_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Every ban and unban, so admins are accountable for what they do (OWASP Logging Cheat Sheet).
-- Kept if the admin's account is deleted, with admin_id set to NULL.
CREATE TABLE admin_actions (
    id         BIGSERIAL   PRIMARY KEY,
    admin_id   BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    action     TEXT        NOT NULL CHECK (action IN ('ban_user', 'unban_user', 'ban_video', 'unban_video')),
    target     TEXT        NOT NULL CHECK (char_length(target) BETWEEN 1 AND 100),
    reason     TEXT        CHECK (char_length(reason) <= 200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX admin_actions_created_at_idx ON admin_actions (created_at DESC);
