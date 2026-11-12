-- Progress is private and belongs to an existing Study notebook entry.
CREATE TABLE learning_progress (
    user_id BIGINT NOT NULL,
    youtube_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'planned',
    completed_at TIMESTAMPTZ,
    reviewed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, youtube_id),
    FOREIGN KEY (user_id, youtube_id)
        REFERENCES saved_videos (user_id, youtube_id) ON DELETE CASCADE,
    CONSTRAINT learning_progress_status CHECK (status IN ('planned', 'in_progress', 'completed')),
    CONSTRAINT learning_progress_completion CHECK ((status = 'completed') = (completed_at IS NOT NULL)),
    CONSTRAINT learning_progress_review CHECK (
        reviewed_at IS NULL OR (status = 'completed' AND reviewed_at >= completed_at))
);
