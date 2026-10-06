-- Videos saved by logged-in users.
-- Deleting a user removes their saved-video rows.
-- Deleting a cached video also removes it from saved lists.

CREATE TABLE saved_videos (
  user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  youtube_id TEXT NOT NULL REFERENCES videos (youtube_id) ON DELETE CASCADE,
  saved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, youtube_id)
  );

CREATE INDEX saved_videos_user_id_idz ON saved_videos (user_id);
