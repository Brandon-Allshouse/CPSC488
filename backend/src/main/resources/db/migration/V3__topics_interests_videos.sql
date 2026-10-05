-- Interest topics, the topics each user picked, and the YouTube videos the feed is built from.
-- Like every migration: once it has run on anyone's machine, don't edit it; add a new file.

-- The topics users can pick from. search_query is what FeedRefresher searches YouTube for; it's
-- never sent to the browser. To add a topic, insert a row in a new migration.
CREATE TABLE topics (
    id                SERIAL PRIMARY KEY,
    name              TEXT NOT NULL UNIQUE CHECK (char_length(name) BETWEEN 1 AND 50),
    search_query      TEXT NOT NULL CHECK (char_length(search_query) BETWEEN 1 AND 100),
    -- When videos were last fetched for this topic; NULL means never. Each search costs 100 of
    -- the 10,000 daily YouTube API units, so topics are refreshed at most once a day.
    videos_fetched_at TIMESTAMPTZ
);

INSERT INTO topics (name, search_query) VALUES
    ('Programming', 'programming explained'),
    ('Math',        'math explained'),
    ('Physics',     'physics explained'),
    ('Chemistry',   'chemistry explained'),
    ('Biology',     'biology explained'),
    ('Space',       'astronomy space explained'),
    ('History',     'history explained'),
    ('Economics',   'economics explained'),
    ('Psychology',  'psychology explained'),
    ('Geography',   'geography explained');

-- Which topics each registered user picked. Guests' picks only live in their browser.
CREATE TABLE user_interests (
    user_id  BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    topic_id INT    NOT NULL REFERENCES topics (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, topic_id)
);

-- Videos found on YouTube. The feed is served only from this table, so browsing never spends
-- YouTube API quota. Titles come from YouTube and are untrusted: show them as plain text only.
CREATE TABLE videos (
    youtube_id    TEXT        PRIMARY KEY CHECK (youtube_id ~ '^[A-Za-z0-9_-]{11}$'),
    topic_id      INT         NOT NULL REFERENCES topics (id) ON DELETE CASCADE,
    title         TEXT        NOT NULL CHECK (char_length(title) <= 300),
    channel_title TEXT        NOT NULL CHECK (char_length(channel_title) <= 200),
    published_at  TIMESTAMPTZ NOT NULL,
    fetched_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX videos_topic_id_idx ON videos (topic_id);
