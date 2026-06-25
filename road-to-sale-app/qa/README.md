# Road to Sale — QA (headless E2E harness)

Full-stack, black-box integration tests for Road to Sale. The harness treats the
running stack as a black box: it drives the **BFF** over HTTP (and may read
Postgres directly to assert persistence) and **never imports** BFF or Core
source code.

## Scope (Phase 3)

Drive the full session lifecycle over HTTP through `bff → backend → Postgres`:

1. **login** (`POST /auth/login`) — get an access token for a seeded user.
2. **create session** (`POST /sessions`) — `type: LIVE`, `checksheetCode: RTS_HONDA_V1`.
3. **batch events** (`POST /sessions/:id/events`) — send cue events using real
   question IDs from `RTS_HONDA_V1` (1–16). Re-POST the same `cueId` batch and
   assert no duplicate rows (idempotency).
4. **upload photo** (`POST /sessions/:id/photos`) — multipart, e.g. `slot: front_left`.
5. **submit** (`POST /sessions/:id/submit`) — status becomes `COMPLETED`.
6. **read back** (`GET /sessions/:id`) — assert derived per-question `outcomes`
   (highest-confidence event per question, satisfied if confidence >= 0.6).

### Additional assertions

- **Tenant isolation:** a user in dealership A receives `404` for dealership B's
  session id; cross-tenant reads/writes are impossible.
- **Idempotency:** re-POSTing the same `cueId` batch does not create duplicates.
- **Auth:** protected routes reject missing/expired tokens; refresh works.

## Headless database

Tests use an **embedded Postgres** (zonky `embedded-postgres`, a real Postgres
binary launched in-process by the Core test profile) so the run is fully headless
with **no Docker daemon**. `deployment/docker-compose.yml` provides Postgres for
normal local dev.

## Running

```bash
npm install
npm test
```

Phase 3 adds a one-command runner that boots both services and runs the E2E green
with no manual steps.
