# Data Model

The Postgres schema the Core API owns, plus how multi-tenancy, the append-only
event log, and derived outcomes work. The schema is created by one Flyway
migration: `backend/src/main/resources/db/migration/V1__init.sql`. Hibernate runs
in `validate` mode, so it never changes the schema — Flyway is the only source of
truth for it.

See [architecture.md](./architecture.md) for the design reasoning and
[data flow in flows.md](./flows.md).

## Tables

Five tables. Primary keys are `uuid`, defaulted with `gen_random_uuid()` (the
`pgcrypto` extension is enabled by the migration).

### `dealerships` — one row per tenant

| column | type | notes |
|---|---|---|
| `id` | uuid | PK |
| `name` | text | not null, e.g. "Honda of Fremont" |
| `created_at` | timestamptz | not null, default `now()` |

### `users` — salespeople (and future roles)

| column | type | notes |
|---|---|---|
| `id` | uuid | PK |
| `dealership_id` | uuid | not null, FK → `dealerships(id)` (tenant) |
| `username` | text | not null |
| `password_hash` | text | not null, BCrypt — never plaintext |
| `name` | text | not null, display name |
| `roles` | text[] | not null, default `'{}'`, e.g. `{SALESPERSON}` |
| `created_at` | timestamptz | not null, default `now()` |

Constraint: `uq_users_username` — `UNIQUE (username)`. A username is unique
**across all dealerships** (globally), because it is the login identity: login
looks a user up by username alone (the credentials carry no dealership), so two
users sharing a username would make login ambiguous. `dealership_id` is still
the tenant FK and is indexed (`idx_users_dealership`); it just is not part of
the login key.

### `sessions` — the core entity

| column | type | notes |
|---|---|---|
| `id` | uuid | PK |
| `dealership_id` | uuid | not null, FK → `dealerships(id)` (tenant) |
| `user_id` | uuid | not null, FK → `users(id)` (the salesperson) |
| `type` | text | not null, CHECK `IN ('LIVE','MOCK')` |
| `status` | text | not null, default `'ACTIVE'`, CHECK `IN ('ACTIVE','COMPLETED')` |
| `checksheet_code` | text | not null, e.g. `RTS_HONDA_V1` |
| `context` | jsonb | nullable, e.g. `{ customerName, vehicleOfInterest }` |
| `transcript` | text | nullable, set at submit |
| `started_at` | timestamptz | not null, default `now()` |
| `ended_at` | timestamptz | nullable, set at submit |

Index: `idx_sessions_dealership_user` on `(dealership_id, user_id)` — backs the
tenant-scoped "list my sessions" query.

### `session_events` — append-only log of detected cues

| column | type | notes |
|---|---|---|
| `id` | uuid | PK |
| `session_id` | uuid | not null, FK → `sessions(id)` |
| `question_id` | text | not null, which NADA question the cue satisfies |
| `step_no` | int | not null, NADA step number, CHECK `>= 1` |
| `detected_at` | timestamptz | not null, when the cue fired |
| `confidence` | numeric(4,3) | not null, CHECK `>= 0 AND <= 1` |
| `transcript_span` | text | nullable, the words that triggered it |
| `source` | text | not null, CHECK `IN ('feature','workflow')` |
| `cue_id` | text | not null, client-generated id for idempotency |

Constraints:
- `uq_session_events_session_cue` — `UNIQUE (session_id, cue_id)`. The
  idempotency guarantee: a given `cueId` can be recorded at most once per
  session.
- `chk_session_events_confidence` — `CHECK (confidence >= 0 AND confidence <= 1)`.
  Combined with the `numeric(4,3)` type, `confidence` is a number in `[0, 1]`
  with three decimal places.
- `chk_session_events_step_no` — `CHECK (step_no >= 1)`. NADA step numbers start
  at 1.

Index: `idx_session_events_session` on `(session_id)`.

### `photos` — trade-in photos

| column | type | notes |
|---|---|---|
| `id` | uuid | PK |
| `session_id` | uuid | not null, FK → `sessions(id)` |
| `slot` | text | not null, CHECK over the 7 slots below |
| `storage_path` | text | not null, key in the storage backend |
| `mime_type` | text | not null |
| `uploaded_at` | timestamptz | not null, default `now()` |

Slot CHECK values: `front_left`, `front_right`, `rear_left`, `rear_right`,
`interior`, `odometer`, `vin`.

Index: `idx_photos_session` on `(session_id)`.

## Multi-tenancy

Every tenant-relevant table carries `dealership_id`. The rule is enforced on the
server, not in the schema alone:

- `dealership_id` is read **only** from the authenticated user's JWT, never from
  the request body or query.
- Every read and write is scoped to that `dealership_id` (for example
  `SessionRepository.findByIdAndDealershipId`).
- A row owned by another dealership is treated as **not found** (`404`), so the
  API never reveals that it exists.

Tenants share one database; they are separated by `dealership_id`, not by
separate schemas or databases.

## The append-only event log

`session_events` is never updated in place. The app posts a batch of cue events
while a session is `ACTIVE`; the Core appends each one. Re-posting the same
`cueId` is a no-op thanks to `UNIQUE (session_id, cue_id)`.

The append uses `INSERT ... ON CONFLICT (session_id, cue_id) DO NOTHING`
(`SessionEventRepository.insertIgnoreDuplicate`), which returns 1 for a new row
and 0 for a duplicate. `SessionService.appendEvents` sums those to report
`accepted` (the count of new events). Posting events to a `COMPLETED` session is
rejected with `409`.

## The NADA checksheet is static JSON, not a table

The question set does not change between sessions, so it is not stored in
Postgres. It lives as a versioned JSON file in the Core
(`backend/src/main/resources/checksheets/rts_honda_v1.json`, code
`RTS_HONDA_V1`) and is served via `GET /checksheet/{code}`. `ChecksheetService`
loads and caches it in memory; an unknown code returns `404`.

`RTS_HONDA_V1` has 10 steps and **16 questions** total (step 7, "Service Walk",
has zero questions). Each `question.id` (`"1"`..`"16"`) is the `question_id`
referenced by `session_events`.

## How outcomes are derived

There is no stored per-question "answer" column. The outcome a rep reviews is
computed from `session_events` at read/submit time
(`SessionService.deriveOutcomes`):

1. Group events by `question_id`.
2. For each question, pick the **highest-confidence** event.
3. The question is **satisfied** when that event's `confidence` is at or above the
   threshold (`roadtosale.outcome.confidence-threshold`, default `0.6`).

`progress.total` is the number of questions in the session's checksheet (16 for
`RTS_HONDA_V1`); `progress.answered` is the count of satisfied questions.

## Seed data (dev + E2E)

Seeded idempotently on startup under the `dev` profile (`DevSeedRunner` →
`SeedService.seed()`), and available to Core tests via the same `SeedService`:

| Dealership | username | password | name | roles |
|---|---|---|---|---|
| Honda of Fremont | `rep1` | `password123` | Sam Rep | `SALESPERSON` |
| Honda of Oakland | `rep2` | `password123` | Riley Rep | `SALESPERSON` |

`rep1` and `rep2` are in different dealerships, which is what the cross-tenant
`404` test relies on.
