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
  the backend holds its credentials.
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
| Default credentials | None. The backend and docker compose refuse to start without `DB_PASSWORD` and `PASSWORD_PEPPER` |

### Logging (OWASP Top 10 A09, OWASP Logging Cheat Sheet)

Login, logout, registration, rate-limit and policy rejections are logged under the `security` logger
with the outcome, user id, IP and user agent. Passwords, tokens and email addresses are never logged,
and control characters are stripped so user input can't forge log lines.

### Secure development process (NIST SSDF)

| Practice | How |
|---|---|
| PO.1 Define security requirements | This document |
| PS.1 Protect code and secrets | `.env` is gitignored; secrets come only from the environment |
| PW.4 Reuse well-secured components | Maintained libraries only (Javalin, BouncyCastle, Flyway, HikariCP, pgJDBC) with pinned versions, on long-term support (LTS) releases of Java and Node |
| PW.7 Review code | Security-relevant PRs are reviewed against this document; CodeQL runs on every PR |
| PW.8 Test | Unit tests for hashing, password policy, rate limiting and log sanitizing; run in CI |
| RV.1 Find vulnerabilities | Weekly Dependabot PRs (see "Dependency updates" in the README), `npm audit` in CI, and OWASP Dependency-Check run by hand (`mvn verify -P security-scan`) |

## Checklist for new features

The features on the roadmap (video feed, interests, YouTube and LLM APIs) bring new risks. Most of
the protections above apply automatically, but a few are up to you:

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
- **YouTube embeds** need the Content-Security-Policy in `frontend/vite.config.ts` to allow
  YouTube's domains. Add only the specific domains you need, not wildcards like `https:`.

## Known gaps and accepted risks

Things we know aren't covered yet, and what would need to change:

1. **Single factor only (AAL1).** There's no MFA. That's acceptable for this app's data; add TOTP or passkeys if sensitive data is added.
2. **No email verification or password reset yet.** Until verification exists, sign-up says when an email is already registered (sign-ups are rate limited to reduce enumeration). Password reset must follow the OWASP Forgot Password Cheat Sheet when built.
3. **Rate limits are in memory.** They reset on restart and aren't shared across multiple backend instances. Move them to the DB or Redis before scaling out. Behind the Vite dev proxy, all clients share one IP.
4. **The breached-password check fails open** if Have I Been Pwned is unreachable, so an outage doesn't block sign-ups. The other policy rules still apply.
5. **HTTPS is a deployment requirement.** Deploy behind TLS 1.2+, set `COOKIE_SECURE=true`, and send the CSP and `frame-ancestors` as HTTP headers from the web server.
6. **The pepper can't be rotated** without users resetting their passwords. Store it in a secrets manager in production.
