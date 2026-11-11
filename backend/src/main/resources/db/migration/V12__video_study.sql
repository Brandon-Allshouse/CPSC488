-- The summary and quiz questions the LLM wrote for a video (see study/StudyGenerator). Each video
-- gets one, written the first time someone asks, and everyone shares it. LLM output is untrusted:
-- the backend checks every field before saving, and the frontend shows it as plain text only.
-- Banning a video deletes it from videos, which deletes this row too.
CREATE TABLE video_study (
    youtube_id TEXT        PRIMARY KEY REFERENCES videos (youtube_id) ON DELETE CASCADE,
    summary    TEXT        NOT NULL CHECK (char_length(summary) BETWEEN 1 AND 1000),
    -- [{ question, options: [4 strings], answerIndex, explanation }], 1 to 3 of them
    questions  JSONB       NOT NULL CHECK (jsonb_typeof(questions) = 'array'
                                           AND jsonb_array_length(questions) BETWEEN 1 AND 3),
    -- which Ollama model wrote it, so old ones can be found if we switch models
    model      TEXT        NOT NULL CHECK (char_length(model) BETWEEN 1 AND 100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- for the daily generation limit, which counts rows from the last 24 hours
CREATE INDEX video_study_created_at_idx ON video_study (created_at);
