-- Topics that users add with AI (see topics/TopicSuggestions). The seeded list from V3/V4 is the
-- starting catalog; AI-made topics are added to it for everyone. Only the backend inserts them,
-- after checking every field the model wrote.

-- 'seeded' came from a migration, 'ai' was suggested by the LLM and added by a user.
ALTER TABLE topics
    ADD COLUMN source     TEXT        NOT NULL DEFAULT 'seeded' CHECK (source IN ('seeded', 'ai')),
    -- who added it. Only admins see this. Kept as NULL if that account is deleted.
    ADD COLUMN created_by BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- AI-made names are shown to everyone, so no control characters (they could fake line breaks)
    ADD CONSTRAINT topics_name_no_control CHECK (name !~ '[[:cntrl:]]'),
    ADD CONSTRAINT topics_query_no_control CHECK (search_query !~ '[[:cntrl:]]');

-- "Algebra 2" and "algebra 2" are the same topic, so AI can't add a near-copy of one we have.
CREATE UNIQUE INDEX topics_name_lower_idx ON topics (lower(name));
-- for the per-user and per-day limits, which count recent AI topics
CREATE INDEX topics_ai_created_idx ON topics (created_at) WHERE source = 'ai';

-- Names an admin removed, so the next person can't just ask the AI to add them again.
CREATE TABLE banned_topic_names (
    name_lower TEXT        PRIMARY KEY CHECK (char_length(name_lower) BETWEEN 1 AND 50),
    reason     TEXT        NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 200),
    banned_by  BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    banned_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Removing an AI topic is an admin action like a ban, so it goes in the same log.
ALTER TABLE admin_actions DROP CONSTRAINT admin_actions_action_check;
ALTER TABLE admin_actions ADD CONSTRAINT admin_actions_action_check
    CHECK (action IN ('ban_user', 'unban_user', 'ban_video', 'unban_video', 'remove_topic'));
