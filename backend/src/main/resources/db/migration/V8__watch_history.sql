-- The videos each user pressed play on, newest first. Like every migration: once it has run
-- anywhere, don't edit it.
--
-- One row per user and video: watching it again just moves it to the top. WatchHistoryRepository
-- keeps only the newest 25 per user, deleting older rows in the same transaction. History is
-- private to its user (admins can't see it), is deleted with the account, and loses a video when
-- an admin bans it (the video row is deleted, so this cascades).
CREATE TABLE watch_history (
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    youtube_id TEXT        NOT NULL REFERENCES videos (youtube_id) ON DELETE CASCADE,
    watched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, youtube_id)
);

-- Listing and trimming a user's history both read their rows newest first.
CREATE INDEX watch_history_user_watched_idx ON watch_history (user_id, watched_at DESC);
