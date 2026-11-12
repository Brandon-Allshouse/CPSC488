# Code Version 3: Learning plan and review queue

Prepared for Mathew Rybolt, Team 6, November 12, 2026. This contribution is based on
`Brandon-Allshouse/CPSC488` main at `6c7193ace2b40815f313f754af1894817ebbad68`.
The existing quizzes, leaderboard, notebook, playlists, moderation and account features are team work.
No changes have been committed, pushed, merged or submitted.

## Assignment checked in D2L

[Code version 3](https://sru.desire2learn.com/d2l/lms/dropbox/user/folder_submit_files.d2l?db=4067753&grpid=0&isprv=0&bp=0&ou=4066223)
is due **November 12, 2026 at 9:00 a.m. Eastern**. It asks for a functional C++/Java backend,
15+ functions for core use cases, user inputs and database connectivity, formatted output in
the Version 2 GUI, security functions, error handling and documentation. It suggests using
vectors, pointers and file operations where appropriate; Java uses managed references and
collections rather than C++ pointers. The new Java feature uses collections and Writer-based CSV output.

Put the entire project in `Code_Version_3/` and submit **one ZIP**, named
`Team6_Code_Version_3.zip`. Only one team member should submit. The assignment also says the
whole group must attend the November 12 presentation to receive grades.

## What the new code does

Open **Your videos → Learning progress** while signed in.

- Every video in the Study notebook begins as **Planned**, including existing entries.
- Set a video to **In progress** or **Completed**. These choices are saved in PostgreSQL.
- Completion schedules a review seven days later. The server supplies the due date and totals.
- **Mark reviewed** records the current server time and schedules another review in seven days.
- Repeating Completed preserves the existing dates. Changing back to Planned or In progress clears them.
- Due videos appear first, oldest due date first. Search titles/creators and filter statuses or due videos.
- Totals and completion percentage describe the whole notebook, even while the list is filtered.
- **Download learning plan** exports the whole private notebook to a UTF-8 CSV with dates and watch links.
- A failed save keeps the last confirmed state. If the write succeeded but refreshing fails, the UI
  says so and requires a refresh before another edit. Loading failures show Retry rather than false zeroes.

Completion is **self-reported**. Watching a video and answering a quiz do not silently change these
states. The existing quiz leaderboard remains separate. This feature needs no new dependency or API key.

## Rubric mapping and the 25 added Java functions

| Requirement | Evidence |
| --- | --- |
| Functional Java backend | `learning` controller, service, JDBC repository and CSV writer |
| At least 15 meaningful functions | 25 non-constructor methods below, in addition to existing project functions |
| User input and database | Validated status choices, explicit review action and V16 PostgreSQL migration |
| Formatted Version 2 GUI output | Existing component styles, four totals, progress bar, review dates, search and filters |
| File output | Owner-only CSV download using Java Writer, UTF-8 BOM, CRLF rows and safe quoting |
| Security | Session-derived owner, parameterized SQL, validated fields/IDs, FK and CHECK constraints, inherited CSRF and security headers |
| Error handling | 400 invalid input, 401 logged out, 404 missing/unowned/ineligible entry, app-wide safe 500 response; retry/error states in GUI |
| Documentation | This guide, API contract, automated tests, installation instructions and demo |

| Class | Substantive methods | Count |
| --- | --- | ---: |
| `LearningProgressController` | `register`, `requireUser`, `handleList`, `handleExport`, `handleStatus`, `handleReview`, `readStatus`, `validateVideoId` | 8 |
| `LearningProgressService` | `snapshot`, `setStatus`, `review`, `validateStatus`, `reviewDueAt`, `toItem`, `summarize`, `countStatus` | 8 |
| `LearningProgressRepository` | `list`, `saveStatus`, `markReviewed`, `lockNotebookEntry`, `read` | 5 |
| `LearningProgressCsv` | `encode`, `write`, `writeRow`, `cell` | 4 |
| Total | Constructors and generated record accessors excluded | **25** |

## API and persistence

| Method | Path | Request | Success |
| --- | --- | --- | --- |
| GET | `/api/me/learning-progress` | Session cookie | `{items, summary, reviewIntervalDays}` |
| PUT | `/api/me/learning-progress/{youtubeId}` | `{"status":"planned"}`, `in_progress`, or `completed` | 204 |
| POST | `/api/me/learning-progress/{youtubeId}/review` | No body; JSON Content-Type header | 204 |
| GET | `/api/me/learning-progress/export` | Session cookie | CSV attachment |

The snapshot contains each video's id/title/creator, status, completion/review/update timestamps,
review due date and due flag. Summary contains total/planned/inProgress/completed/dueForReview and
completionPercent. Empty plans have a real zero percent. API callers cannot set owners or dates.

The additive `V16__learning_progress.sql` follows the current main branch's V15. Existing migrations
are untouched. Its composite foreign key references `(user_id, youtube_id)` in `saved_videos`, so
removing a notebook entry, user or video cleans up its progress. A playlist copy is independent.
All existing saved entries participate through a left join without a data backfill. The notebook's
existing application limit of 500 entries bounds normal list/export size.

Writes lock the matching notebook row in a transaction. This serializes progress writes against
entry deletion and prevents orphan data. Prepared statements scope every operation to the session
owner. Status/completion/review CHECK constraints also protect the database. Write timestamps use
`clock_timestamp()` after the lock so a waiting transaction does not backdate a later change.

CSV is generated in memory, leaving no private temporary file. It has a fixed download filename,
inherits `Cache-Control: no-store`, quotes commas/newlines/quotes and neutralizes formula prefixes,
including after leading whitespace. It does not contain email, session cookies or account IDs.

## Add the code to an existing checkout

The review package contains `Add_to_existing_project/`, including only changed/new files, a patch,
and `Apply-LearningProgress.ps1`. The installer preflights every file before writing; it stops if a
file differs from the expected base or another V16 migration occupies the slot. It accepts CRLF
checkouts, backs up files it replaces and never runs Git commits, pushes, database commands or D2L.

From that directory:

```powershell
./Apply-LearningProgress.ps1 -ProjectPath 'C:\Users\afroh\Downloads\CPSC488' -WhatIf
./Apply-LearningProgress.ps1 -ProjectPath 'C:\Users\afroh\Downloads\CPSC488'
```

Review `git status` and the diff in your own checkout first. If your checkout is behind or contains
other changes, reconcile it with the stated main commit before applying the contribution. The
installer aborts on a mismatch and leaves the checkout untouched. A fresh source snapshot is also
included as `Team6_Code_Version_3.zip`, with the required `Code_Version_3/` layout.

For a separate demo copy, extract that source ZIP, provide your existing local `.env` using the
normal README instructions, and start from `Code_Version_3/`:

```powershell
docker compose up --build
```

Open `http://localhost:5173`. The new migration applies on backend startup. Do not reset database
volumes to install this feature. An existing checkout's `.env` is preserved by the installer; no
real `.env`, credentials, Git metadata or dependency/build folders are included in the ZIP.

## Verification performed here

| Check | Observed result |
| --- | --- |
| Current Java production and test source compilation | Passed with Java 25 and Maven offline test-compile |
| New rule, validation and CSV tests | **15 passed**, using the installed JUnit Jupiter engine directly |
| Real HTTP tests | 9 attempted; all blocked before HTTP requests by the environment's loopback socket restrictions |
| Real PostgreSQL tests | 8 aborted because TEST_DB_URL was unavailable; they use the existing isolated TestDatabase and all real migrations |
| TypeScript/TSX syntax | All 6 changed/new frontend files passed with bundled TypeScript 6.0.3 |
| API TypeScript strict checking | New API helper and its existing client passed |
| Full frontend build / Vitest | Blocked: required npm tarballs were absent and network access unavailable |
| Normal Maven test lifecycle | Blocked by an incomplete local Surefire 3.6.0 installation |

Compilation and the 15 passing tests do not establish a live GUI, real HTTP behavior or PostgreSQL
persistence. Run the normal project checks below before presenting it as fully tested. The code
includes the HTTP, database, React and API tests needed for a normal project environment.

```powershell
docker compose run --rm backend-tests
docker compose exec frontend npm test -- --no-file-parallelism
docker compose exec frontend npm run build
```

If working outside Docker: backend `mvn test`; frontend `npm ci`, `npm test`, then `npm run build`.
The repository's existing test database setup uses a disposable `brainfeed_test...` database;
never point tests at the application database. Use a unique `TEST_DB_NAME` when sharing a test server.

## Five-minute demonstration

1. Sign in and save two videos to **Study notebook** through the bookmark menu.
2. Open **Your videos → Learning progress**. Both should start Planned.
3. Set one to In progress and one to Completed. Explain the 50% total and next review date.
4. Reload and reopen Learning progress to demonstrate the database restoring the states.
5. Filter Completed, search a title/creator, then clear the filter. Totals should stay unchanged.
6. Mark the completed video reviewed. Then reopen it as Planned and show its review date disappear.
7. Download the CSV and show that both videos are present even if the GUI filter hides one.
8. Show a second account's independent plan and that guests have no progress tab.

The seven-day boundary is covered by fixed-clock tests; the demo does not require backdating real data.
Also check the page at phone width and verify retry behavior when the backend is unavailable.

## What to explain in class

"This feature extends the Study notebook into a persistent learning plan. Users can mark videos
as planned, in progress or completed, then return to completed videos for review. The Java controller
authenticates requests and validates input. The repository saves each user's choices in PostgreSQL,
and the service calculates totals and review dates. The existing React GUI displays those results
and provides a CSV download. Ownership comes from the login session, so a request cannot change
another user's plan. Completion is the user's own report, and review is scheduled seven days after
completion or the latest review."

Use that as a walkthrough after reviewing the code. Describe executed checks accurately: the local
compile and 15 unit tests passed; live HTTP/database/frontend checks still need the normal environment.

If asked why the service is separate: the learning rules can be tested with a fixed clock without a
database or browser. If asked why the repository is separate: it contains parameterized SQL and
transactions while the controller handles HTTP. If asked why there is a migration: the schema change
is repeatable and existing notebook data remains usable. If asked about concurrent writes: the
parent notebook row lock serializes edits and removal; the foreign key enforces cleanup.
