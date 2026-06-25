# 0005 — PRD: Road to Sale Backend (Core API + BFF)

> **Status:** Draft for implementation
> **Owner / builder:** Trisha (intern)
> **Author:** Bijal
> **Audience:** This document is written for a junior developer. Terms are spelled
> out and assumptions are made explicit. If anything here is unclear, stop and ask
> before building — do not guess.

---

## 1. Introduction / Overview

Today the Road to Sale mobile app talks to an **external** backend called
**SmartComply** over HTTP. SmartComply is owned by another team, evolves quickly,
and models everything as compliance *audits* and *assignments*. Road to Sale only
uses a thin slice of it, and that slice has been bent to fit a domain it does not
belong to (a sales conversation is not an "audit").

This project replaces that dependency with a backend **Road to Sale owns end to
end**, designed around the real domain: a **session** — a salesperson's
conversation with a customer that is recorded, transcribed, and evaluated against
the static NADA "Road to the Sale" question set.

We are building **two new deployables**:

1. **Core API** — Java / Spring Boot + Postgres. The source of truth. Owns all
   data and business logic. Multi-tenant (serves many dealerships from one hosted
   instance).
2. **BFF (Backend-for-Frontend)** — Node / TypeScript (Fastify). The only thing
   the mobile app talks to. Shapes/relays data for the app and shares TypeScript
   types with the front end.

The existing **front end** (`road-to-sale-app/`) is unchanged in topology: it
talks **only** to the BFF.

### Why three layers

```
┌───────────────────────────────┐
│  Front end (TypeScript)        │  Expo / React Native — road-to-sale-app/
└───────────────┬───────────────┘
                │ HTTP — talks ONLY to the BFF, through the app-side facade
┌───────────────▼───────────────┐
│  BFF (Node / TypeScript)       │  App-facing API, mobile-shaped — road-to-sale-bff/
└───────────────┬───────────────┘
                │ HTTP — internal contract
┌───────────────▼───────────────┐
│  Core API (Java / Spring)      │  Domain + Postgres, source of truth — road-to-sale-backend/
└───────────────────────────────┘
```

This gives us two clean facades (front end → BFF, BFF → Core). The front end
never knows Java exists; the Core never knows what shape the mobile app needs.

---

## 2. Goals

1. **Own the backend.** No dependency on SmartComply for Road to Sale.
2. **Session-centric domain model**, not audit/assignment. A session is the
   first-class entity.
3. **Multi-tenant SaaS from day one.** One hosted backend serves many
   dealerships; each dealership's data is isolated by a `dealership_id`.
4. **Strict source isolation.** Backend source lives in its own folders, fully
   disjoint from the front-end source. Layers communicate only through facades /
   HTTP contracts — never by importing each other's code.
5. **Local-runnable.** A developer can run the whole stack (Postgres + Core +
   BFF) on their laptop with documented commands, with no external services.
6. **Free / open-source stack**, all source owned by us.
7. **Designed for what's next:** practice ("mock") sessions are supported by the
   data model even though their UI/flow is a later effort.

---

## 3. User Stories

- **As a salesperson**, I want to log in on my phone so that my sessions are tied
  to me and my dealership.
- **As a salesperson**, I want to start a session when a customer walks in, so the
  app can record and evaluate the conversation.
- **As the app (on the salesperson's behalf)**, I want to send detected
  conversation cues to the backend as they happen during a live session, so the
  NADA steps fill in automatically.
- **As a salesperson**, I want to attach trade-in photos to the session.
- **As a salesperson**, I want to finish (submit) the session and have its final
  state saved.
- **As a salesperson**, I want to see the list of my past sessions.
- **As a dealership owner (future)**, I need each dealership's data kept fully
  separate from every other dealership's.

---

## 4. Tech Stack & Repository Layout (decided — do not re-choose)

### 4.1 Stack

| Layer | Technology |
|---|---|
| Core API | Java 17, Spring Boot 3 (Spring Web, Spring Security, Spring Data JPA) |
| Migrations | Flyway (versioned SQL migrations) |
| Database | PostgreSQL 16 |
| Auth | JWT — access token **1 hour**, refresh token **30 days** |
| File storage | **Local disk** now, behind a storage interface; **AWS S3** is the likely later swap |
| Build (Core) | **Maven** (`pom.xml`) |
| BFF | Node 20, TypeScript, Fastify |
| BFF ↔ Core | HTTP (use `undici`/`axios`); BFF validates with JSON Schema |
| API docs | OpenAPI/Swagger on **both** layers (springdoc on Core, `@fastify/swagger` on BFF) |
| Hosting (future) | **Railway** (managed Postgres + services); keep config env-driven so it stays portable |

### 4.2 Repository layout (product umbrella with four disjoint folders)

The product lives under one umbrella folder, `road-to-sale-app/`, containing four
sibling folders. Each is self-contained (own build script, dependency file,
tests, docs) and shares **no source code** with the others — they communicate only
through facades / HTTP. (This nests the boundaries under the product folder rather
than at repo root; the disjointness rule is unchanged.)

```
road-to-sale-app/
├── ui/                  Front end (TypeScript / Expo-RN) — talks ONLY to the BFF
│   └── (the current road-to-sale-app/ contents relocate here — see §10)
├── bff/                 Node / Fastify BFF
│   ├── src/
│   ├── tests/
│   ├── docs/            api.md, architecture.md, flows.md
│   ├── build.sh  run.sh  test.sh
│   └── package.json
├── backend/             Java / Spring Core API
│   ├── src/
│   ├── tests/
│   ├── docs/            api.md, architecture.md, flows.md
│   ├── db/migrations/   Flyway SQL
│   ├── build.sh  run.sh  test.sh
│   └── pom.xml
└── qa/                  Full-stack black-box E2E/integration tests
    ├── tests/           exercise ui→bff→backend through public facades only
    └── package.json

deployment/
└── docker-compose.yml   Postgres for local dev (and optionally backend + bff)
```

> The **rule** is fixed: the four folders are disjoint — no folder imports another's
> source; all cross-folder communication is via facade/HTTP. `qa/` tests treat the
> running stack as a black box (no internal imports), per the org testing
> standards.

### 4.3 Ownership & test execution

- **Built by Claude (this effort):** `backend/`, `bff/`, `qa/`, all DB schema +
  Flyway migrations, build/run/test scripts, and module docs.
- **Built by Trisha:** `ui/` (the React Native app) only.
- **Real test data:** the seeded NADA checksheet is derived from the existing
  `road-to-sale-app/src/cue-packs/road-to-sale-v1.yaml` (cue → `template_question_id`
  map) so the E2E exercises the actual Road to Sale question set.
- **Headless E2E database:** uses an **embedded Postgres** (zonky
  `embedded-postgres`, a real Postgres binary launched in-process) so the test
  runs fully headless without a Docker daemon. `deployment/docker-compose.yml`
  provides Postgres for normal local dev.
- **E2E scope:** API-level — drives the full session lifecycle over HTTP through
  `bff → backend → Postgres` and asserts persistence. It does **not** drive the
  mobile UI.

---

## 5. Domain & Data Model (owned by the Core API)

All tables carry a `dealership_id` where relevant. **Tenancy is enforced on the
server**: the `dealership_id` is read from the authenticated user's token, never
trusted from the request body.

### 5.1 Tables (Postgres)

**`dealerships`** — one row per dealership (tenant)
| column | type | notes |
|---|---|---|
| id | uuid (PK) | |
| name | text | e.g. "Honda of Fremont" |
| created_at | timestamptz | |

**`users`** — salespeople (and future roles)
| column | type | notes |
|---|---|---|
| id | uuid (PK) | |
| dealership_id | uuid (FK) | tenant |
| username | text (globally unique — see post-review note) | |
| password_hash | text | bcrypt/argon2 — never store plaintext |
| name | text | display name |
| roles | text[] | e.g. `{SALESPERSON}` |
| created_at | timestamptz | |

> **Post-review decision (W5):** `username` is **globally unique**
> (`UNIQUE (username)`), not unique-per-dealership. Login looks a user up by
> username alone (the credentials carry no dealership), so a per-dealership
> uniqueness rule would make login ambiguous. `dealership_id` is still the tenant
> FK and is indexed; it just is not part of the login key.

**`sessions`** — the core entity (replaces SmartComply assignment/inspection)
| column | type | notes |
|---|---|---|
| id | uuid (PK) | |
| dealership_id | uuid (FK) | tenant |
| user_id | uuid (FK) | the salesperson |
| type | enum `LIVE` \| `MOCK` | mock = future practice sessions |
| status | enum `ACTIVE` \| `COMPLETED` | |
| checksheet_code | text | which NADA template, e.g. `RTS_HONDA_V1` |
| context | jsonb | optional `{ customerName?, vehicleOfInterest?, ... }` |
| transcript | text (nullable) | final transcript, set at submit |
| started_at | timestamptz | |
| ended_at | timestamptz (nullable) | set at submit |

**`session_events`** — **append-only** log of detected cues (replaces
`createOrUpdateUserChksAns`)
| column | type | notes |
|---|---|---|
| id | uuid (PK) | |
| session_id | uuid (FK) | |
| question_id | text | which NADA question the cue satisfies |
| step_no | int | NADA step 1–10 (CHECK `>= 1` — post-review) |
| detected_at | timestamptz | when the cue fired |
| confidence | numeric(4,3) | 0..1 (CHECK `0..1` — post-review) |
| transcript_span | text | the words that triggered it |
| source | enum `feature` \| `workflow` | how the cue was detected |
| cue_id | text | client-generated id for idempotency |

> **Post-review decision (W2/W3):** `confidence` is stored as `numeric(4,3)` with
> a `CHECK (confidence >= 0 AND confidence <= 1)`, and `step_no` carries a
> `CHECK (step_no >= 1)`, so out-of-range values are rejected at the database, not
> just in code.

> **Why append-only:** a live session produces cues over time. Storing them as an
> immutable event stream (not upserted rows) preserves the timeline, supports
> replay/analytics, and makes mock-session scoring trivial. The "answer" the rep
> reviews per question is **derived** (latest / highest-confidence event per
> question) at read/submit time — it is not a stored mutable field.

**`photos`** — trade-in photos
| column | type | notes |
|---|---|---|
| id | uuid (PK) | |
| session_id | uuid (FK) | |
| slot | enum (see below) | which photo slot |
| storage_path | text | path/key in the storage backend |
| mime_type | text | |
| uploaded_at | timestamptz | |

Photo slots: `front_left`, `front_right`, `rear_left`, `rear_right`, `interior`,
`odometer`, `vin`.

### 5.2 NADA checksheet — static reference data (NOT a table)

The NADA "Road to the Sale" question set does not change between sessions. Store
it as a **versioned JSON file** in the Core (`resources/checksheets/rts_honda_v1.json`)
and serve it via an endpoint. Shape (kept compatible with what the app already
renders):

```ts
ChecksheetDTO {
  code: string;                 // "RTS_HONDA_V1"
  name: string;
  version: string;
  steps: Array<{
    id: string;                 // step id
    name: string;               // e.g. "Greet / Hospitality"
    orderNo: number;            // 1..10
    questions: Array<{
      id: string;               // question_id used in session_events
      text: string;
      orderNo: number;
      isMandatory: boolean;
      resultType: 'CUE' | 'FREE_TEXT' | 'NUMERIC' | 'PHOTO';
    }>;
  }>;
}
```

---

## 6. API Contracts

There are two contracts. The **app-facing** contract (BFF) is the authoritative
one the front end depends on. The **Core** contract is internal and consumed only
by the BFF.

All JSON responses (except multipart upload responses) use the existing envelope
the app already understands:

```ts
ApiResponse<T> = { status: number; message: string; data: T }
```

### 6.1 App-facing API (BFF) — what the front end calls

> This is the modern replacement for the old `ISmartComplyClient`. The app-side
> facade will be renamed to `IRoadToSaleBackend` (front-end task — see §10) and
> these are the methods/endpoints it maps to.

| # | Method & path | Replaces (old SmartComply) | Body → returns |
|---|---|---|---|
| 1 | `POST /auth/login` | `/api/user/login` | `{username, password, deviceType}` → `{accessToken, refreshToken, user}` |
| 2 | `POST /auth/refresh` | `/api/user/refreshToken` | `{refreshToken}` → `{accessToken, refreshToken}` |
| 3 | `GET /checksheet/:code` | `getChecksheetDetail` | → `ChecksheetDTO` (static) |
| 4 | `GET /sessions` | `getMyAssignments` | → `SessionSummaryDTO[]` |
| 5 | `POST /sessions` | `addAuditAssignments` + `createOrUpdate` | `{type, checksheetCode, context?}` → `SessionDTO` |
| 6 | `GET /sessions/:id` | (new) | → `SessionDTO` (incl. derived per-question outcomes) |
| 7 | `POST /sessions/:id/events` | `createOrUpdateUserChksAns` | `{events: SessionEventDTO[]}` → `{accepted: number}` |
| 8 | `POST /sessions/:id/submit` | `submitSession` | `{transcript?}` → `SessionDTO` (status `COMPLETED`) |
| 9 | `POST /sessions/:id/photos` | `uploadTradePhoto` | multipart `{slot, file}` → `PhotoDTO` |
| 10 | `GET /sessions/:id/photos` | `getTradePhotos` | → `PhotoDTO[]` |
| 11 | `GET /sessions/:id/photos/:photoId/content` | (new — replaces public `/files`) | → raw image bytes (authed) |

> **Post-review decision (#9/#10 + §5 photos):** photo bytes are served **only**
> through the authenticated, tenant-scoped endpoint #11 above
> (`GET /sessions/{id}/photos/{photoId}/content`), which `PhotoDTO.fileUrl` points
> at. The original public `/files/**` handler was an IDOR (any path under the
> storage dir was world-readable) and was removed. Uploads are also restricted:
> only JPEG/PNG/WebP images are accepted (validated by sniffing the file's leading
> bytes, not the declared `Content-Type`) → `400` otherwise, and files are capped
> at 10 MB → `413` over the cap.

App-facing DTOs:

```ts
user = { id, username, name, dealershipId, roles: string[] }

SessionSummaryDTO = {
  id, type, status, checksheetCode,
  startedAt, endedAt?,
  context?: { customerName?, vehicleOfInterest? },
  progress: { answered: number, total: number }
}

SessionDTO = SessionSummaryDTO & {
  transcript?: string,
  outcomes: Array<{ questionId, stepNo, satisfied: boolean,
                    confidence?: number, transcriptSpan?: string }>
}

SessionEventDTO = {
  cueId, questionId, stepNo, detectedAt,
  confidence, transcriptSpan, source: 'feature' | 'workflow'
}

PhotoDTO = { id, sessionId, slot, fileUrl, uploadedAt }
// fileUrl is the BFF-relative content path:
//   /sessions/{sessionId}/photos/{photoId}/content   (authed; see endpoint 11)
```

Notes:
- **Idempotency:** `POST /sessions/:id/events` must ignore events whose `cueId`
  was already recorded for that session (the app may retry on flaky networks).
  Endpoint accepts a batch.
- `outcomes` in `SessionDTO` is **derived** from `session_events`, not stored.

### 6.2 Core API (Java) — internal, called by the BFF

The Core exposes equivalent resource endpoints under `/api/v1` (e.g.
`POST /api/v1/sessions`, `POST /api/v1/sessions/{id}/events`, `GET /api/v1/checksheets/{code}`,
auth endpoints, photo upload/list). The Core:
- owns persistence, multi-tenant scoping, auth/token issuance, and the static
  checksheet;
- returns domain-shaped data; the BFF reshapes to the app-facing DTOs above.

For v1 the BFF may be largely a validating relay (auth passthrough + reshape +
batching), but it is a real, separate deployable so app-shaped concerns never
leak into the Core.

> **Post-review decision (TR1):** this is how it shipped — the Core's resource
> endpoints **are** under `/api/v1` and the checksheet path is **plural**
> (`GET /api/v1/checksheets/{code}`). `GET /health` stays at the **root** on the
> Core. The app-facing BFF contract is unchanged: root paths and the singular
> `GET /checksheet/{code}`. The BFF maps each app-facing path to the matching
> Core `/api/v1` path in `bff/src/coreClient.ts`.

---

## 7. Functional Requirements

Numbered so they can be checked off. "The system" = the backend (Core + BFF as
appropriate).

**Auth & identity**
1. The system must let a user log in with username + password and return a JWT
   access token (**1 hour** lifetime) and a refresh token (**30 day** lifetime).
2. Passwords must be stored only as a salted hash (bcrypt or argon2). Plaintext
   passwords must never be stored or logged.
3. The system must issue a new access+refresh pair from a valid refresh token, and
   reject invalid/expired refresh tokens.
4. Every protected endpoint must require a valid access token and reject requests
   without one (`401`).
5. The access token must carry the user's `userId` and `dealershipId` as claims.

> **Post-review decision (B1 — JWT prod fail-fast):** outside the `dev`/`test`
> profiles the Core **refuses to start** if the JWT signing secret is blank, is
> the known dev default, or is shorter than 32 bytes (256 bits). This stops a
> production deploy from silently accepting forged tokens signed with a weak or
> default key. There is no secret padding.

**Multi-tenancy (critical)**
6. Every data query must be scoped to the `dealershipId` from the caller's token.
7. A user from dealership A must never be able to read or modify dealership B's
   sessions, events, or photos — even if they pass another dealership's IDs.
   Attempts must return `404` (not `403`, to avoid leaking existence).
8. `dealership_id` from a request body/query must be ignored; only the token's
   value is authoritative.

**Checksheet**
9. The system must serve the NADA checksheet for a given `code` as static data.
10. If the `code` is unknown, return `404`.

**Sessions**
11. A salesperson must be able to create a session with `type` `LIVE` or `MOCK`
    and an optional `context`. New sessions start with status `ACTIVE`.
12. The system must list the authenticated user's sessions (most recent first).
    **Post-review:** the list is **paginated** — `page`+`size` or `limit`+`offset`,
    default size 50, hard cap 200 — and batch-loads each page's events in one
    query (no N+1).
13. The system must return a single session by id, including derived per-question
    `outcomes`.
14. The system must let the user submit a session: set status `COMPLETED`, set
    `ended_at`, and store the optional final `transcript`. Submitting an already
    completed session must be rejected (`409`).

**Session events (live cues)**
15. The system must accept a **batch** of cue events for an `ACTIVE` session and
    append them to the event log.
16. Events must be **idempotent by `cueId`** per session (duplicates ignored,
    not errored).
17. Events for a `COMPLETED` session must be rejected (`409`).
18. Per-question outcome must be **derived** from events: for each `questionId`,
    take the **highest-confidence** event; the question is "satisfied" if that
    event's confidence ≥ a configurable threshold (default **0.6**). Outcomes are
    never stored as mutable fields — always computed from the event log.

**Photos**
19. The system must accept a `multipart/form-data` upload of one photo for a given
    `slot` on a session, store the file via the storage abstraction, and return a
    `PhotoDTO` with a retrievable `fileUrl`. **Post-review:** the upload is
    restricted to JPEG/PNG/WebP images (validated by sniffing the file's leading
    bytes → `400` otherwise) and capped at 10 MB (`413` over the cap); the
    `fileUrl` resolves to the authed, tenant-scoped content endpoint (#11), not a
    public path.
20. The system must list all photos for a session.

**Cross-cutting**
21. All request bodies must be validated; invalid input returns `400` with a clear
    message (Fastify JSON Schema on the BFF; Bean Validation on the Core).
22. Both Core and BFF must expose a `GET /health` endpoint returning `200` when
    the service (and, for Core, the DB) is reachable.
23. Both services must log each request (method, path, status, latency, userId,
    dealershipId) — but never log secrets, tokens, or passwords.
24. Both services must expose OpenAPI/Swagger documentation.
25. Errors must use the `ApiResponse` envelope with appropriate HTTP status codes.

---

## 8. Non-Goals (out of scope for this build)

- **Raw audio storage.** We store events + final transcript text + photos only.
- **Mock-session UI/flow.** The data model supports `type = MOCK`; building the
  practice experience is a later PRD.
- **On-device work.** Transcription and cue detection happen in the app, not on
  the server. The backend never receives audio.
- **Migrating data out of SmartComply.** This is greenfield; no import.
- **Dealership self-signup / admin console.** Dealerships and users are created
  via a seed/admin script for v1.
- **Real-time push (WebSocket/SSE).** The app POSTs events; reading back via
  request/refresh is fine for v1.
- **Production deploy automation.** Local run + one manual dev deploy target is
  enough for v1 (full CI/CD is a follow-up per the org CI/CD playbook).
- **Password reset / email flows.**
- **Refresh-token rotation / revocation (post-review deferral, W6).** v1 tokens
  are stateless and valid until they expire (access 1h, refresh 30d). There is no
  logout, no server-side denylist, and a refresh does not invalidate the old
  refresh token; a stolen, unexpired token stays usable until it expires.
  Rotation + revocation is tracked for v2.
- **`LoginRequest.deviceType` behavior (post-review note, N3).** The field is
  required and validated on both layers but is not yet stored or used. It is kept
  for forward device-tracking so the contract does not have to change later.

---

## 9. Local Development (how Trisha runs the whole stack)

Each deployable follows the org build standard (`build.sh`, `run.sh`, `test.sh`).

1. **Postgres:** `docker compose up -d` (from `deployment/`) starts Postgres.
2. **Core:** `cd road-to-sale-app/backend && ./build.sh && ./run.sh` — runs Flyway
   migrations on start, then boots Spring Boot (default port e.g. 8090).
3. **Seed:** a one-command seed creates one dealership ("Honda"), 1–2 test users,
   and loads the `RTS_HONDA_V1` checksheet.
4. **BFF:** `cd road-to-sale-app/bff && ./build.sh && ./run.sh` — boots Fastify
   (default port e.g. 8089, so the existing app config keeps working) pointed at
   the Core.
5. **Front end:** `cd road-to-sale-app/ui`, point its facade base URL at the BFF,
   and run as today.

Each deployable ships a `.env.example` documenting required variables (DB URL,
JWT secret, Core base URL for the BFF, storage dir, ports). Real `.env` files are
gitignored.

---

## 10. Coordinating front-end change (dependency, not built by Trisha here)

Two **front-end tasks**, tracked separately (not built by Trisha in this backend
PRD):

1. **Relocate the existing app into `road-to-sale-app/ui/`.** Today the app's
   source sits directly under `road-to-sale-app/`; it moves into the new `ui/`
   sub-folder so `ui/`, `bff/`, `backend/`, `qa/` are siblings.
2. **Rename/reshape the facade.** `ISmartComplyClient` becomes
   **`IRoadToSaleBackend`** with the §6.1 methods, and `SmartComplyClient` is
   reimplemented to call the BFF. The existing `SmartComplyClient.test.ts`
   fixtures become the acceptance tests for the BFF contract. Until this lands,
   the app can run against the existing mock client.

---

## 11. Success Metrics / Acceptance Criteria

The build is "done" when:

1. The whole stack (Postgres + Core + BFF) runs locally from documented commands,
   with **no external services** and no SmartComply.
2. A full **session lifecycle** works end to end through the BFF: login → create
   session → POST a batch of events → upload a photo → submit → GET the session
   and see correct derived outcomes.
3. **Tenant isolation test passes:** a user in dealership A receives `404` for
   dealership B's session id; cross-tenant reads/writes are impossible.
4. **Idempotency test passes:** re-POSTing the same `cueId` batch does not create
   duplicate events.
5. Auth tests pass: protected routes reject missing/expired tokens; refresh works.
6. Both services expose `GET /health` and OpenAPI docs.
7. Tier-1 tests run in under ~30s and pass before each commit; the suite passes
   before any merge.
8. Each deployable has its three docs (`api.md`, `architecture.md`, `flows.md`)
   and a README.

---

## 12. Decisions (resolved) & Remaining Questions

All six open questions are now **decided**:

1. **Folder structure** — product umbrella `road-to-sale-app/` with `ui/`, `bff/`,
   `backend/`, `qa/`. Existing app contents relocate into `ui/` (separate
   restructure task, see §10).
2. **Build tool** — **Maven**.
3. **Hosting (future)** — **Railway**; keep config env-driven so it stays portable.
4. **Photo storage** — **local disk now**, behind a storage interface; **AWS S3**
   is the likely later swap (not built in v1).
5. **Token lifetimes** — access **1 hour**, refresh **30 days**.
6. **Outcome rule** — **highest-confidence event** per question, satisfied if
   confidence ≥ configurable threshold (default **0.6**).

_No open questions remain. The threshold (0.6) is tunable and can be revisited
once real sessions produce confidence distributions._

## 13. Post-review decisions (resolved against the shipped code)

A 5-agent code review (tracked in `dev/tasks/review-0005-findings.md`) produced a
set of decisions that this PRD now reflects. These tightened the design rather
than changing intent:

| Area | Decision | PRD §updated |
|---|---|---|
| Core paths (TR1) | Core resource endpoints are under `/api/v1`; checksheet is plural `/api/v1/checksheets/{code}`; `/health` stays at root. App-facing BFF stays root + singular `/checksheet`. | §6.2 |
| Photos (B3/B4) | No public `/files`. Bytes served via authed, tenant-scoped `GET /sessions/{id}/photos/{photoId}/content` (#11). Upload limited to JPEG/PNG/WebP (magic-byte sniff → `400`) and 10 MB (`413`). | §5, §6.1 #9–#11, §7 #19 |
| Login identity (W5) | `users.username` is **globally unique**, not per-dealership. | §5.1 |
| Event constraints (W2/W3) | `confidence` is `numeric(4,3)` with a `0..1` CHECK; `step_no` has a `>= 1` CHECK. | §5.1 |
| Session list (W1) | `GET /sessions` is **paginated** (page+size or limit+offset; default 50, cap 200) and batch-loads events (no N+1). | §7 #12 |
| Auth secret (B1) | JWT **fail-fast** in prod: refuse to start on a blank / dev-default / < 32-byte secret; no padding. | §7 (auth) |
| Refresh tokens (W6) | **Deferred to v2:** stateless tokens, valid until expiry; no rotation, revocation, or logout. | §8 |
| `deviceType` (N3) | Validated but unused for now; kept for forward device-tracking. | §8 |
