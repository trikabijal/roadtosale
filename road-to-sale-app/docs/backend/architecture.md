# Architecture

How the Road to Sale backend is put together: three tiers, two facades, a
session-centric domain, and multi-tenancy enforced from the auth token.

See [README.md](./README.md) for build/run/test commands and
[data-model.md](./data-model.md) for the schema.

## The three tiers

```
┌─────────────────────────────────────────────┐
│  ui — Expo / React Native (front end)         │
└───────────────┬───────────────────────────────┘
                │ HTTP — talks ONLY to the BFF
┌───────────────▼───────────────────────────────┐
│  bff — Node / Fastify (app-facing API)         │
│  validates input, checks auth header, relays   │
└───────────────┬───────────────────────────────┘
                │ HTTP — CORE_BASE_URL
┌───────────────▼───────────────────────────────┐
│  backend — Java / Spring (Core API)            │
│  domain + auth + multi-tenancy + checksheet    │
└───────────────┬───────────────────────────────┘
                │ JDBC
┌───────────────▼───────────────────────────────┐
│  Postgres 16                                   │
└─────────────────────────────────────────────┘
```

- **ui** — the mobile app. Talks only to the BFF.
- **bff** — Node 20 / TypeScript / Fastify. The only thing the app talks to.
- **backend (Core API)** — Java 17 / Spring Boot 3 (Web, Security, Data JPA,
  Validation), Flyway, Postgres 16, JWT (jjwt), springdoc OpenAPI. The source of
  truth.
- **Postgres** — one shared database; tenants are separated by `dealership_id`,
  not by separate databases.

## The two-facade isolation rule

There are exactly two facades:

1. **ui → bff** — the app-facing contract, written down in
   `road-to-sale-app/contract/openapi.yaml`.
2. **bff → Core** — an internal HTTP contract the BFF depends on.

The four folders (`ui/`, `bff/`, `backend/`, `qa/`) share **no source code**.
No folder imports another folder's source; all cross-folder communication is
over HTTP. Two examples of the rule in the code:

- The BFF's `coreClient.ts` is the single place that talks to the Core. It calls
  the Core over HTTP via `undici` and never imports Java.
- The `qa/` E2E harness (`qa/tests/harness.ts`) launches the Core and BFF as
  child processes and drives them over HTTP. It imports neither's source; it only
  reads Postgres directly to check that data persisted.

### The BFF is a validating relay

For v1 the BFF mostly validates and forwards. Each route:

1. Validates the request body/params with a Fastify JSON Schema
   (`bff/src/schemas.ts`). Bad input returns `400` **before** the Core is called.
2. On protected routes, checks that an `Authorization: Bearer ...` header is
   present (`requireAuth` in `bff/src/util.ts`). Missing → `401`, again without
   calling the Core. The BFF does **not** verify the JWT signature; the Core is
   the auth authority.
3. Forwards the request to the Core via `coreClient.ts`, passing the
   `Authorization` header through verbatim.
4. Relays the Core's HTTP status and JSON body straight back (`relay` in
   `util.ts`). The Core's body is already in the `ApiResponse` envelope, so the
   BFF does not re-wrap it.

If the Core cannot be reached at all, `coreClient` throws
`CoreUnreachableError`, which `withCore` maps to a `502` envelope. This keeps
app-shaped concerns (multipart handling, batching, validation messages) out of
the Core, while the Core stays the only place with business logic and data.

## The session-centric domain

The first-class entity is a **session** — one salesperson's conversation with a
customer. A session is not an "audit"; the old SmartComply audit/assignment model
is gone.

- **`Session`** (`backend/.../domain/Session.java`) has a `type` of `LIVE` or
  `MOCK` and a `status` of `ACTIVE` or `COMPLETED`. `LIVE` is a real walk-in;
  `MOCK` is reserved for future practice sessions (the data model supports it; no
  UI is built for it yet).
- **`session_events`** is an **append-only** log of detected conversation cues.
  The app posts cues as they fire during a live session; the server appends them
  and never updates them in place. Idempotency is by client-generated `cueId`
  (see [data-model.md](./data-model.md)).
- **Outcomes are derived, not stored.** The per-question result a rep reviews is
  computed from the event log at read/submit time: for each question, take the
  highest-confidence event; the question is "satisfied" if that event's
  confidence is at or above a threshold (default `0.6`, set by
  `roadtosale.outcome.confidence-threshold`). See `deriveOutcomes` in
  `SessionService.java`. There is no stored mutable "answer" column.
- **The NADA checksheet is static reference data, not a table.** It lives as a
  versioned JSON file (`backend/.../resources/checksheets/rts_honda_v1.json`,
  code `RTS_HONDA_V1`). The app reads it via the BFF at `GET /checksheet/{code}`;
  the BFF relays to the Core's `GET /api/v1/checksheets/{code}` (plural).
  `ChecksheetService` loads and caches it; an unknown code returns `404`.

### Why append-only plus derived outcomes

A live session produces cues over time. Storing them as an immutable stream
preserves the timeline, supports later replay and analytics, and makes
mock-session scoring straightforward. Because the reviewed answer is always
computed from the log, there is no mutable field to keep in sync.

## Multi-tenancy by dealership_id

The backend is multi-tenant: one hosted instance serves many dealerships. Every
relevant table carries a `dealership_id`.

- **Tenancy comes from the token.** Login issues a JWT carrying `userId` and
  `dealershipId` claims (`JwtService.java`). The Core reads `dealershipId` only
  from the verified token (`AuthenticatedUser`, injected via the `@CurrentUser`
  resolver). A `dealership_id` in a request body or query is ignored.
- **The signing secret must be strong in production.** On startup outside the
  `dev`/`test` profiles, `JwtService` refuses to boot if the JWT secret is
  blank, is the known dev default, or is shorter than 32 bytes (256 bits). This
  prevents a deploy from silently accepting forged tokens signed with a weak or
  default key. There is no secret padding.
- **Every query is scoped to the caller's dealership.** For example,
  `SessionService.requireOwnedSession` looks up a session with
  `findByIdAndDealershipId(sessionId, caller.dealershipId())`.
- **Cross-tenant access returns `404`, not `403`.** A session belonging to
  another dealership is treated as not found, so the API never reveals that the
  row exists (`ApiException.NotFound`).

## Key decisions and why

| Decision | Choice | Why |
|---|---|---|
| Core language/stack | Java 17, Spring Boot 3, Postgres 16 | Owned by the team; strong typing, JPA, Flyway, mature auth. The source of truth lives here. |
| BFF | Node 20, TypeScript, Fastify | App-shaped concerns (validation messages, batching, multipart) stay close to the front end; the BFF shares TypeScript DTO types with the app. |
| BFF role in v1 | Thin validating relay | Keeps business logic in one place (the Core) while still being a real, separate deployable so the app's shape never leaks into the Core. |
| Migrations | Flyway versioned SQL | One canonical schema (`V1__init.sql`); Hibernate runs in `validate` mode, so the schema is never auto-mutated. |
| Auth | JWT, access 1h / refresh 30d, BCrypt hashes | Stateless; the access token carries `userId` and `dealershipId` for tenant scoping. The app's signing secret is checked at startup — see fail-fast below. |
| Outcomes | Derived from events, threshold default 0.6 | No mutable answer state; the threshold is tunable. |
| Checksheet | Static versioned JSON | The NADA question set does not change per session; no table is needed. |
| Photo storage | Local disk behind a `StorageService` interface | Simple for v1; S3 is the likely later swap behind the same interface. Bytes are served only through the authed, tenant-scoped content endpoint — never a public URL. |

## Cross-cutting concerns

- **Response envelope.** Every JSON response (success and error) on both the BFF
  and the Core uses `ApiResponse { status, message, data }`. See [api.md](./api.md).
- **Error mapping (Core).** `GlobalExceptionHandler` renders every exception as an
  `ApiResponse` with the right status: `ApiException.BadRequest` → 400,
  `Unauthorized` → 401, `NotFound` → 404, `Conflict` → 409, validation failures →
  400, anything unexpected → 500.
- **Health.** Both services expose `GET /health` (public; on the Core it stays
  at the root, not under `/api/v1`). The Core's check also opens a DB connection
  (`HealthController`); the BFF's also reports Core reachability (`coreReachable`).
- **Logging.** Both log method, path, status, and latency per request. The BFF
  redacts the `Authorization` header and any `password` field; the Core never
  logs secrets or tokens.
- **OpenAPI.** The Core serves Swagger UI at `/swagger-ui.html`; the BFF serves
  it at `/docs`.
- **Photo bytes.** Served only via the authed, tenant-scoped content endpoint
  (`GET /api/v1/sessions/{id}/photos/{photoId}/content` on the Core, relayed by
  the BFF). Uploads are limited to JPEG/PNG/WebP (checked by sniffing the file's
  leading bytes, not its declared type) and to 10 MB; an oversize upload returns
  `413`.
- **Request size limits (BFF).** The BFF caps JSON request bodies at 1 MiB and
  the events batch at 500 items (`bff/src/server.ts`, `bff/src/schemas.ts`), so
  oversized payloads are rejected before reaching the Core.
- **Paginated listing.** `GET /sessions` is paginated (default page size 50,
  hard cap 200) and batch-loads events for the page in one query — no per-session
  N+1 (`SessionService.list`).

## Known v1 limitations / deferred

These are intentional v1 scope cuts, tracked for later work:

- **No refresh-token rotation or revocation (W6).** Tokens are stateless and
  valid until they expire (access 1h, refresh 30d). There is no logout, no
  server-side denylist, and a refresh does not invalidate the old refresh token.
  A stolen, unexpired token stays usable until it expires. Rotation + revocation
  is planned for v2.
- **`LoginRequest.deviceType` is accepted but unused (N3).** The field is
  required and validated on both layers, but the Core does not yet store or act
  on it. It is kept for forward device-tracking (e.g. per-device sessions or
  push) so the contract does not have to change later.
