# Security

BrainFeed is a class project, but we build it to real-world security standards. This file lists
those standards, shows where the code meets each requirement, and notes what isn't covered yet.
If your change touches accounts, sessions, user input or the database, check it against this file
before opening a PR.

## Standards

- **NIST SP 800-63B** (Digital Identity Guidelines: Authentication), rev. 4, at AAL1. Password,
  login and session rules.
- **NIST SP 800-218**, the Secure Software Development Framework (SSDF). How we write, test and
  maintain the code.
- **OWASP ASVS 4.0** (Application Security Verification Standard), Level 1 plus some Level 2
  controls. A checklist of what a secure web app should do.
- **OWASP Top 10 (2021)** and the **OWASP Cheat Sheet Series**. The most common web app
  vulnerabilities, and practical guidance for avoiding them.

Nobody outside the team has audited the project. The tables below show what we implemented, not
what a third party has verified.

## Architecture and trust boundaries

```
Browser ──HTTPS──▶ React frontend ──/api (same origin)──▶ Javalin backend ──JDBC──▶ PostgreSQL
                                                                  └──HTTP──▶ Ollama (LLM)
```

- The browser and frontend never talk to the database. Postgres listens on `127.0.0.1` only, and only
  the backend holds its credentials. In `docker-compose.yml` the database is on the `data` network
  and the frontend is on the `web` network; only the backend is on both, so the frontend container
  has no network path to Postgres.
- The same goes for Ollama, which runs the LLM. It has no login of its own, so it gets no host port
  at all and sits on its own `llm` network with only the backend.
- Everything arriving at the backend is untrusted and is validated there. Frontend validation is
  only for user convenience.

## Controls

### Passwords (NIST SP 800-63B §3.1.1, OWASP Password Storage Cheat Sheet)

| Requirement | Implementation |
|---|---|
| Minimum 15 characters for single-factor passwords | `PasswordPolicy.MIN_LENGTH` |
| Allow at least 64 characters; all printable characters, spaces and Unicode allowed | Max 128 code points (ASVS 2.1.2). No character restrictions |
| No composition rules, no forced periodic changes, no hints or security questions | None exist |
| No truncation | Argon2id has no length limit; passwords are never trimmed |
| Unicode normalization | NFKC before hashing and length checks |
| Block breached, context-specific and repetitive passwords | Have I Been Pwned k-anonymity check, plus username/email/site name and repetition checks |
| Offer to show the password while typing | Show/Hide toggle on the login page |
| Salted, memory-hard one-way hash | Argon2id, m=19 MiB, t=2, p=1, 128-bit random salt per password |
| Secret pepper stored separately from hashes | HMAC-SHA256 with `PASSWORD_PEPPER`, which is kept in the environment, never in the DB |
| Database refuses anything but a hash | `CHECK` constraint `users_password_is_argon2id` |

### Authentication (NIST SP 800-63B §3.2, OWASP Authentication Cheat Sheet)

| Requirement | Implementation |
|---|---|
| Rate limit failed attempts (NIST: at most 100) | Per 15 minutes (`LoginThrottle`): 10 failures per IP, 10 per account from any one IP, and 100 per account from all IPs together. 20 sign-ups/hour per IP |
| Strangers can't lock you out | Wrong passwords from one IP only block that IP (for that account, and after 10 failures for every account). Blocking an account everywhere takes 100 failures, which needs at least 10 different IPs, since each IP is cut off at 10. A correct password clears the account's counts but not the IP's, so an attacker can't earn more guesses by logging into their own account. Before 2026-10-27 a single per-account limit of 10 let anyone who knew an email lock its owner out indefinitely |
| Generic failure messages | "Incorrect email or password" in every case |
| No timing-based account enumeration | Unknown emails are checked against a dummy Argon2id hash |
| Resist hashing-based denial of service | At most 4 concurrent password hashes; 16 KB request body cap |

### Sessions (NIST SP 800-63B §7, OWASP Session Management Cheat Sheet)

| Requirement | Implementation |
|---|---|
| At least 64 bits of token entropy from a CSPRNG | 256-bit `SecureRandom` token |
| Tokens not stored in usable form | Only the SHA-256 hash is stored; a DB `CHECK` enforces it |
| New session on login (prevents session fixation) | New token on every login and sign-up, and the browser's old session is deleted from the database (before 2026-11-11, signing up while logged in left the old session alive) |
| Absolute and idle timeouts | 7 days absolute (NIST allows ≤30 at AAL1), 24 hours idle |
| Server-side logout | The session row is deleted and the cookie cleared |
| Cookie hardening | `HttpOnly`, `SameSite=Strict`, and in production `Secure` + the `__Host-` prefix |

### Injection and web security (OWASP Top 10 A03, A05)

| Threat | Mitigation |
|---|---|
| SQL injection | Only parameterized `PreparedStatement`s with constant SQL |
| XSS | React auto-escaping; no `dangerouslySetInnerHTML`/`innerHTML`/`eval`; strict CSP on the production build |
| CSRF | `SameSite=Strict` cookie, JSON-only writes, and an `Origin` allow-list |
| Clickjacking / MIME sniffing | `X-Frame-Options: DENY`, `frame-ancestors 'none'`, `nosniff` |
| Unexpected input | Strict JSON (unknown fields rejected), length and format validation, DB `CHECK` constraints |
| Information leakage | Generic 500 errors, no stack traces to clients, no server version header, `Cache-Control: no-store` |
| Default credentials | None. The backend and docker compose refuse to start without `DB_PASSWORD` and `PASSWORD_PEPPER`, and the optional pgAdmin refuses to start without `PGADMIN_PASSWORD` |

### Interests, feed and YouTube (OWASP Top 10 A01, A03, A08; ASVS V4, V5, V12)

| Requirement | Implementation |
|---|---|
| Users can only read or change their own interests | `/api/me/interests` takes the user from the session cookie only; the request has no user id to tamper with. Saving locks the user's row first, so two saves at once (double click, two tabs) take turns instead of one failing with a 500 |
| Validate all input | Topic ids must be real topics (at most 200 per feed request or interests save); feed `page` is capped; unknown JSON fields rejected; DB `CHECK`s on video ids and text lengths |
| API key never reaches the browser | `YOUTUBE_API_KEY` lives in `.env`; only `YouTubeClient` (backend) calls YouTube, and its error messages never include the key |
| YouTube data is untrusted | Video ids must match YouTube's 11-character format; titles are stripped of control characters, length-capped (never cutting an emoji in half) and shown only as React text. A reply that isn't JSON (a proxy's error page) is reported with our own short message, so none of it ends up in the logs. In the page, video ids are only used through `encodeURIComponent` (for element ids and URLs) and looked up with `getElementById`, never inserted into HTML or CSS selectors |
| Browsing can't exhaust API quota | The feed is served from the database. Opening it can only trigger searches for topics that have never been searched (at most 5 per request, and never the same topic twice at once). Those and the hourly refresh share a budget of 80 searches per 24 hours, counted in the database so restarts don't reset it, and new-topic searches pause for 15 minutes after a YouTube error |
| Thumbs up/down are private and can't be tampered with | Feedback writes and reads require a session; the user comes from the session cookie, video ids are validated, and a database primary key enforces one choice per user/video. List queries filter on that session-derived user id. Guests are not allowed to save or view feedback |
| Watch history is private | Every `/api/me/watch-history` endpoint takes the user from the session cookie only, and every query filters on that user id. Admins have no endpoint for it either. Users can remove single videos or clear it all; it's deleted with the account. Only the newest 25 plays are kept (oldest deleted in the same transaction), and only pressing play records one, never scrolling past |
| Saved videos and playlists are private | Every `/api/me/playlists` endpoint takes the user from the session cookie only, and every query filters on that user id, so a guessed playlist id gets the same 404 as one that doesn't exist (no IDOR, no way to learn which ids exist). Playlist ids must be positive numbers and video ids must match YouTube's format. Names are trimmed, 1-50 characters with no control characters, backed by a DB `CHECK`, and shown only as React text. Each user is capped at 50 playlists of 500 videos, so one account can't fill the database. The count and the insert run in one transaction that first locks the user's row (or the playlist's row, which also re-checks it's theirs) with `FOR NO KEY UPDATE`, so parallel requests take turns and can't overshoot the cap (checked on 2026-10-27: 60 creates at once made exactly 50). The built-in Watch later playlist can't be deleted. Playlists are deleted with the account, and banned videos drop out of every playlist |
| Study notebook and notes are private | `/api/me/saved-videos` and `/api/me/study-notes` read the user from the session, accept no client-selected user id, and use owner-scoped prepared statements. Each user can keep at most 500 notebook videos, counted inside a transaction that locks the user's row first, so parallel saves can't overshoot it. Notes require an existing notebook entry, have a 2,000-character backend and database limit, and render only as text. Removing a notebook entry cascades to its note. Explicit text downloads include only that account's saved notebook notes. |
| Links out to YouTube and sharing | "Watch on YouTube" links are built only from the validated, URL-encoded video id and open with `target="_blank" rel="noopener noreferrer"`, so the new tab can't reach back into BrainFeed (reverse tabnabbing) and gets no referrer. "Copy link" writes that same URL with the async Clipboard API, only when the user clicks; it never reads the clipboard |
| Summary numbers don't leak or invent data | The progress box, Watch history and Your videos summaries are computed in the browser from the signed-in user's own lists (already private, see above); guests get none and nothing new is sent to the server. Channel names in them render as React text. The login page's subject counts come from the public `/api/topics`. The Leaderboard only shows real scores from the backend (see "Quiz scoring and leaderboard") |
| Limit what the embedded player can do | Privacy-enhanced `youtube-nocookie.com` player, loaded only when the user presses play, in a `sandbox`ed iframe; the CSP allows only `www.youtube-nocookie.com` frames and `i.ytimg.com` images |

### Video summaries and quizzes (OWASP Top 10 for LLM Applications 2025, OWASP Top 10 A03, A04)

| Requirement | Implementation |
|---|---|
| No paid API, no keys, no data sent to an AI company | The model (`gemma4:e4b` by default, Apache 2.0) runs in Ollama on our own machine. Nothing about users is ever in a prompt: only the video's title, channel, topic and YouTube description |
| Nobody else can use our LLM | Ollama has no authentication, so it's never published on a host port. It's on the `llm` Docker network with only the backend; the frontend container and the database can't reach it either. `OLLAMA_URL` comes only from settings, never from a request (no SSRF), and the client doesn't follow redirects |
| Prompt injection from video text (LLM01) | Titles and descriptions are written by uploaders, so they're treated as untrusted. Our instructions are in the system message; the video text is in the user message inside `<video>` tags, with `<`, `>`, links and control characters removed so it can't close the tag or add fake lines, and the description capped at 1,000 characters. Before that, `DescriptionCleaner` drops links, hashtags, promotion lines and sentences unrelated to the title; it only picks which of the uploader's sentences to keep, and the tag cleanup still runs on whatever it keeps. The model has no tools, no secrets and no user data, so the worst a successful injection can do is write a bad summary for that one video. A summary that repeats a run of our instructions is rejected (`StudyParser.echoesInstructions`) |
| LLM output is untrusted (LLM05) | Ollama only lets the model produce JSON matching our schema. `StudyParser` then checks every field: text is cleaned and length-capped (summary 1,000, question 300, option 200, explanation 500), each question needs exactly 4 different options, and a question is dropped unless its `answer` matches one option exactly, so a quiz never ships a wrong answer key that failed this check. Stored with placeholders, backed by DB `CHECK`s, and shown only as React text (never HTML) |
| Can't be used to overload the server (LLM10) | Generation only starts for videos already in our database (ids validated). One video at a time on its own thread, at most 20 waiting (then `busy`), each video written once and shared, at most 500 a day (counted in the database so restarts don't reset it), at most 50 new ones an hour from one IP (asking again for one that's waiting doesn't count; finished ones are always served), one retry for bad output, a 6-hour wait after a video fails twice, and a 1-minute pause after Ollama errors. A 4-minute timeout stops a stuck request, and every model reply is capped (`num_predict`; Ollama's default is no limit): 1,024 tokens for summaries, 2,048 for topic reviews, so a model stuck repeating itself can't hold the worker until the timeout |
| Prefetching from the feed stays bounded (LLM10) | Serving a feed page lines up summaries for at most its first 5 videos, at low priority. It has its own, smaller budgets on top of the ones above: at most 8 of the 20 places in line (the rest are kept for people who pressed play), it stops once 300 were written in 24 hours (so guests scrolling can never use up the 500; the last 200 are only for videos someone asked for, and the 300 counts videos already waiting), and at most 30 an hour per IP (separate from the 50 for pressing play). Already-written, waiting and recently failed videos are skipped, a prefetch nobody asked for in 10 minutes is dropped without calling the model, and the limits are checked again when its turn comes. Nothing about the user goes in the prompt, and if prefetching fails the feed still loads. Prefetched videos wait behind requests made up to 2 minutes later, so they can't delay someone who pressed play for long and can't be starved either |
| Doesn't spend the YouTube quota | Each new summary costs 1 unit (videos.list, description only), and the 500/day cap (prefetched ones included) keeps that under the ~2,000 units the feed's search budget leaves free |
| Honest about what it is (LLM09 Misinformation) | The box says it was written by AI from the title and description and can get things wrong. The prompt tells the model to stick to the description and well-known facts and not guess what the video shows. Small models still make mistakes (see Known gaps) |
| Admins can remove bad output | Banning a video deletes its summary (foreign key `ON DELETE CASCADE`) |

### Quiz scoring and leaderboard (OWASP Top 10 A01, A04, A07; ASVS V4, V5, V11)

| Requirement | Implementation |
|---|---|
| The browser can't see or decide the answer | `GET /api/videos/{id}/study` leaves out `answerIndex` and the explanation. `POST .../study/answers` checks the choice against the saved quiz (`StudyRepository.find`) and only then returns the right option and explanation. The client sends only a question number and a choice, never "correct" or points; unknown fields (like `points` or `userId`) are rejected |
| No farming points | Only a user's first answer to each question is saved and scored (unique constraint `quiz_attempts_one_per_question`). Each answer runs in one transaction that locks the user's row with `FOR NO KEY UPDATE` and inserts with `ON CONFLICT DO NOTHING`, so parallel answers can't both be first (tested with 20 at once over HTTP and 40 in the repository: exactly one counted). Points are a server constant (10), and DB `CHECK`s refuse points for a wrong answer or more than 100 |
| Validate all input | Video ids must match YouTube's format; `question` and `choice` must be JSON whole numbers (not strings, decimals or null), 0-2 and 0-3, and must exist in that quiz. Bad input is a 400, never a 500, and nothing is saved. DB `CHECK`s back up the ranges |
| Rate limits | 30 answers a minute per account and 120 per IP (higher, since a class can share one IP), counted before the input is checked so a flood of bad requests is limited too; 30 leaderboard loads a minute per IP. Hitting a limit is a 429 and a `security` log line (`quiz_answer_rate_limited`, `leaderboard_rate_limited`) |
| Users only score for themselves | The user comes from the session cookie only. Guests can check answers, but nothing is saved and they earn no points |
| The leaderboard doesn't leak personal data | `/api/leaderboard` is public but returns only usernames, points, correct answers and streaks: never emails, user ids or IPs. "Your rank" comes from the session, not the request. Banned users are left out (and so is their rank), and deleting an account deletes its scores (`ON DELETE CASCADE`). Usernames render as React text |
| Banning a video | The quiz is deleted with the video; earned points stay, but the answers are detached (`ON DELETE SET NULL`), so they can't block a future quiz and the answer check returns 404 |

### Adding topics and the AI review (OWASP Top 10 for LLM Applications 2025, OWASP Top 10 A01, A03, A04, A10)

Users type a topic name (`POST /api/me/topics`) and it's added at once, with no LLM call in the
request. The model reviews each new topic afterwards on its own thread (`TopicReviewer`).

| Requirement | Implementation |
|---|---|
| Only accounts can add | `/api/me/topics` needs a session (401 otherwise), so every added topic is tied to an account and its limits. Guests can only pick topics we already have, in their browser. The usual CSRF (JSON-only, Origin check), body-size and unknown-field rules from `App.configure` apply |
| Typed names are strictly validated (A03) | `TopicNames.check`, in the backend (the browser's copy is only for quick feedback): Unicode NFKC, invisible format characters (zero-width spaces, direction marks) removed, control characters refused, spaces collapsed, then 1-3 words, 2-40 characters, letters and digits plus spaces and `& + ' - . #` only, starting with a letter or digit and with at least one letter. No angle brackets, quotes, backticks, slashes or colons, so no markup, links or prompt tags. Bad input is a `400` with a plain message, never a `500` (tested with bad JSON, wrong types, NUL bytes, URLs, markup and over-long names). The database also refuses control characters. Names are shown only as React text |
| Fast deterministic denylist before anything is created | `TopicDenylist` blocks obvious sexual terms, slurs and phrases like "how to kill" or "bomb making", after undoing case, accents, look-alike digits (`p0rn`) and spacing tricks (`p.o.r.n`). It's kept short so real subjects ("Nazi Germany", "Suicide Prevention", "Fire Retardants") aren't blocked; the review and admins handle anything that needs judgment. A blocked name gets the same `409 That topic can't be added.` as a banned one, so it doesn't reveal which list it's on, and the refusal is in the `security` log |
| Removed names stay removed | Before creating, `banned_topic_names` is checked for the name and its singular and plural forms, inside the same locked transaction as the insert |
| No duplicates or near-copies (race-safe) | `TopicMatcher` finds the topic the user meant first: same name ignoring case and punctuation, singular or plural, or a small typo (scaled to length, none for 4 letters or fewer, first letter and digits must match). If nothing matches, the match is run again inside a transaction holding a Postgres advisory lock before inserting, so parallel requests for "Comet Tail" and "COMET TAILS" make one topic (tested with 12 at once). Names are also unique ignoring case (`topics_name_lower_idx`) |
| Abuse and cost limits (LLM10, A04) | 60 requests an hour per account and 120 per IP (each is a few indexed queries, so these only stop scripts). New topics: 10 a day per account, 200 a day overall, at most 40 subjects and 2,000 topics, and a user can follow at most 200 topics (the feed's limit). The limits are counted inside the locked transaction, so parallel requests can't overshoot them. The review runs one topic at a time on its own thread, once per new topic, so it's bounded by the daily limits. Topics nobody follows are never refreshed from YouTube, and new topics' first searches share the 80-a-day budget |
| No SSRF through the YouTube search (A10) | A topic name only ever becomes the `q` parameter of the fixed `googleapis.com` search URL, URL-encoded by `YouTubeClient.searchUrl`. Users can't choose a host, path or other parameters, and the name can't contain `/` or `:` anyway |
| Prompt injection in the review (LLM01) | The topic name (already validated as above) and the subject list are cleaned with `UntrustedText.forPrompt` and kept inside `<topic>` and `<subjects>` tags in the user message; our rules are in the system message, which says never to follow instructions inside the topic. The model has no tools, secrets or user data. The worst a successful injection can do is choose a subject or search query, which are checked next |
| Review output is untrusted (LLM05) | Schema-constrained JSON, then `TopicParser`: `harmful` must be a real boolean (anything else and the reply is discarded, then retried once); a subject must be 1-3 words and 1-30 characters of the allowed characters, pass the denylist and not be banned; a search query must match the query format and be at most 80 characters. Anything unusable leaves the topic where it is. The review can only move a topic to a subject (existing, new within the 40-subject cap, or the topic itself when it's a broad field); it can never touch seeded topics |
| Harmful topics are removed automatically | When the review flags a topic, `AiTopicRepository.removeFlagged` deletes it (and its videos and interests), adds the name to `banned_topic_names`, and logs `remove_topic` in `admin_actions` with no admin, all in one transaction, the same way an admin removal works. A `topic_flagged` line goes to the `security` log with the account that added it. Admins can still remove anything the review missed |
| Reviews survive restarts | Topics waiting for review have `reviewed_at IS NULL` (V15), and the backend queues them again at startup, so a restart or an Ollama outage delays moderation instead of skipping it |
| Who added what stays private | `created_by` is only shown to admins (`/api/admin/topics`). `/api/topics` only says `ai: true` |

### Admin access and bans (OWASP Top 10 A01, ASVS V4)

| Requirement | Implementation |
|---|---|
| Only admins can use admin endpoints | A `before` handler on `/api/admin/*` checks the session and the `is_admin` flag on every request, including for endpoints added later. Each handler also refuses if that check didn't run. The frontend hides the Admin menu from non-admins, but that's only convenience |
| No way to escalate privileges through the app | There's no endpoint that grants admin. It's set by hand in the database (README, "Admin accounts"). The flag is re-read on each request, so removing it works right away |
| One admin can't lock out the others | Admins can't ban themselves or other admins; a DB `CHECK` (`users_admin_not_banned`) backs this up |
| A ban takes effect immediately | Banning deletes all of the user's sessions in the same transaction, and `UserRepository.findById` ignores banned users, so an old cookie can't be used either |
| Bans don't reveal which accounts exist or are banned | Login only says "suspended" after the correct password; a wrong password gets the usual generic message |
| Bad user-added topics can be removed for good | `DELETE /api/admin/topics/{id}` only works on topics users added (seeded ones get 404), needs a reason, blocks the name, and is logged in `admin_actions` (`remove_topic`) in the same transaction. Removals by the AI review are logged the same way with no admin |
| Banned videos stay gone | `banned_videos` is a blacklist that `VideoRepository.saveAll` checks, so YouTube refreshes can't bring a video back. Banning deletes the video and everyone's likes of it |
| Admins are accountable | Every ban and unban is written to `admin_actions` (who, what, when, why) in the same transaction as the change, and to the `security` log. A reason is required, limited to 200 characters on one line |
| Audit log export is safe to open (OWASP CSV Injection) | `GET /api/admin/actions/export` is admin-only (path-based check plus its own check in `ExportController`) and listed in `AccessControlTest`. `CsvFile.cell` quotes every cell and prefixes any cell starting with `= + - @`, tab or CR with `'`, so a ban reason like `=HYPERLINK(...)` shows as text in Excel. The file is written to a random `Files.createTempFile` name (owner-only on Linux, since it holds admin IPs), deleted in `finally`, and each download is logged as `event=audit_log_export` |
| Admin input is validated like any other | User ids must be positive numbers, video ids must match YouTube's format, and search text is length-capped with `%`, `_` and `\` escaped so they can't act as wildcards |

### Users' IP addresses (OWASP Top 10 A01, A09; OWASP Logging Cheat Sheet)

| Requirement | Implementation |
|---|---|
| Log the real client, not our proxy | `ClientIp` reads `X-Forwarded-For` from right to left and stops at the first address that isn't one of our `TRUSTED_PROXIES`. Rate limits, the security log and the admin page all use it |
| A client can't fake their IP | The header is ignored unless the connection comes from a trusted proxy, and anything the client wrote into it themselves sits to the left of what our proxy appended, so it's never reached. At most 20 hops are read |
| No lookups driven by attackers | Header values are parsed with `InetAddress.ofLiteral`, which never does DNS. Only hostnames from our own settings are looked up (cached for 30 seconds) |
| Only valid addresses are stored | Stored as Postgres `INET` through `?` placeholders, after `ClientIp.normalize` turns anything that isn't an IP into `NULL` |
| IP addresses are personal data | Only admins can see them (sign-up IP, last login IP and time, and the IP of each admin action). `/api/auth/me` and every other user-facing response leave them out. Only the latest login IP is kept, not a history |

### Logging (OWASP Top 10 A09, OWASP Logging Cheat Sheet)

Login, logout, registration, rate-limit and policy rejections, banned-account logins, refused
admin access, blocked cross-site requests (with the origin), every topic a user added
(`topic_added`), refused topic adds (`topic_add`: blocked names and limits), topics the AI review
removed (`topic_flagged`), quiz answers and leaderboard loads over their rate limits, and every admin ban,
unban or topic removal are logged under the `security` logger
with the outcome, user id, IP and user agent. Passwords, tokens and email addresses are never logged,
and control characters are stripped so user input can't forge log lines.

### Secure development process (NIST SSDF)

| Practice | How |
|---|---|
| PO.1 Define security requirements | This document |
| PS.1 Protect code and secrets | `.env` is gitignored; secrets come only from the environment |
| PW.4 Reuse well-secured components | Maintained libraries only (Javalin, BouncyCastle, Flyway, HikariCP, pgJDBC; React, Tailwind CSS, lucide-react) with versions pinned in lockfiles, on long-term support (LTS) releases of Java and Node. New packages must pass `npm audit` and be at least two weeks old when added, since brand-new releases are where supply-chain attacks hide. shadcn/ui components are copied into the repo as source, so they're reviewed like our own code |
| PW.7 Review code | Security-relevant PRs are reviewed against this document; CodeQL runs on every PR |
| PW.8 Test | Unit tests run in CI. Backend: hashing, password policy, breach-list matching, rate limiting (including that guesses from one IP can't lock the account out for everyone else), transactions committing or rolling back as a whole, log sanitizing, email/username rules, pepper and `.env` loading, YouTube response handling, feed and feedback input checks, admin input checks and ban rules (no banning yourself or other admins), playlist name and id checks, the 25-video watch history limit, request bodies that fail to parse (bad JSON, unknown fields) becoming a 400 with no parser details, finding the real client IP (including spoofed and garbled `X-Forwarded-For` headers), the YouTube search budget and new-topic fetching, LLM output checks (wrong answer keys, duplicate options, length caps, echoed instructions, thinking text), prompt cleaning (video text can't close its tag or add lines), the summary queue limits, daily limit, per-IP limit, retries and pauses, the summary prefetch queue (requests go first, a waiting prefetch is moved up instead of queued twice, aging, stale prefetches dropped without calling the model) and its budgets (its share of the line, its daily share, its per-IP limit, and a feed page queueing only its first videos over real HTTP), the description cleanup (links, hashtags, promotion and unrelated sentences removed, never over the budget, an injection in a kept sentence still can't close the tag), the timing fields read from Ollama and the same context size on every request, a failed model warm-up at startup only being logged, the summary endpoint's id checks, the reply length cap sent to Ollama, background work that can't be started (worker shut down) not leaving videos or topics stuck as pending, YouTube error pages not being logged, text caps never splitting an emoji, and security log lines staying on one line with no forged entries. Frontend: input validation, the API helper's CSRF header, and every page and component (including that YouTube titles and channel names render as plain text, the player is sandboxed, the feed stops checking for new videos after a minute or when the user leaves, saved feedback appears in the user's lists, non-admins can't reach the admin page, bans need a reason, and pasted YouTube links are parsed safely, only pressing play adds to the watch history, and it's never kept for guests, playlist names render as plain text, guests get no Save button and never request playlists, "Watch on YouTube" links use `noopener noreferrer`, and summary numbers render channel names as plain text and are never shown to guests, AI summaries render as plain text, the summary only starts on play or a click, and checking for it stops after 3 minutes or when the card goes away). Database tests run every repository's SQL against a real Postgres (a separate `brainfeed_test` database, in Docker and in CI): queries only return the caller's own rows, another user's playlist id looks the same as a missing one, the playlist, notebook and AI topic limits hold under parallel requests, cascades and `CHECK` constraints work, session tokens are stored hashed and expire (idle and absolute), and banning logs the user out. `AccessControlTest` wires every controller like `App.java` and checks that each `/api/me` route refuses guests (401) and each `/api/admin` route refuses non-admins (403); new private routes must be added to its lists. API tests over real HTTP, with the same setup as production (`App.configure`), check the security headers, the CSRF and JSON-only rules, that bugs return a generic error with no internals, the auth rules (generic login errors, hardened cookies, a new session on every login, server-side logout, signing up while logged in ending the old session, oversized logins getting the generic error, throttling after 10 wrong passwords, context-specific passwords refused, bans), the feed, interests and feedback endpoints (bad ids, bodies and query strings are a 400, never a 500, and saving interests twice at once works) and the watch history endpoints. Adding topics is tested over real HTTP with the database (guests get 401, bad input is a 400 and never a 500, denylisted and banned names are a 409, 12 parallel adds of one topic in different spellings make one, the daily and hourly limits, the CSRF rules, and the search query never reaching the browser), plus unit tests for name cleanup, matching (plurals, typos, digits), the denylist (including spelling tricks and real topics it must allow), the subject guess, review prompt cleaning and output checks, and the review moving, re-querying and removing topics. Quiz scoring is tested over HTTP and in the database: the quiz hides its answers, only first answers count (also with 20 sent at once), guests earn nothing, bad input is a 400, the rate limits, streaks across days, the Monday week start, tie ranks, the top 20 plus your own rank, banned users left out, no emails or ids in the leaderboard, and what banning a video does to points |
| RV.1 Find vulnerabilities | Weekly Dependabot PRs (see "Dependency updates" in the README), `npm audit` in CI, and OWASP Dependency-Check run by hand (`mvn verify -P security-scan`), which fails on anything CVSS 7 or higher. Checked false positives go in `backend/dependency-check-suppressions.xml`, each with the reason and an expiry date so it gets looked at again (today: CVE-2026-53914, which is about the Kotlin compiler, not the `kotlin-stdlib` runtime Javalin pulls in) |

## Checklist for new features

New features (likes, history, LLM APIs) bring new risks. Most of the protections above apply
automatically, but a few are up to you:

- **New endpoints** get the security headers and CSRF checks automatically (the `before` handler
  in `App.java`). If an endpoint needs a logged-in user, call `AuthController.currentUser` and
  return 401 when it's empty. For anything that belongs to a user (interests, history, likes),
  also check that it belongs to *that* user, not just that someone is logged in.
- **New tables:** use `?` placeholders for every value, validate input in the backend, and add
  `CHECK` constraints in the migration as a backstop.
- **LLM prompts:** never put user data or secrets in a prompt, keep untrusted text out of the
  system message, and run anything the model writes through checks like `StudyParser` before
  saving or showing it.
- **API keys** (YouTube, LLM) go in `.env` and `.env.example`, never in code. Only the backend
  calls those APIs, so keys never reach the browser.
- **Anything from YouTube or an LLM is untrusted input**, just like user input. Show it as plain
  text through React. Never use it as HTML or put it in SQL without placeholders.
- **New outside domains** (images, embeds, APIs called from the browser) must be added to the
  Content-Security-Policy in `frontend/vite.config.ts`. Add only the specific domains you need,
  not wildcards like `https:`.

## Known gaps and accepted risks

Things we know aren't covered yet, and what would need to change:

1. **Passwords are the only login factor for now (NIST AAL1).** NIST allows this when a stolen account can't do much harm, which is true of interests and liked videos. MFA with an authenticator app (TOTP, such as Google Authenticator) is planned, which would bring us to AAL2. When building it: encrypt each user's TOTP secret with a key kept in `.env`, rate-limit code attempts, accept one code of clock drift either side but never the same code twice, and only ask for the code after the password is correct. Once it's in place, update the Authentication table and the AAL level at the top of this file.
2. **Emails are never verified, and there's no password reset.** We're not sending email, because that would mean buying a domain. As a result:
   - Anyone can sign up with an email address they don't own. Never treat an account's email as proof of who someone is.
   - A user who forgets their password loses the account. The team can only help by editing the database directly. Don't add a reset flow that works without proving the user owns the account (for example, security questions, which NIST forbids). When MFA is added, give each user about 10 one-time recovery codes at setup (stored hashed, like passwords). Those will be the recovery path for a lost phone, since there's no email to fall back on.
   - Sign-up says when an email is already registered, so someone could use it to check whether an address has an account. The sign-up rate limit makes this slow to do in bulk.
3. **No CAPTCHA yet, so bots can create accounts.** The only protection is the rate limit of 20 sign-ups per hour per IP, and someone with many IPs can get around it. A CAPTCHA on sign-up is planned. The CAPTCHA provider's secret key goes in `.env`, the backend must check every token with the provider (never trust the browser's answer), and the Content-Security-Policy will need the provider's domains.
4. **Rate limits are in memory.** They reset on restart and aren't shared across multiple backend instances. Move them to the DB or Redis before scaling out. Someone with 10 or more IPs can still lock one account out for 15 minutes at a time by sending 100 wrong passwords; that's the trade-off for NIST's per-account limit, and MFA (gap 1) is the long-term answer. They're counted per real client IP (see "Users' IP addresses"), so in production `TRUSTED_PROXIES` must name the web server in front of the backend, or every user would share that server's IP and its limits.
5. **The breached-password check fails open** if Have I Been Pwned is unreachable, so an outage doesn't block sign-ups. The other policy rules still apply.
6. **HTTPS is a deployment requirement.** Deploy behind TLS 1.2+, set `COOKIE_SECURE=true`, and send the CSP and `frame-ancestors` as HTTP headers from the web server.
7. **The pepper can't be rotated.** Changing it would stop every existing password from working, and without a password reset those accounts would be lost. Store it in a secrets manager in production.
8. **The topic, interest, video feedback, playlist and feed endpoints have no rate limit.** (Adding topics does, see "Adding topics and the AI review", and so does starting new summaries, see gap 16.) They only do small, indexed database reads and single-row writes, so the risk is low. The feed can start YouTube searches for never-searched topics, but the 80-searches-a-day budget caps that, so a flood can't run up the quota. It could still load the database, or use up the day's budget so other users' new topics wait until tomorrow. Add a per-IP limit (like `LoginRateLimiter`) before any public deployment.
9. **Embedded videos are third-party content.** We can't vet every video YouTube returns; safe search, the Education category and the topic queries are the only filters. Watching a video sends the viewer's IP to YouTube (Google), as with any embed.
10. **pgAdmin is a local development tool only.** It gives full read and write access to the database, so it's locked down: it only starts with `--profile tools`, listens on `127.0.0.1` only, runs as a non-root user, and has its own login. It won't start without `PGADMIN_PASSWORD`, so there's no default password. Never deploy it or expose port 5050. On a shared server, use `psql` over SSH instead.
11. **A banned person can make a new account.** Without email verification or a CAPTCHA (gaps 2 and 3), nothing stops someone signing up again with a different email. Bans stop the account, not the person. Email verification is what would make bans stick.
12. **Admin accounts are the most valuable ones to steal.** An admin can see every user's email and ban anyone who isn't an admin, and admins log in with just a password like everyone else. Keep the number of admins small, use long unique passwords, and when MFA is added (gap 1), require it for admins first.
13. **IP addresses are kept until the next login.** Each account keeps its sign-up IP and its latest login IP indefinitely, and admin actions keep theirs. That's fine for a class project, but a real deployment needs a privacy notice and a retention limit (for example, clearing IPs older than 90 days), since IP addresses count as personal data under privacy laws like the GDPR.
14. **AI summaries and quizzes can be wrong.** The model only sees the title and description, not the video itself, and a 4B model running on a CPU makes mistakes. Our checks catch broken output and answer keys that don't match an option, but not a question where two options are both true, or a wrong fact stated confidently (in testing, `qwen3:4b` invented which star cluster a star is in and gave two different ages for it, which is one reason `gemma4:e4b` is the default). The box says so. A bigger model (`OLLAMA_MODEL`) helps at the cost of speed and RAM; a "report this quiz" button for admins would be the next step.
15. **Fixed 2026-11-11: quiz answers used to be sent to the browser.** The quiz no longer includes `answerIndex` or the explanation; the backend checks each answer and only then reveals them (see "Quiz scoring and leaderboard"). What's left: since guests can check answers, someone could look up an answer as a guest (or on a second account) and then answer on their main account. Only the first answer per account counts, so this takes a second account per question and only earns 10 points each time; email verification and a CAPTCHA (gaps 2 and 3) would make extra accounts harder to get. The rate limits are in memory, like the login limits (gap 4). There's also no way to hide yourself from the public leaderboard; an opt-out setting would be the next step if anyone asks.
16. **Anyone can start a summary, including guests.** The queue, daily cap, once-per-video rule and the limit of 50 new summaries per IP per hour bound the work, but someone with 10 or more IPs could still use up the day's 500 so other users' new summaries wait until tomorrow (saved ones still show). The per-IP counts are in memory, like the login limits (gap 4). Requiring a login to start a new summary would close this.
17. **A typed topic is visible to everyone before the AI review sees it.** Adding is instant, so a name that passes the format rules, the short denylist and the banned list is in the shared catalog for the 10-30 seconds until the review runs, and for longer if Ollama is down (waiting topics are reviewed at the next start). The review is a 4B model and can miss things, and the denylist is deliberately short. The per-account limits, the `security` log entries, the review's automatic removal and the admin removal (both block the name) are the controls. Possible next steps: hide new topics from other users until they're reviewed, or a dedicated moderation model.
18. **Topic request counts live in memory.** Like the login limits (gap 4), the hourly request limits reset on restart and aren't shared between backend instances. The daily limits on new topics are counted in the database, so they hold across restarts.
