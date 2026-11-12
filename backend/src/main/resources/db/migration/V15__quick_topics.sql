-- Quick topics (see topics/TopicFinder): a user types a topic name and it's added right away,
-- then the LLM checks it in the background (topics/TopicReviewer).

-- When the LLM looked at an AI-made topic. NULL means it's still waiting, so a restart picks the
-- waiting ones up again instead of skipping their moderation check.
ALTER TABLE topics ADD COLUMN reviewed_at TIMESTAMPTZ;

-- Topics from the old finder were already written by the LLM, so they count as reviewed.
UPDATE topics SET reviewed_at = created_at WHERE source = 'ai';

CREATE INDEX topics_unreviewed_idx ON topics (id) WHERE source = 'ai' AND reviewed_at IS NULL;

-- Where a new topic goes when we can't tell its subject yet. Seeded, so an admin can't remove it
-- (and everything under it) by accident; the review moves topics out of it when it can.
INSERT INTO topics (name, search_query) VALUES ('Explore', 'learn something new explained');
