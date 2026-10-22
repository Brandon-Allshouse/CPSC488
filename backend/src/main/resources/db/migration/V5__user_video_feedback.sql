-- One like or dislike per user and video. Records are private to the user who created them.
CREATE TABLE user_video_feedback (
    user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    youtube_id TEXT   NOT NULL REFERENCES videos (youtube_id) ON DELETE CASCADE,
    feedback   TEXT   NOT NULL CHECK (feedback IN ('liked', 'disliked')),
    PRIMARY KEY (user_id, youtube_id)
);
