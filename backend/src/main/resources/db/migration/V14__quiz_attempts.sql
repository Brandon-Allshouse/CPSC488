-- Quiz scoring: every signed-in user's answers, and their daily streak (see progress/ and the
-- README's "How scoring works"). The backend checks each answer against video_study, so the
-- browser never decides what's right.

-- One row per user per question: only the first answer counts. The unique constraint is what makes
-- that race-safe, since two answers sent at once can't both insert.
--
-- youtube_id points at the quiz, not the video. When an admin bans a video its quiz is deleted
-- (video_study cascades from videos) and youtube_id here becomes NULL: the points stay, because the
-- user answered in good faith, but the row no longer blocks anyone. If the video is ever unbanned
-- and gets a new quiz, those are new questions and can be answered again. NULLs never clash in a
-- unique constraint, so any number of detached rows can exist.
CREATE TABLE quiz_attempts (
    id             BIGSERIAL   PRIMARY KEY,
    user_id        BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    youtube_id     TEXT        REFERENCES video_study (youtube_id) ON DELETE SET NULL
                               CHECK (youtube_id ~ '^[A-Za-z0-9_-]{11}$'),
    -- a quiz has at most 3 questions (StudyParser.MAX_QUESTIONS) of 4 options each
    question_index SMALLINT    NOT NULL CHECK (question_index BETWEEN 0 AND 2),
    choice         SMALLINT    NOT NULL CHECK (choice BETWEEN 0 AND 3),
    correct        BOOLEAN     NOT NULL,
    -- copied in at answer time, so changing the points rule later doesn't rewrite old scores
    points         INTEGER     NOT NULL CHECK (points BETWEEN 0 AND 100 AND (correct OR points = 0)),
    answered_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT quiz_attempts_one_per_question UNIQUE (user_id, youtube_id, question_index)
);

-- for "this week" on the leaderboard, which sums points answered since Monday
CREATE INDEX quiz_attempts_answered_at_idx ON quiz_attempts (answered_at);

-- Days in a row (UTC) with at least one correct first answer. Stored rather than worked out from
-- quiz_attempts each time, so the leaderboard doesn't have to scan everyone's history. A streak
-- whose last_day is before yesterday has ended; the leaderboard shows it as 0.
CREATE TABLE quiz_streaks (
    user_id      BIGINT  PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    current_days INTEGER NOT NULL CHECK (current_days >= 1),
    last_day     DATE    NOT NULL
);
