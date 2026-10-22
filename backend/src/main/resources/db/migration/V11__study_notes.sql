-- Personal notes belong to a user's existing notebook entry.
-- Removing that entry (or its user/video) removes the associated note.
CREATE TABLE study_notes (
    user_id BIGINT NOT NULL,
    youtube_id TEXT NOT NULL,
    note TEXT NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, youtube_id),
    FOREIGN KEY (user_id, youtube_id)
        REFERENCES saved_videos (user_id, youtube_id) ON DELETE CASCADE,
    CONSTRAINT study_notes_length CHECK (char_length(note) <= 2000)
);
