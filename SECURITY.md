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
```

- The browser and frontend never talk to the database. Postgres listens on `127.0.0.1` only, and only
  the backend holds its credentials. In `docker-compose.yml` the database is on the `data` network
  and the frontend is on the `web` network; only the backend is on both, so the frontend container
  has no network path to Postgres.
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
| Rate limit failed attempts (NIST: at most 100) | 10 failures per 15 min per account and per IP; 20 sign-ups/hour per IP |
| Generic failure messages | "Incorrect email or password" in every case |
| No timing-based account enumeration | Unknown emails are checked against a dummy Argon2id hash |
| Resist hashing-based denial of service | At most 4 concurrent password hashes; 16 KB request body cap |

### Sessions (NIST SP 800-63B §7, OWASP Session Management Cheat Sheet)

| Requirement | Implementation |
|---|---|
| At least 64 bits of token entropy from a CSPRNG | 256-bit `SecureRandom` token |
| Tokens not stored in usable form | Only the SHA-256 hash is stored; a DB `CHECK` enforces it |
| New session on login (prevents session fixation) | New token every login, and the old one is deleted |
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
| Users can only read or change their own interests | `/api/me/interests` takes the user from the session cookie only; the request has no user id to tamper with |
| Validate all input | Topic ids must be real topics (at most 200 per feed request); feed `page` is capped; unknown JSON fields rejected; DB `CHECK`s on video ids and text lengths |
| API key never reaches the browser | `YOUTUBE_API_KEY` lives in `.env`; only `YouTubeClient` (backend) calls YouTube, and its error messages never include the key |
| YouTube data is untrusted | Video ids must match YouTube's 11-character format; titles are stripped of control characters, length-capped and shown only as React text. In the page, video ids are only used through `encodeURIComponent` (for element ids and URLs) and looked up with `getElementById`, never inserted into HTML or CSS selectors |
| Browsing can't exhaust API quota | The feed is served from the database. Opening it can only trigger searches for topics that have never been searched (at most 5 per request, and never the same topic twice at once). Those and the hourly refresh share a budget of 80 searches per 24 hours, counted in the database so restarts don't reset it, and new-topic searches pause for 15 minutes after a YouTube error |
| Thumbs up/down are private and can't be tampered with | Feedback writes and reads require a session; the user comes from the session cookie, video ids are validated, and a database primary key enforces one choice per user/video. List queries filter on that session-derived user id. Guests are not allowed to save or view feedback |
| Watch history is private | Every `/api/me/watch-history` endpoint takes the user from the session cookie only, and every query filters on that user id. Admins have no endpoint for it either. Users can remove single videos or clear it all; it's deleted with the account. Only the newest 25 plays are kept (oldest deleted in the same transaction), and only pressing play records one, never scrolling past |
| Saved videos and playlists are private | Every `/api/me/playlists` endpoint takes the user from the session cookie only, and every query filters on that user id, so a guessed playlist id gets the same 404 as one that doesn't exist (no IDOR, no way to learn which ids exist). Playlist ids must be positive numbers and video ids must match YouTube's format. Names are trimmed, 1-50 characters with no control characters, backed by a DB `CHECK`, and shown only as React text. Each user is capped at 50 playlists of 500 videos, inside the `INSERT` itself, so one account can't fill the database. The built-in Watch later playlist can't be deleted. Playlists are deleted with the account, and banned videos drop out of every playlist |
| Study notes are private | `/api/me/study-notes` reads the user from the session, accepts no client-selected user id, and uses owner-scoped prepared statements. Notes require an existing notebook entry, have a 2,000-character backend and database limit, and render only as text. Removing a notebook entry cascades to its note. Explicit text downloads include only that account's saved notebook notes. |
| Limit what the embedded player can do | Privacy-enhanced `youtube-nocookie.com` player, loaded only when the user presses play, in a `sandbox`ed iframe; the CSP allows only `www.youtube-nocookie.com` frames and `i.ytimg.com` images |

### Admin access and bans (OWASP Top 10 A01, ASVS V4)

| Requirement | Implementation |
|---|---|
| Only admins can use admin endpoints | A `before` handler on `/api/admin/*` checks the session and the `is_admin` flag on every request, including for endpoints added later. Each handler also refuses if that check didn't run. The frontend hides the Admin menu from non-admins, but that's only convenience |
| No way to escalate privileges through the app | There's no endpoint that grants admin. It's set by hand in the database (README, "Admin accounts"). The flag is re-read on each request, so removing it works right away |
| One admin can't lock out the others | Admins can't ban themselves or other admins; a DB `CHECK` (`users_admin_not_banned`) backs this up |
| A ban takes effect immediately | Banning deletes all of the user's sessions in the same transaction, and `UserRepository.findById` ignores banned users, so an old cookie can't be used either |
| Bans don't reveal which accounts exist or are banned | Login only says "suspended" after the correct password; a wrong password gets the usual generic message |
| Banned videos stay gone | `banned_videos` is a blacklist that `VideoRepository.saveAll` checks, so YouTube refreshes can't bring a video back. Banning deletes the video and everyone's likes of it |
| Admins are accountable | Every ban and unban is written to `admin_actions` (who, what, when, why) in the same transaction as the change, and to the `security` log. A reason is required, limited to 200 characters on one line |
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
admin access, blocked cross-site requests (with the origin), and every admin ban or unban are
logged under the `security` logger
with the outcome, user id, IP and user agent. Passwords, tokens and email addresses are never logged,
and control characters are stripped so user input can't forge log lines.

### Secure development process (NIST SSDF)

| Practice | How |
|---|---|
| PO.1 Define security requirements | This document |
| PS.1 Protect code and secrets | `.env` is gitignored; secrets come only from the environment |
| PW.4 Reuse well-secured components | Maintained libraries only (Javalin, BouncyCastle, Flyway, HikariCP, pgJDBC; React, Tailwind CSS, lucide-react) with versions pinned in lockfiles, on long-term support (LTS) releases of Java and Node. New packages must pass `npm audit` and be at least two weeks old when added, since brand-new releases are where supply-chain attacks hide. shadcn/ui components are copied into the repo as source, so they're reviewed like our own code |
| PW.7 Review code | Security-relevant PRs are reviewed against this document; CodeQL runs on every PR |
| PW.8 Test | Unit tests run in CI. Backend: hashing, password policy, breach-list matching, rate limiting, log sanitizing, email/username rules, pepper and `.env` loading, YouTube response handling, feed and feedback input checks, admin input checks and ban rules (no banning yourself or other admins), playlist name and id checks, the 25-video watch history limit, finding the real client IP (including spoofed and garbled `X-Forwarded-For` headers), the YouTube search budget and new-topic fetching. Frontend: input validation, the API helper's CSRF header, and every page and component (including that YouTube titles and channel names render as plain text, the player is sandboxed, the feed stops checking for new videos after a minute or when the user leaves, saved feedback appears in the user's lists, non-admins can't reach the admin page, bans need a reason, and pasted YouTube links are parsed safely, only pressing play adds to the watch history, and it's never kept for guests, playlist names render as plain text, and guests get no Save button and never request playlists). The endpoints themselves aren't tested against a real database yet (planned API tests) |
| RV.1 Find vulnerabilities | Weekly Dependabot PRs (see "Dependency updates" in the README), `npm audit` in CI, and OWASP Dependency-Check run by hand (`mvn verify -P security-scan`) |

## Checklist for new features

New features (likes, history, LLM APIs) bring new risks. Most of the protections above apply
automatically, but a few are up to you:

- **New endpoints** get the security headers and CSRF checks automatically (the `before` handler
  in `App.java`). If an endpoint needs a logged-in user, call `AuthController.currentUser` and
  return 401 when it's empty. For anything that belongs to a user (interests, history, likes),
  also check that it belongs to *that* user, not just that someone is logged in.
- **New tables:** use `?` placeholders for every value, validate input in the backend, and add
  `CHECK` constraints in the migration as a backstop.
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
4. **Rate limits are in memory.** They reset on restart and aren't shared across multiple backend instances. Move them to the DB or Redis before scaling out. They're counted per real client IP (see "Users' IP addresses"), so in production `TRUSTED_PROXIES` must name the web server in front of the backend, or every user would share that server's IP and its limits.
5. **The breached-password check fails open** if Have I Been Pwned is unreachable, so an outage doesn't block sign-ups. The other policy rules still apply.
6. **HTTPS is a deployment requirement.** Deploy behind TLS 1.2+, set `COOKIE_SECURE=true`, and send the CSP and `frame-ancestors` as HTTP headers from the web server.
7. **The pepper can't be rotated.** Changing it would stop every existing password from working, and without a password reset those accounts would be lost. Store it in a secrets manager in production.
8. **The topic, interest, video feedback, playlist and feed endpoints have no rate limit.** They only do small, indexed database reads and single-row writes, so the risk is low. The feed can start YouTube searches for never-searched topics, but the 80-searches-a-day budget caps that, so a flood can't run up the quota. It could still load the database, or use up the day's budget so other users' new topics wait until tomorrow. Add a per-IP limit (like `LoginRateLimiter`) before any public deployment.
9. **Embedded videos are third-party content.** We can't vet every video YouTube returns; safe search, the Education category and the topic queries are the only filters. Watching a video sends the viewer's IP to YouTube (Google), as with any embed.
10. **pgAdmin is a local development tool only.** It gives full read and write access to the database, so it's locked down: it only starts with `--profile tools`, listens on `127.0.0.1` only, runs as a non-root user, and has its own login. It won't start without `PGADMIN_PASSWORD`, so there's no default password. Never deploy it or expose port 5050. On a shared server, use `psql` over SSH instead.
11. **A banned person can make a new account.** Without email verification or a CAPTCHA (gaps 2 and 3), nothing stops someone signing up again with a different email. Bans stop the account, not the person. Email verification is what would make bans stick.
12. **Admin accounts are the most valuable ones to steal.** An admin can see every user's email and ban anyone who isn't an admin, and admins log in with just a password like everyone else. Keep the number of admins small, use long unique passwords, and when MFA is added (gap 1), require it for admins first.
13. **IP addresses are kept until the next login.** Each account keeps its sign-up IP and its latest login IP indefinitely, and admin actions keep theirs. That's fine for a class project, but a real deployment needs a privacy notice and a retention limit (for example, clearing IPs older than 90 days), since IP addresses count as personal data under privacy laws like the GDPR.
