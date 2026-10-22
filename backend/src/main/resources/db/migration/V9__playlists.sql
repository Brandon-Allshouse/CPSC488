-- Saved videos: each user has a "Watch later" playlist (made automatically the first time they
-- open their playlists) plus any playlists they create. A video can be in several playlists.
-- Like every migration: once it has run on anyone's machine, don't edit it; add a new file.

CREATE TABLE playlists (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        TEXT        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 50),
    -- The built-in "Watch later" playlist, which can't be renamed or deleted.
    watch_later BOOLEAN     NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- No two playlists of one user with the same name ("Math" and "math" count as the same), and at
-- most one Watch later each. PlaylistRepository looks for these names in duplicate-key errors.
CREATE UNIQUE INDEX playlists_user_name_key ON playlists (user_id, lower(name));
CREATE UNIQUE INDEX playlists_one_watch_later ON playlists (user_id) WHERE watch_later;

-- Deleting a playlist removes its entries. Deleting a video (e.g. an admin bans it) removes it
-- from every playlist.
CREATE TABLE playlist_videos (
    playlist_id BIGINT      NOT NULL REFERENCES playlists (id) ON DELETE CASCADE,
    youtube_id  TEXT        NOT NULL REFERENCES videos (youtube_id) ON DELETE CASCADE,
    added_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (playlist_id, youtube_id)
);

CREATE INDEX playlist_videos_youtube_id_idx ON playlist_videos (youtube_id);