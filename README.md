# BrainFeed

CPSC 488 Software Engineering

Matthew Ryboth, Brody Scott, Brandon Allshouse, Madeline Foradori

## What this is

Most short-form video apps are built to keep you scrolling, not to teach you anything.
This project is our attempt at flipping that: same familiar scrolling feed, but built
around educational content instead of whatever an algorithm thinks will keep you hooked.
Think Duolingo mixed with TikTok, for any subject: the quick, swipeable feed of TikTok, plus
Duolingo's focus on actually learning and remembering things.

Users pick a few topics they're interested in (programming, history, biology, whatever)
and get a feed of videos that actually explain or teach something about those topics,
pulled mainly from YouTube (possibly TikTok/Instagram later on for extra content).

## Features

- Sign up for an account, or just browse as a guest
- Pick interests up front, change them whenever: a whole subject (Math) or just part of it (Algebra 2)
- Scrollable feed of educational videos
- Thumbs up / down on videos to improve what you're shown, and a private list of liked and disliked
  videos where clicking a title takes you back to that video in your feed
- Less content from creators/channels you've marked as not interesting
- Preferences and history saved for registered users: a private watch history of the last 25 videos
  you pressed play on
- A random/discovery feed to branch out into new topics
- Admin page: ban users, blacklist videos, and see a log of every admin action

## Stack (subject to change)

- **Backend:** Java 25, using Javalin 7, built with Maven, tested with JUnit
- **Frontend:** React 19 + TypeScript, built with Vite (Node 24), styled with Tailwind CSS 4 and shadcn/ui components, tested with Vitest
- **Database:** PostgreSQL 18. The schema is managed by Flyway migrations in `backend/src/main/resources/db/migration`
- **Local development:** Docker Compose runs all of the above, so the only tools you need are Docker Desktop and Git
- **External services:** YouTube Data API, plus an LLM API for content classification (and later, generation)

```
Browser ──▶ React frontend (localhost:5173) ──/api──▶ Javalin backend (localhost:7070) ──▶ PostgreSQL (localhost:5432)
```

The frontend never talks to the database. Postgres only accepts connections from your own machine, and
only the backend has its password. Docker enforces this too: the database and backend share one
network (`data`) and the backend and frontend share another (`web`), so the frontend container
can't even reach the database. Keep new frontend code going through `/api`.

## Project layout

```
backend/                      Java backend (Maven project; Dockerfile builds and runs it)
  src/main/java/.../App.java    entry point: settings, security headers, routes
  src/main/java/.../auth/       accounts, login sessions, password hashing
  src/main/java/.../feed/       topics, interests, video feed, YouTube client
  src/main/java/.../admin/      admin page endpoints: user bans, video blacklist, admin action log
  src/main/java/.../history/    watch history: the last 25 videos each user pressed play on
  src/main/resources/db/migration/   database schema (Flyway SQL files)
  src/test/                     unit tests (JUnit)
frontend/                     React app (Vite project)
  src/pages/                    LoginPage, InterestsPage, FeedPage, LikedVideosPage, WatchHistoryPage, LeaderboardPage (placeholder), AdminPage
  src/components/layout/        page templates: PageLayout (signed-in pages), AuthLayout (login/interests)
  src/components/ui/            shadcn/ui building blocks: Button, Card, Input, Label, Badge, Table, Alert
  src/components/               VideoCard (one video in the feed), FeedHeader (logo and Profile menu)
  src/index.css                 color theme, base font settings and animations (everything else is Tailwind classes)
  src/assets/                   images bundled into the app (BrainFeed logo, also the favicon)
  src/api/                      typed calls to the backend
  src/**/*.test.ts(x)           unit tests (Vitest), next to the file they test
docker-compose.yml            runs the whole app locally: database, backend, frontend
.env.example                  template for your local settings and secrets
SECURITY.md                   security requirements. Read before touching auth code
PRESENTATION.md               how to demo the project, and which code to show
pgadmin/servers.json          sets up the optional database GUI (see "Looking at the database")
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
| `Cross-site request blocked.` when logging in or signing up | The address in your browser isn't in `ALLOWED_ORIGINS` (in `.env`). Open exactly **http://localhost:5173**; `127.0.0.1:5173` counts as a different site. To see what was blocked, run `docker compose logs backend` and look for `event=cross_site_write`. If you really need another address, add it to `ALLOWED_ORIGINS` (comma-separated) and run `docker compose up -d backend`. |
| pgAdmin: `failed to resolve host 'db'` (or the backend can't reach `db`) after pulling | Your containers are still on the old shared network from before the database got its own. Run `docker compose up -d` (and `docker compose --profile tools up -d pgadmin` if you use pgAdmin) to recreate them on the new networks. Your data and pgAdmin settings are kept. |
| Backend: `DB_PASSWORD is not set` or `PASSWORD_PEPPER ...` | That value in `.env` is blank or invalid. Generate one as in step 1. |
| Backend: `password authentication failed for user "..."` | `DB_PASSWORD` changed after the database was created. Reset the local database: `docker compose down -v`, then `docker compose up --build`. This erases local data. |
| Database starts empty after pulling the PostgreSQL 18 upgrade | Expected: Postgres can't read data files from an older major version, so the database moved to a new volume and starts fresh. Sign up again. To free the old volume's disk space: `docker volume rm brainfeed_brainfeed-data`. |
| Backend: Flyway `Validate failed: Migration checksum mismatch` | A migration file changed after it ran on your database. Reset with `docker compose down -v`. Never edit a migration others have run; add a new one. |
| `port is already allocated` (5432, 7070 or 5173) | Something else is using that port. For 5432 it's usually a separately installed PostgreSQL: stop it in the Services app (`postgresql-x64-…`). For 7070/5173, close any backend or `npm run dev` you started outside Docker. |
| Login page shows `Request failed (500)`, and frontend logs show `ECONNREFUSED` | The backend isn't running or crashed. Look for the error in the `backend-1` lines, or run `docker compose logs backend`. |
| Frontend edits don't show up | Wait a second (changes are detected by polling), then refresh. If still stuck, `Ctrl+C` and `docker compose up`. |
| `Too many failed login attempts` while testing | The limits are kept in memory. Run `docker compose restart backend`. |
| Sign-up hangs a few seconds with no internet | The breached-password check is timing out. Set `PASSWORD_BREACH_CHECK=false` in `.env` while offline, and restart. |
| http://localhost:5050 (pgAdmin) won't load | pgAdmin doesn't start with a plain `docker compose up`. Run `docker compose --profile tools up -d pgadmin` and wait about 20 seconds. If it still won't load, `docker compose logs pgadmin` says why (usually `PGADMIN_PASSWORD` is blank in `.env`). |

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

## Frontend

The UI is built from [shadcn/ui](https://ui.shadcn.com) components styled with
[Tailwind CSS](https://tailwindcss.com). shadcn/ui isn't a library you install: its components are
short source files you copy into `src/components/ui/` and own. Colors come from the variables at the
top of `src/index.css` (`background`, `card`, `primary`, `muted`, `destructive`, ...), so changing a
color there changes it everywhere. Icons come from [lucide-react](https://lucide.dev).

### Getting around

- **The BrainFeed logo and name** (top left, on every signed-in page) go back to the feed. On the
  feed itself they scroll back to the first video.
- **A title on "Your videos" or "Watch history"** (both in the Profile menu) opens that video in the feed. If it's
  already loaded, the feed scrolls to it; if not, it's added at the top. Either way it's outlined
  for a few seconds, and scrolling carries on from there. A video is never shown twice.
- **Keyboard:** ↓ or J for the next video, ↑ or K for the previous one. The Profile menu closes with
  Escape or a click outside it.

### Adding a page

Every signed-in page uses the same template, `PageLayout`. It draws the header and Profile menu,
the title and "Back to feed", so a new page only contains its own content:

```tsx
import PageLayout, { type PageNavigation } from '@/components/layout/PageLayout';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';

export default function QuizPage(navigation: PageNavigation) {
  return (
    <PageLayout {...navigation} name="quiz" title="Quiz" description="Test what you just watched.">
      <Card>
        <CardHeader>
          <CardTitle>Question 1</CardTitle>
        </CardHeader>
        <CardContent>...</CardContent>
      </Card>
    </PageLayout>
  );
}
```

The BrainFeed logo on the new page goes back to the feed automatically. Then, in `FeedPage.tsx`,
add `'quiz'` to the `view` type, add an `onShowQuiz` entry to
`navigation`, render `<QuizPage {...navigation} />` when it's selected, and add a menu item in
`FeedHeader.tsx`. Full-screen steps before the feed (like login) use `AuthLayout` instead.

### Adding a shadcn/ui component

Copy the component's code from ui.shadcn.com (the "Manual" install tab) into
`src/components/ui/`. Imports like `@/lib/utils` already work. Two rules:

- **Avoid components that pop up or position themselves** (Dropdown Menu, Popover, Tooltip,
  Select, Dialog) unless you really need one. They use Radix, which positions things with inline
  styles and is hard to test. Our menus use a styled `<details>` element instead (see
  `FeedHeader.tsx`).
- **New npm packages need a check first:** run `npm audit`, and don't install a version released in
  the last two weeks. Brand-new releases are where supply-chain attacks hide.

### Course requirements checklist

| Requirement | Where |
|---|---|
| Classes and ids | Tailwind classes everywhere, plus named classes used as hooks (`feed-list`, `video-card`, `auth-backdrop`, ...). Ids on every page (`login-page`, `feed-page`, `admin-page`, ...), on form fields (`email`, `password`), and on headings that label sections |
| `display: flex` | Tailwind's `flex` class, used across the app (`FeedHeader.tsx`, `PageLayout.tsx`, `VideoCard.tsx`, ...) |
| CSS animation or transition | `@keyframes fade-in-up` (pages and cards slide in) and `@keyframes pop` (thumbs up/down) in `index.css`; transitions on buttons, links, the menu chevron, the play button and table rows |
| 2+ font properties changed | `index.css` base layer: `font-family` (Inter), `font-size`, `line-height`, `letter-spacing`, and `font-weight` on headings |
| 2+ box model properties changed | `margin` on `body` in `index.css`; `padding`, `border`, `border-radius` and `margin` through Tailwind (`p-4`, `border`, `rounded-xl`, `mt-2`) on every card and button |
| Unobtrusive JavaScript | `index.html` has no inline scripts or `on...=` attributes. All behavior is attached from TypeScript modules, and the production Content-Security-Policy blocks inline scripts and handlers anyway |
| 10+ functions | 80+ in `src/` (not counting tests), e.g. `handleSubmit`, `switchMode`, `toggle`, `loadPage`, `scrollFeed`, `changeVideoFeedback`, `runAction`, `parseYoutubeId`, `closeMenu`, `cn` |
| 20+ event listeners | 40+: `onClick`, `onChange` and `onSubmit` handlers on every page; `keydown` listeners for the feed's ↑/↓/J/K shortcuts and for closing the Profile menu with Escape; a `pointerdown` listener for closing it by clicking outside; and two `IntersectionObserver`s (infinite scroll, pausing off-screen videos) |

TypeScript counts as JavaScript here; it compiles to plain JavaScript.

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
`PASSWORD_PEPPER`, `PASSWORD_BREACH_CHECK`, `COOKIE_SECURE`, `ALLOWED_ORIGINS`, `TRUSTED_PROXIES`, `PORT`,
`YOUTUBE_API_KEY`, `NVD_API_KEY`, `PGADMIN_EMAIL`, `PGADMIN_PASSWORD`.

**Users' IP addresses.** Browsers reach the backend through a proxy (Vite in development), so the
backend learns each user's real IP from the proxy's `X-Forwarded-For` header, and only believes
that header from the proxies listed in `TRUSTED_PROXIES` (see `ClientIp.java`). Docker Compose
sets it to the frontend container. In local Docker everyone shows up as the same address, your
own machine as Docker sees it (something like `172.x.0.1`). In production, set `TRUSTED_PROXIES`
to the web server in front of the backend, and make sure that server sets `X-Forwarded-For`.

### Getting a YouTube API key

The feed shows videos fetched from YouTube. Without a key the app still runs, but the feed stays
empty. Each person gets their own free key:

1. Go to https://console.cloud.google.com, sign in, and create a project (any name).
2. Open **APIs & Services → Library**, search for **YouTube Data API v3**, and click **Enable**.
3. Open **APIs & Services → Credentials → Create credentials → API key**. Copy the key.
4. Click the new key, and under **API restrictions** choose **Restrict key → YouTube Data API v3**,
   then save. That way a leaked key can't be used for any other Google service.
5. Paste it after `YOUTUBE_API_KEY=` in `.env`, then `Ctrl+C` and `docker compose up` again.

Within a minute of starting, the backend logs `Fetched N videos for topic ...`. The free quota is
10,000 units a day and each topic search costs 100, and there are about 120 topics, so videos are
fetched in two ways:

- **When someone opens the feed for a topic that has never been searched**, it's searched right
  away. The feed shows "Finding videos for these topics..." and checks again every few seconds
  until they arrive, which usually takes under 10 seconds. At most 5 new topics are fetched per request.
- **Every hour**, up to 3 topics that haven't been refreshed in a week are searched again, picked
  topics first.

Both share a budget of 80 searches (8,000 units) per 24 hours. The count comes from the database,
so restarting doesn't reset it or fetch again. After a YouTube error (usually the quota or a bad
key), new-topic fetching pauses for 15 minutes. If the budget is used up, the feed says there are
no videos yet, and they get fetched the next day.

### Looking at the database

The database only accepts connections from your own laptop, and the app (or at least
`docker compose up -d db`) has to be running. There are two ways to look inside it.

**In the browser, with pgAdmin** (the official PostgreSQL admin tool). One-time setup: put a
password after `PGADMIN_PASSWORD=` in `.env` (a generated secret like in step 1, or any long
password). Then:

```powershell
docker compose --profile tools up -d pgadmin
```

Open **http://localhost:5050** and log in with `PGADMIN_EMAIL` (`admin@sru.edu` unless you changed
it) and `PGADMIN_PASSWORD`. In the left sidebar, open **Servers → BrainFeed (local)**. It asks for
the database password: that's `DB_PASSWORD` from `.env` (tick "Save password" so it only asks
once). Then go to **Databases → brainfeed → Schemas → public → Tables**. Right-click a table and
pick **View/Edit Data → All Rows**, or open **Tools → Query Tool** to run SQL. Right-clicking
`brainfeed` → **ERD For Database** draws how the tables connect.

pgAdmin only starts with `--profile tools`, so a plain `docker compose up` doesn't run it. Stop it
with `docker compose stop pgadmin`. If your `DB_USER` isn't `brainfeed`, right-click the server →
**Properties → Connection** and change the username.

**In the terminal, with psql** (nothing to set up):

```powershell
docker compose exec db sh -c 'psql -U $POSTGRES_USER -d brainfeed'
```

Type SQL at the `brainfeed=#` prompt (`\dt` lists the tables, `\q` quits). Open the prompt first
and then type queries. Don't pass a query with `-c "..."`, because Windows PowerShell strips the
inner quotes. [PRESENTATION.md](PRESENTATION.md#appendix-looking-inside-the-database-yourself) has a
list of useful queries.

Stick to looking. If you change data by hand and break something, `docker compose down -v` resets
the local database (and erases everything in it).

## Auth API

All request and response bodies are JSON. Errors look like `{ "error": "message safe to show the user" }`.

| Method | Path                 | Body                            | Success response                                   |
|--------|----------------------|---------------------------------|----------------------------------------------------|
| POST   | `/api/auth/register` | `{ email, username, password }` | `201 { user }`, and logs in (sets session cookie)  |
| POST   | `/api/auth/login`    | `{ email, password }`           | `200 { user }`, and sets session cookie            |
| POST   | `/api/auth/logout`   | none                            | `204`, and ends the session                        |
| GET    | `/api/auth/me`       | none                            | `200 { user }`, or `401` if not logged in          |

`user` is `{ id, email, username, createdAt, admin }`. Other error codes: `400` invalid input, `403`
account banned (login only, and only after the right password), `409` email or username taken, `429`
too many attempts. Every POST and PUT must send `Content-Type: application/json`
(the frontend's `request()` helper in `api/client.ts` does this).

## Feed API

| Method | Path                | Body / query                       | Success response                                         |
|--------|---------------------|------------------------------------|----------------------------------------------------------|
| GET    | `/api/topics`       | none                               | `200 { topics: [{ id, name, parentId }] }`               |
| GET    | `/api/me/interests` | none (login required)              | `200 { topicIds }`, empty if none picked yet             |
| PUT    | `/api/me/interests` | `{ topicIds }` (login required)    | `200 { topicIds }`; replaces all of the user's interests |
| GET    | `/api/me/video-feedback` | none (login required)         | `200 { likedVideos, dislikedVideos }`                    |
| PUT    | `/api/me/video-feedback/{youtubeId}` | `{ feedback: "liked" or "disliked" }` (login required) | `204`; saves the user's choice |
| DELETE | `/api/me/video-feedback/{youtubeId}` | none (login required)          | `204`; clears the user's choice                          |
| GET    | `/api/feed`         | `?topics=1,2&seed=123&page=0`      | `200 { videos, nextPage, fetching }`; see below         |

Topics come in two levels. A subject (Math) has `parentId: null`; a sub-subject (Algebra 2) has its
subject's id. Asking for a subject's id in `/api/feed` or saving it as an interest includes all
of its sub-subjects. The list comes back with subjects first (A to Z), then sub-subjects in the
order they're usually learned. To add one, insert a row in a new migration (see
`V4__subjects_and_subtopics.sql`).

The topic list and feed are public, so guests can use them; guests' interests stay in the browser.
`nextPage` is null at the end. `fetching` is true when some of the topics have never been searched
and that search is happening now, so ask for page 0 again in a few seconds.
A `video` is `{ youtubeId, title, channelTitle, topicId, publishedAt }`. Pages hold 10 videos. The
feed comes in a shuffled order, and the same `seed` always gives the same order, so keep it while
scrolling. Videos are served from our database (filled once a day per topic by `FeedRefresher`),
so browsing never spends YouTube quota. Video feedback requires a signed-in account and is private
to that account. The feedback endpoint returns saved videos as `{ youtubeId, title, creator }`;
guests are asked to log in before voting.

## Watch history API

A video goes into the history when the user presses its play button. Scrolling past it doesn't
count. Each user keeps their newest 25: watching a video again moves it back to the top, and older
ones are deleted. The history is private (admins can't see it either), is deleted with the account,
and loses a video if an admin bans it. All endpoints need a login and answer `401` otherwise.

| Method | Path                                  | Body | Success response                                                         |
|--------|---------------------------------------|------|--------------------------------------------------------------------------|
| GET    | `/api/me/watch-history`               | none | `200 { videos: [{ youtubeId, title, creator, watchedAt }], limit: 25 }`, newest first |
| PUT    | `/api/me/watch-history/{youtubeId}`   | none | `204`; records a play. `404` if the video isn't in our database          |
| DELETE | `/api/me/watch-history/{youtubeId}`   | none | `204`; removes one video. `404` if it wasn't in the history              |
| DELETE | `/api/me/watch-history`               | none | `204`; clears the whole history                                          |

## Admin API

Every `/api/admin/` endpoint answers `401` if not logged in and `403` if the user isn't an admin.

| Method | Path                                    | Body / query      | Success response                                                     |
|--------|-----------------------------------------|-------------------|----------------------------------------------------------------------|
| GET    | `/api/admin/stats`                      | none              | `200 { users, bannedUsers, admins, videos, bannedVideos }`           |
| GET    | `/api/admin/users`                      | `?q=text`         | `200 { users }`, up to 50 matching username, email or IP address, newest first |
| PUT    | `/api/admin/users/{id}/ban`             | `{ reason }`      | `204`; bans and logs them out everywhere. `400` for yourself or an admin |
| DELETE | `/api/admin/users/{id}/ban`             | none              | `204`; `404` if they weren't banned                                  |
| GET    | `/api/admin/videos`                     | `?q=text`         | `200 { videos }`, up to 50 matching title, channel or id             |
| GET    | `/api/admin/banned-videos`              | none              | `200 { videos }`, newest ban first                                   |
| PUT    | `/api/admin/banned-videos/{youtubeId}`  | `{ reason }`      | `204`; removes it from the feed and everyone's liked lists for good  |
| DELETE | `/api/admin/banned-videos/{youtubeId}`  | none              | `204`; it comes back the next time its topic is refreshed            |
| GET    | `/api/admin/actions`                    | none              | `200 { actions }`, the 50 most recent bans and unbans, with the admin's IP |

A user in `users` is `{ id, username, email, createdAt, admin, bannedAt, banReason, signupIp,
lastLoginIp, lastLoginAt }`. The IPs are `null` for accounts from before IPs were recorded, and
they're never included in any non-admin response. A reason (1-200 characters, one line) is
required for every ban. Banning a video works for any
YouTube id, even one the feed hasn't fetched yet.

### Admin accounts

There's no button or endpoint that makes someone an admin, on purpose: that way a bug or a stolen
session can't be used to hand out admin access. To make an account an admin, sign it up normally,
then open psql (see "Looking at the database") and run:

```sql
UPDATE users SET is_admin = true WHERE username = 'testuser';
```

The change takes effect on that user's next request; reload the page and **Admin** appears in the
Profile menu. Set it back to `false` the same way to remove access. An admin can't be banned, so
remove admin access first if you ever need to.

## Security

Security requirements, the standards they come from (NIST SP 800-63B, NIST SSDF, OWASP ASVS/Top 10)
and known gaps are in [SECURITY.md](SECURITY.md). **Read it before changing anything related to
accounts, sessions, input handling or the database.**

## Roadmap

This is a class project, so scope is going to move around as we go. Rough direction:

1. **Done:** accounts/guest mode, interest selection, YouTube feed
2. **Now:** subjects and sub-subjects (Math → Algebra 2) covering the major areas of learning
3. **Next:** save thumbs up/down and use it in the feed (hide disliked videos, show less from disliked channels)
4. **Then:** the Duolingo side: LLM-generated summaries and quiz questions after videos, then progress
   tracking (streaks, reviewing missed questions)
5. **Later:** web scraping for learning material beyond YouTube (sources to be decided)
6. **Later:** an LLM creates subjects and sub-subjects, so any subject can be covered instead of
   only the list in `V4__subjects_and_subtopics.sql`. That list becomes the starting catalog
7. **Maybe:** TikTok/Instagram as additional content sources, if time allows

Nothing above is locked in. It's just where things stand right now.

## Status

Accounts are in place: sign up, log in, log out, and guest mode, with the backend, database and
security baseline set up. Users (and guests) pick interests and get a scrolling feed of short
educational YouTube videos for those topics. Topics are split into 20 subjects and about 100
sub-subjects, so users can pick all of Math or just Algebra 2. Up/down buttons on the feed page
scroll one video at a time.

Each video also has thumbs up/down buttons and a "Video Summary" box, but for now they only appear
on screen. The thumbs choice isn't saved and resets when the page reloads, and the summary box is
empty until the LLM summaries from the roadmap are added. Next up: saving thumbs up/down and using it
to shape the feed.
