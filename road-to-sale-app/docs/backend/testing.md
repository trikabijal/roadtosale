# Testing

The test strategy for the backend stack and how to run each layer. This doubles
as the test plan. See [README.md](./README.md) for the full build/run commands.

## The three layers of tests

| Layer | What it tests | How | Count | Needs |
|---|---|---|---|---|
| Core slice tests | The Java Core API end to end (controllers → services → DB) | Real embedded Postgres, MockMvc HTTP calls | 25 | No Docker |
| BFF unit tests | The BFF's edge behavior (validation, auth, relay) | Core HTTP client mocked | 14 | Nothing running |
| Headless E2E | The whole stack through the BFF | Real embedded Postgres + Core jar + BFF, all booted in-process | 14 checks | No Docker |

All three run headless. None needs a Docker daemon: the Core tests and the E2E
both use `embedded-postgres` (the zonky package), which launches a real Postgres
binary in-process. The `deployment/docker-compose.yml` Postgres is only for
normal local development, not for tests.

## Core slice tests (25)

- **Location:** `road-to-sale-app/backend/src/test/java/com/auditpro/roadtosale/`
- **Run:**
  ```bash
  cd road-to-sale-app/backend && ./test.sh   # mvn test
  ```
- **How:** `AbstractIntegrationTest` boots the full Spring app with
  `@SpringBootTest` against a zonky embedded Postgres
  (`@AutoConfigureEmbeddedDatabase`, refreshed after each test method), runs
  Flyway, and seeds the two standard dealerships/users before each test. Tests
  call real endpoints through `MockMvc` and assert on the `ApiResponse` JSON.

Coverage by file:

- **`AuthTest`** (8): login returns tokens + user; wrong password → 401; missing
  field → 400; refresh issues a new pair; garbage refresh → 401; an access token
  cannot be used as a refresh token; a protected route without a token → 401;
  with a bad token → 401.
- **`SessionLifecycleTest`** (8): create/get/list a session; unknown checksheet →
  404; submit completes and a second submit → 409; nonexistent session → 404;
  events are idempotent by `cueId`; outcome uses the highest-confidence event and
  the threshold; an event below threshold is not satisfied; events on a completed
  session → 409.
- **`TenantIsolationTest`** (4): `rep1` cannot read, submit, or post events to
  `rep2`'s session (each → 404); list is scoped to the caller only.
- **`PhotoTest`** (2): upload then list a photo with a retrievable URL; uploading
  to another tenant's session → 404.
- **`ChecksheetAndHealthTest`** (3): health is public and reports the DB up; the
  checksheet loads by code; an unknown code → 404.

## BFF unit tests (14)

- **Location:** `road-to-sale-app/bff/tests/bff.test.ts`
- **Run:**
  ```bash
  cd road-to-sale-app/bff && ./test.sh   # vitest run
  ```
- **How:** The Core HTTP client (`coreClient`) is mocked with vitest, so these
  are fast and need nothing running. They assert the BFF's edge behavior:
  - **(a) validation** rejects bad bodies with `400` **before** the Core is
    called: login missing a password, create-session with a bad enum, an event
    with `confidence` out of range, an empty event batch.
  - **(b) auth presence:** a protected route with no `Authorization` (or a
    non-Bearer header) → `401`, Core never called.
  - **(c) happy path:** login and list-sessions forward correctly and relay the
    Core's exact envelope and status, including a non-200 (a `409` on submit).
  - **(d) multipart:** the photo route forwards `file` + `slot` to the Core; an
    invalid slot → `400` before the Core is called.
  - **(e) Core down:** a `CoreUnreachableError` maps to a `502` `ApiResponse`.

## Headless full-stack E2E (14 checks)

- **Location:** `road-to-sale-app/qa/tests/e2e.test.ts` (harness:
  `qa/tests/harness.ts`)
- **Run:**
  ```bash
  cd road-to-sale-app/qa && ./test.sh
  ```
- **How:** `startStack` (in `harness.ts`) boots the whole stack in-process and
  tears it down afterward:
  1. An embedded Postgres on a free port (no Docker).
  2. The Core jar as a child process, pointed at that Postgres (it builds the jar
     with Maven first if one is missing).
  3. The BFF as a child process (`npx tsx src/server.ts`), pointed at the Core.

  All transient data (the PG data dir, the Core storage dir, child logs) lives
  under `qa/.tmp/` and is removed on teardown.

### Black-box discipline

The harness drives the running stack only through the **BFF over HTTP**. It
imports no BFF or Core source. To prove data really persisted, it connects to the
embedded Postgres directly and queries the tables.

### What the E2E asserts

The ordered lifecycle (all through the BFF unless noted), then direct DB checks:

- **a. login** `rep1` → 200 with an `accessToken`.
- **b. checksheet** `GET /checksheet/RTS_HONDA_V1` → 200; total questions across
  steps is **16**.
- **c. create session** → 200, `status: ACTIVE`, captures the id.
- **d. list** includes the new session.
- **e. post events** — a batch of 5 events (four with confidence ≥ 0.6, one
  below) with unique `cueId`s → `accepted == 5`.
- **f. idempotency** — re-posting the same batch → `accepted == 0`.
- **g. upload photo** (`front_left`, multipart) → a `fileUrl` starting `/files/`;
  fetching that URL from the Core returns the exact PNG bytes.
- **h. list photos** includes the uploaded photo.
- **i. submit** → `status: COMPLETED`; the four ≥ 0.6 questions are `satisfied`,
  the below-threshold one is not; `progress.total == 16` and
  `progress.answered == 4`.
- **j. conflicts** — re-submitting → `409`; posting events to the completed
  session → `409`.
- **k. tenant isolation** — `rep2` doing `GET /sessions/{rep1SessionId}` → `404`.
- **DB: sessions** — exactly one row for `rep1`, `COMPLETED`, transcript set.
- **DB: session_events** — row count equals the distinct `cue_id` count (no
  duplicates persisted).
- **DB: photos** — exactly one row, slot `front_left`, with a `storage_path`.

## Tiering (org standard)

- **Tier 1 (before every commit, fast):** BFF unit tests (no services needed) and
  the Core slice tests.
- **Tier 2 (before a PR/release):** the headless full-stack E2E, which exercises
  the real ui→bff→Core→Postgres path through the public facade.

There is no separate Tier 3 suite for the backend yet.
