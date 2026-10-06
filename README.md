# BrainFeed

CPSC 488 Software Engineering

Matthew Ryboth, Brody Scott, Brandon Allshouse, Madeline Foradori

## What this is

Most short-form video apps are built to keep you scrolling, not to teach you anything.
This project is our attempt at flipping that: same familiar scrolling feed, but built
around educational content instead of whatever an algorithm thinks will keep you hooked.

Users pick a few topics they're interested in (programming, history, biology, whatever)
and get a feed of videos that actually explain or teach something about those topics,
pulled mainly from YouTube (possibly TikTok/Instagram later on for extra content).

## Features

- Sign up for an account, or just browse as a guest
- Pick interests up front, change them whenever
- Scrollable feed of educational videos
- Thumbs up / down on videos to improve what you're shown
- Less content from creators/channels you've marked as not interesting
- Preferences and history saved for registered users
- A random/discovery feed to branch out into new topics

## Stack (subject to change)

- **Backend:** Java 25, using Javalin 7, built with Maven, tested with JUnit
- **Frontend:** React 19 + TypeScript, built with Vite (Node 24), tested with Vitest
- **Database:** PostgreSQL 18. The schema is managed by Flyway migrations in `backend/src/main/resources/db/migration`
- **Local development:** Docker Compose runs all of the above, so the only tools you need are Docker Desktop and Git
- **External services:** YouTube Data API, plus an LLM API for content classification (and later, generation)

```
Browser ──▶ React frontend (localhost:5173) ──/api──▶ Javalin backend (localhost:7070) ──▶ PostgreSQL (localhost:5432)
```

The frontend never talks to the database. Postgres only accepts connections from your own machine, and
only the backend has its password.

## Project layout

```
backend/                      Java backend (Maven project; Dockerfile builds and runs it)
  src/main/java/.../App.java    entry point: settings, security headers, routes
  src/main/java/.../auth/       accounts, login sessions, password hashing
  src/main/java/.../feed/       topics, interests, video feed, YouTube client
  src/main/resources/db/migration/   database schema (Flyway SQL files)
  src/test/                     unit tests (JUnit)
frontend/                     React app (Vite project)
  src/pages/                    LoginPage, InterestsPage, FeedPage
  src/components/               VideoCard (one video in the feed)
  src/api/                      typed calls to the backend
  src/**/*.test.ts(x)           unit tests (Vitest), next to the file they test
docker-compose.yml            runs the whole app locally: database, backend, frontend
.env.example                  template for your local settings and secrets
SECURITY.md                   security requirements. Read before touching auth code
```

## Running locally

Everything runs in Docker, so the only things you install are **Docker Desktop** and **Git**. Java,
Maven, Node and PostgreSQL run inside containers at versions pinned in `docker-compose.yml` and
`backend/Dockerfile`, so every laptop runs exactly the same setup. Library versions are pinned in
`backend/pom.xml` (Java) and `frontend/package.json` + `package-lock.json` (JavaScript).

Commands below are for **PowerShell** on Windows. Open it from the Start menu, or use the terminal
in VS Code or IntelliJ. They work the same on macOS and Linux, except the secret-generating line.

### 1. One-time setup

**Install Docker Desktop and Git:**

```powershell
winget install -e --id Docker.DockerDesktop
winget install -e --id Git.Git
```

**Install WSL 2**, which Docker Desktop needs to run Linux containers. Open PowerShell **as
Administrator** (Start menu → type "PowerShell" → right-click → *Run as administrator*) and run:

```powershell
wsl --install --no-distribution
```

(If it says WSL is already installed, run `wsl --update` instead.)

Then **restart your laptop**. After the restart, open **Docker Desktop** from the Start menu, accept
the terms, and wait until it shows the engine is running. Docker Desktop must be running whenever
you work on the project. To check, run `docker version` in a new PowerShell window. It should
show both a **Client** and a **Server** section.

**Get the code and create your `.env` file:**

```powershell
git clone https://github.com/Brandon-Allshouse/CPSC488.git
cd CPSC488
Copy-Item .env.example .env
```

`.env` holds your local passwords and secrets. It's gitignored, so it never gets committed.
Two of its values must be filled in, and the app won't start without them. Run this line **twice**,
to get two different random values:

```powershell
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

Open the file with `notepad .env`. Paste the first value after `DB_PASSWORD=` and the second after
`PASSWORD_PEPPER=` (no spaces or quotes), then save. Everyone generates their own values; don't
share your `.env`. (On macOS/Linux, use `openssl rand -base64 32` instead.)

### 2. Start the app

From the `CPSC488` folder:

```powershell
docker compose up --build
```

The first run downloads everything and takes several minutes; later runs take seconds. The logs of
all three parts (`db-1`, `backend-1`, `frontend-1`) appear in this window. When the frontend
prints `Local: http://localhost:5173/`, open **http://localhost:5173** in your browser.

New passwords must be at least 15 characters; a few random words works well.

To stop, press `Ctrl+C` in that window. Your database is kept for next time.

### 3. Day-to-day development

| You changed... | What to do |
|---|---|
| Frontend code (`frontend/src`) | Nothing. Save the file and the browser updates. |
| Backend code (`backend/src`) | `Ctrl+C`, then `docker compose up --build` again. Only the backend rebuilds, usually in under a minute. |
| Database schema | Add a new `V<n>__description.sql` file in `backend/src/main/resources/db/migration`, then restart as for backend code. Never edit an existing one. |
| Java library needed | Add it to `backend/pom.xml`, then restart as for backend code. |
| npm package needed | With the app running: `docker compose exec frontend npm install <package>`. This updates `package.json` and `package-lock.json`; commit both. Don't run `npm install` directly on Windows while using Docker. |
| `.env` values | `Ctrl+C`, then `docker compose up` again. |

Other useful commands:

```powershell
docker compose up --build -d         # start in the background instead
docker compose logs -f backend       # follow one part's logs (backend, frontend or db)
docker compose down                  # stop and remove the containers (data is kept)
docker compose down -v               # ERASES the local database and cached packages
```

### Tests and checks

```powershell
docker compose run --rm backend-tests                                   # backend unit tests
docker compose run --rm backend-tests mvn -B verify -P security-scan    # scan Java libraries for known vulnerabilities (slow the first time)
docker compose exec frontend npm test                                   # frontend unit tests (app must be running)
docker compose exec frontend npm test -- src/pages/FeedPage.test.tsx   # run one frontend test file
docker compose exec frontend npm run build                              # TypeScript type-check + production build (app must be running)
docker compose exec frontend npm run audit:security                     # scan npm packages for known vulnerabilities (app must be running)
```

Run frontend tests with Vitest, not `node` directly: Node does not execute `.ts` or `.tsx` test files.

The security scan needs a free `NVD_API_KEY` in `.env` (see `.env.example`). The first run downloads
the vulnerability database, which takes a while; later runs reuse it.

**Writing tests.** Every new class or component should come with unit tests.

- Backend tests use JUnit and live in `backend/src/test/java`, in the same package as the class
  they test (for example, `feed/FeedControllerTest.java`). Unit tests don't use the database or
  call YouTube. Keep logic that's worth testing in small methods, like
  `FeedController.parseFeedQuery`, or use fake subclasses (see `FeedRefresherTest`). The endpoints
  and SQL will be covered by API tests that run against a real database; those haven't been added yet.
- Frontend tests use [Vitest](https://vitest.dev) and sit next to the file they test
  (`FeedPage.tsx` → `FeedPage.test.tsx`). Component tests start with `// @vitest-environment jsdom`
  and use React Testing Library. They mock the `api/` modules so no backend is needed.
  `src/test/fakeIntersectionObserver.ts` lets a test pretend the user scrolled.
- Use plain test data like `test@sru.edu` and `testuser`.

CI (`.github/workflows/ci.yml`) runs the tests, the vulnerability checks, the build, a Docker build,
and CodeQL on every push and pull request.

### Dependency updates

Every week, Dependabot opens one PR for each kind of dependency (npm, Maven, Docker, GitHub
Actions) with everything that has a new version. CI runs on each one. If CI passes, it's normally
safe to merge. If it fails, the update needs a code change first, so don't merge it as is.

The exception is Java, Node and Postgres. Dependabot is set up not to suggest new major versions
of these (see `.github/dependabot.yml`), because each one is set in several files that all have
to change together. Their image tags (`25-jre`, `24-trixie-slim`, `18`) always point to the latest
patch release, so CI gets security fixes automatically. Your laptop keeps whatever it downloaded
first, so run this every so often to get the latest patches:

```powershell
docker compose pull; docker compose build --pull
```

When it's time to move to a new version, change everything in one row in a single PR:

| Upgrading | What to change |
|---|---|
| Java | `maven.compiler.release` in `backend/pom.xml`, both `FROM` lines in `backend/Dockerfile`, the `backend-tests` image in `docker-compose.yml`, both `java-version` lines in `.github/workflows/ci.yml`, and the Stack section above. Also update the Maven version in those `maven:` tags to the newest one. |
| Node | The `frontend` image in `docker-compose.yml`, `node-version` in `ci.yml`, and `@types/node` in `frontend/package.json` (to the same major version as Node). |
| Postgres | The `db` image in `docker-compose.yml`, and rename its volume as the comment there explains. Everyone's local database will start empty. |

Only upgrade to long-term support (LTS) versions: Java 21, 25, 29 and so on (one every two years),
and even-numbered Node versions. The versions in between only get about six months of updates.

### Troubleshooting

| Problem | Fix |
|---|---|
| `docker` "is not recognized" | Restart your laptop after installing Docker Desktop. |
| `failed to connect to the docker API at npipe:////./pipe/docker_engine`, `error during connect`, or `cannot find the file specified` | Docker Desktop isn't running. Start it from the Start menu and wait for the engine. If it won't start, make sure WSL is installed (step 1). |
| `The Windows Subsystem for Linux is not installed` | Run `wsl --install --no-distribution` in an Administrator PowerShell, then restart the laptop. |
| Docker Desktop: "WSL 2 installation is incomplete" or "WSL needs updating" | Run `wsl --update` in PowerShell, then restart Docker Desktop. |
| Docker Desktop: "Virtualization support not detected" | Virtualization is turned off in your laptop's BIOS/UEFI settings. Search your laptop model + "enable virtualization". |
| `env file ...\.env not found` or `Set DB_PASSWORD in .env` | `.env` is missing or misnamed. It must be in the `CPSC488` folder and named exactly `.env`. Check with `Get-ChildItem -Force .env`; Notepad sometimes saves it as `.env.txt`. |
| Node reports `Unknown file extension ".tsx"` when you run a frontend test | Don't launch the test file with `node`. Run it through Vitest instead: `docker compose exec frontend npm test -- src/pages/FeedPage.test.tsx` (the app must be running). |
| Backend: `DB_PASSWORD is not set` or `PASSWORD_PEPPER ...` | That value in `.env` is blank or invalid. Generate one as in step 1. |
| Backend: `password authentication failed for user "..."` | `DB_PASSWORD` changed after the database was created. Reset the local database: `docker compose down -v`, then `docker compose up --build`. This erases local data. |
| Database starts empty after pulling the PostgreSQL 18 upgrade | Expected: Postgres can't read data files from an older major version, so the database moved to a new volume and starts fresh. Sign up again. To free the old volume's disk space: `docker volume rm brainfeed_brainfeed-data`. |
| Backend: Flyway `Validate failed: Migration checksum mismatch` | A migration file changed after it ran on your database. Reset with `docker compose down -v`. Never edit a migration others have run; add a new one. |
| `port is already allocated` (5432, 7070 or 5173) | Something else is using that port. For 5432 it's usually a separately installed PostgreSQL: stop it in the Services app (`postgresql-x64-…`). For 7070/5173, close any backend or `npm run dev` you started outside Docker. |
| Login page shows `Request failed (500)`, and frontend logs show `ECONNREFUSED` | The backend isn't running or crashed. Look for the error in the `backend-1` lines, or run `docker compose logs backend`. |
| Frontend edits don't show up | Wait a second (changes are detected by polling), then refresh. If still stuck, `Ctrl+C` and `docker compose up`. |
| `Too many failed login attempts` while testing | The limits are kept in memory. Run `docker compose restart backend`. |
| Sign-up hangs a few seconds with no internet | The breached-password check is timing out. Set `PASSWORD_BREACH_CHECK=false` in `.env` while offline, and restart. |

### Optional: running without Docker (for IDE debugging)

Docker is the standard way to run the project. Running the backend or frontend directly on Windows
is only worth it if you want breakpoints in IntelliJ or VS Code. The database still runs in Docker.

1. Install the tools. Maven isn't available through winget, so the script downloads it into
   `C:\Users\<you>\tools` and adds it to your PATH. Afterwards, close and reopen PowerShell.

   ```powershell
   winget install -e --id EclipseAdoptium.Temurin.25.JDK
   winget install -e --id OpenJS.NodeJS.LTS

   $v = '3.9.15'; $dest = "$env:USERPROFILE\tools"
   New-Item -ItemType Directory -Force $dest | Out-Null
   $ProgressPreference = 'SilentlyContinue'
   Invoke-WebRequest "https://archive.apache.org/dist/maven/maven-3/$v/binaries/apache-maven-$v-bin.zip" -OutFile "$dest\maven.zip" -UseBasicParsing
   Expand-Archive "$dest\maven.zip" -DestinationPath $dest -Force
   Remove-Item "$dest\maven.zip"
   $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
   [Environment]::SetEnvironmentVariable('Path', "$userPath;$dest\apache-maven-$v\bin", 'User')
   ```

2. Stop the Docker backend and frontend if they're running (`docker compose down`), then start only
   the database: `docker compose up -d db`.
3. Backend: `cd backend; mvn compile exec:java`, or in IntelliJ open the `backend` folder as a Maven
   project and run `App.main`. It reads `.env` from the repo root automatically.
4. Frontend, in a second window: `cd frontend; npm install; npm run dev`.

Common native-setup problems:
- `npm.ps1 cannot be loaded because running scripts is disabled`: run
  `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` once, then reopen PowerShell.
- Maven says `JAVA_HOME` is not defined correctly: run
  `[Environment]::SetEnvironmentVariable('JAVA_HOME', (Split-Path (Split-Path (Get-Command java).Source)), 'User')`,
  then reopen PowerShell.

## Contributing

Nobody commits directly to `main`. Every change goes on its own short-lived branch, made from an
up-to-date `main`, and gets merged back through a pull request.

### 1. Pull before you start

Always start from the latest `main`, so you're building on everyone else's work and not an old
copy of it:

```powershell
git switch main
git pull
docker compose up --build
```

Rebuilding after a pull picks up any new database migrations and dependency changes.

### 2. Make a branch for your change

Create one branch per feature or fix, named after what it does:

```powershell
git switch -c feature/interest-selection
```

Start the name with `feature/` for new functionality or `fix/` for bug fixes, followed by a few
words separated by hyphens (`feature/video-feed`, `fix/login-error-message`). Keep each branch
focused on one thing. Small branches are easier to review and cause fewer merge conflicts.

### 3. Commit and push

```powershell
git add <files>
git commit -m "Add interest selection page"
git push -u origin feature/interest-selection
```

Never commit `.env`. It's gitignored, but check `git status` before committing anyway.

### 4. Keep your branch up to date

If `main` changes while you're working, bring those changes into your branch before opening a pull
request, and again if it falls behind while waiting for review:

```powershell
git switch main
git pull
git switch feature/interest-selection
git merge main
```

If Git reports merge conflicts, fix the marked sections in each file, then `git add` them and
`git commit`. Ask the teammate who wrote the other change if you're unsure which version to keep.

### 5. Open a pull request

Open a pull request into `main` on GitHub. Before merging:

- CI must pass (tests, build, vulnerability checks and CodeQL).
- A teammate should review it. If it touches accounts, sessions, user input or the database, check
  it against [SECURITY.md](SECURITY.md).

After it's merged, delete the branch on GitHub, and locally:

```powershell
git switch main
git pull
git branch -d feature/interest-selection
```

Then start your next change from step 1.

## Configuration

Settings are read from `.env` in the repo root, and real environment variables override it.
Every setting is described in [`.env.example`](.env.example): `DB_USER`, `DB_PASSWORD`, `DB_URL`,
`PASSWORD_PEPPER`, `PASSWORD_BREACH_CHECK`, `COOKIE_SECURE`, `ALLOWED_ORIGINS`, `PORT`,
`YOUTUBE_API_KEY`, `NVD_API_KEY`.

### Getting a YouTube API key

The feed shows videos fetched from YouTube. Without a key the app still runs, but the feed stays
empty. Each person gets their own free key:

1. Go to https://console.cloud.google.com, sign in, and create a project (any name).
2. Open **APIs & Services → Library**, search for **YouTube Data API v3**, and click **Enable**.
3. Open **APIs & Services → Credentials → Create credentials → API key**. Copy the key.
4. Click the new key, and under **API restrictions** choose **Restrict key → YouTube Data API v3**,
   then save. That way a leaked key can't be used for any other Google service.
5. Paste it after `YOUTUBE_API_KEY=` in `.env`, then `Ctrl+C` and `docker compose up` again.

Within a minute of starting, the backend logs `Fetched N videos for topic ...` for each topic. The
free quota is 10,000 units a day and each topic search costs 100, so the backend refreshes each
topic at most once a day (about 1,000 units). Restarting doesn't fetch again.

## Auth API

All request and response bodies are JSON. Errors look like `{ "error": "message safe to show the user" }`.

| Method | Path                 | Body                            | Success response                                   |
|--------|----------------------|---------------------------------|----------------------------------------------------|
| POST   | `/api/auth/register` | `{ email, username, password }` | `201 { user }`, and logs in (sets session cookie)  |
| POST   | `/api/auth/login`    | `{ email, password }`           | `200 { user }`, and sets session cookie            |
| POST   | `/api/auth/logout`   | none                            | `204`, and ends the session                        |
| GET    | `/api/auth/me`       | none                            | `200 { user }`, or `401` if not logged in          |

`user` is `{ id, email, username, createdAt }`. Other error codes: `400` invalid input, `409` email or
username taken, `429` too many attempts. Every POST and PUT must send `Content-Type: application/json`
(the frontend's `request()` helper in `api/client.ts` does this).

## Feed API

| Method | Path                | Body / query                       | Success response                                         |
|--------|---------------------|------------------------------------|----------------------------------------------------------|
| GET    | `/api/topics`       | none                               | `200 { topics: [{ id, name }] }`                         |
| GET    | `/api/me/interests` | none (login required)              | `200 { topicIds }`, empty if none picked yet             |
| PUT    | `/api/me/interests` | `{ topicIds }` (login required)    | `200 { topicIds }`; replaces all of the user's interests |
| GET    | `/api/feed`         | `?topics=1,2&seed=123&page=0`      | `200 { videos, nextPage }`; `nextPage` is null at the end |

The topic list and feed are public, so guests can use them; guests' interests stay in the browser.
A `video` is `{ youtubeId, title, channelTitle, topicId, publishedAt }`. Pages hold 10 videos. The
feed comes in a shuffled order, and the same `seed` always gives the same order, so keep it while
scrolling. Videos are served from our database (filled once a day per topic by `FeedRefresher`),
so browsing never spends YouTube quota.

## Security

Security requirements, the standards they come from (NIST SP 800-63B, NIST SSDF, OWASP ASVS/Top 10)
and known gaps are in [SECURITY.md](SECURITY.md). **Read it before changing anything related to
accounts, sessions, input handling or the database.**

## Roadmap

This is a class project, so scope is going to move around as we go. Rough direction:

1. **Now:** basic feed + YouTube integration, accounts/guest mode, interest selection
2. **Next:** interested/not-interested feedback loop actually affecting recommendations, creator downweighting
3. **Later:** LLM-generated short summaries and quiz-style questions for videos/topics in your feed
4. **Maybe:** TikTok/Instagram as additional content sources, if time allows

Nothing above is locked in. It's just where things stand right now.

## Status

Accounts are in place: sign up, log in, log out, and guest mode, with the backend, database and
security baseline set up. Users (and guests) pick interests and get a scrolling feed of short
educational YouTube videos for those topics. Next up: thumbs up/down feeding back into the feed.
