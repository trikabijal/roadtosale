# Tasks — 0005 Road to Sale Backend (Core + BFF + QA)

Derived from `0005-prd-road-to-sale-backend.md`. Built by Claude except where
marked **[Trisha]**. Organized by the 3-phase plan (frozen contract → parallel
build → integration). Check off as completed.

---

## Phase 1 — Frozen contract & scaffold (must land before Phase 2)

- [ ] 1.0 Create the umbrella layout `road-to-sale-app/{ui,bff,backend,qa}` and
      root `deployment/`.
- [ ] 1.1 Author the **shared API contract**: OpenAPI 3 spec for the 10 app-facing
      endpoints (§6.1) + DTO definitions. This is the single source both BFF and
      QA consume.
- [ ] 1.2 Derive the **NADA checksheet JSON** (`backend` resources, `RTS_HONDA_V1`)
      from `road-to-sale-app/src/cue-packs/road-to-sale-v1.yaml` so question IDs
      match the app's cue pack.
- [ ] 1.3 Flyway **baseline migration** `V1__init.sql`: `dealerships`, `users`,
      `sessions`, `session_events`, `photos` (+ enums, indexes, FKs, tenant cols).
- [ ] 1.4 Core scaffold: Maven `pom.xml` (Spring Boot 3, Web, Security, Data JPA,
      Flyway, Postgres driver, springdoc, zonky embedded-postgres for tests),
      `application.yml` (dev + test profiles), `build.sh`/`run.sh`/`test.sh`.
- [ ] 1.5 BFF scaffold: `package.json` (Fastify, @fastify/jwt, @fastify/multipart,
      @fastify/swagger, undici, zod/ajv, vitest), tsconfig, `build.sh`/`run.sh`/`test.sh`.
- [ ] 1.6 QA scaffold: `package.json` for the headless E2E harness + a place for
      the test plan.
- [ ] 1.7 `deployment/docker-compose.yml` (Postgres for local dev) + `.env.example`
      files for backend and bff.
- [ ] 1.8 Commit Phase 1 (frozen contract).

## Phase 2 — Parallel build (against the frozen contract)

### 2A — Java Core API
- [ ] 2A.1 JPA entities + repositories for all five tables.
- [ ] 2A.2 Auth: login + refresh, JWT issuance (access 1h / refresh 30d), password
      hashing (bcrypt/argon2), Spring Security filter; claims carry userId + dealershipId.
- [ ] 2A.3 Tenant scoping: every query filtered by token's dealershipId; cross-tenant
      access → 404; ignore body/query dealershipId.
- [ ] 2A.4 Checksheet endpoint (serve static JSON by code; 404 unknown).
- [ ] 2A.5 Sessions: create (LIVE|MOCK), list (mine), get-by-id with derived
      outcomes, submit (COMPLETED + transcript; 409 if already done).
- [ ] 2A.6 Events: batch append for ACTIVE sessions, idempotent by cueId, 409 if
      COMPLETED.
- [ ] 2A.7 Outcome derivation: highest-confidence event per question, satisfied if
      ≥ configurable threshold (default 0.6).
- [ ] 2A.8 Photos: multipart upload + list; storage behind an interface, local-disk impl.
- [ ] 2A.9 Cross-cutting: `ApiResponse` envelope, Bean Validation (400), `/health`,
      request logging (no secrets), springdoc OpenAPI.
- [ ] 2A.10 Seed runner: one dealership (Honda), 1–2 users, load checksheet.
- [ ] 2A.11 Core unit/slice tests (Tier 1) using embedded Postgres.

### 2B — Node BFF
- [ ] 2B.1 Fastify app + shared TS DTO types (mirror the contract).
- [ ] 2B.2 The 10 app-facing routes with JSON Schema validation.
- [ ] 2B.3 Core HTTP client (undici) + response reshaping to app DTOs.
- [ ] 2B.4 Auth passthrough/token handling; multipart relay for photo upload.
- [ ] 2B.5 `/health`, `@fastify/swagger`, error envelope, request logging.
- [ ] 2B.6 BFF unit tests (Tier 1) with the Core mocked.

## Phase 3 — Integration, E2E, docs

- [ ] 3.1 **Test plan** in `qa/` (tiers, what each test covers, black-box discipline).
- [ ] 3.2 **Headless E2E**: boot Core (embedded Postgres) + BFF, run the full
      lifecycle (login → create session → batch events → upload photo → submit →
      read back), assert DB rows + tenant isolation + event idempotency.
- [ ] 3.3 One-command runner (`qa/test.sh` or root `test-all.sh` hook) that starts
      both services and runs the E2E green, no manual steps, no Docker daemon.
- [ ] 3.4 Module docs (`api.md`, `architecture.md`, `flows.md`) for backend + bff;
      READMEs.
- [ ] 3.5 Drive everything to green; fix until the E2E passes.
- [ ] 3.6 Open PR `feat/rts-backend` → `development` (Bijal merges).

## Out of scope here (Trisha / later)
- [ ] **[Trisha]** Build `road-to-sale-app/ui/` (relocate current app in, then features).
- [ ] **[Trisha/front-end]** Rename facade `ISmartComplyClient` → `IRoadToSaleBackend`,
      reimplement against the BFF; reuse `SmartComplyClient.test.ts` fixtures as
      contract tests.
