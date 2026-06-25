# Core API — Architecture

The Core API is the **source of truth**: a Java 17 / Spring Boot 3 service that
owns all data, auth, multi-tenancy, and the static NADA checksheet. It is one of
the three tiers (BFF → Core → Postgres). Only the BFF calls it.

> This is a short, Core-specific overview. The full three-tier architecture, the
> two-facade isolation rule, and the key-decisions table are in the canonical
> system docs: [../../docs/backend/architecture.md](../../docs/backend/architecture.md).

## Stack

Java 17, Spring Boot 3 (Web, Security, Data JPA, Validation), Flyway, PostgreSQL
16, JWT (jjwt), springdoc OpenAPI. Built with Maven (`pom.xml`).

## Layered structure

```
controller/   HTTP endpoints (thin; delegate to services) → returns ApiResponse
service/      Business logic: AuthService, SessionService, PhotoService, ChecksheetService
domain/       JPA entities (Session, SessionEvent, Photo, User, Dealership) + enums
repository/   Spring Data repositories (tenant-scoped finders)
security/     JwtService, JwtAuthFilter, AuthenticatedUser, @CurrentUser resolver
storage/      StorageService interface + LocalDiskStorage
web/          ApiResponse envelope + GlobalExceptionHandler
config/       SecurityConfig, WebConfig
seed/         DevSeedRunner + SeedService (dev/test seed data)
```

## What the Core owns

- **Persistence** — the Postgres schema, created by one Flyway migration
  (`src/main/resources/db/migration/V1__init.sql`). Hibernate runs in `validate`
  mode, so it never mutates the schema. See
  [../../docs/backend/data-model.md](../../docs/backend/data-model.md).
- **Auth + token issuance** — `JwtService` signs access (1h) and refresh (30d)
  tokens carrying `userId` and `dealershipId`. Outside the `dev`/`test`
  profiles, the service **fails to start** if the JWT secret is blank, the dev
  default, or shorter than 32 bytes — so a deploy can never silently accept
  forged tokens signed with a weak key. There is no secret padding.
- **Multi-tenancy** — `dealershipId` is read only from the verified token
  (`AuthenticatedUser`, via the `@CurrentUser` resolver); a `dealershipId` in
  the request body is ignored. Every read/write is scoped by `dealershipId`
  (e.g. `SessionRepository.findByIdAndDealershipId`). A cross-tenant resource is
  reported as `404`, never `403`, so existence is not leaked.
- **The static checksheet** — a versioned JSON file
  (`src/main/resources/checksheets/rts_honda_v1.json`, code `RTS_HONDA_V1`),
  loaded and cached by `ChecksheetService`; an unknown code → `404`.
- **Derived outcomes** — there is no stored "answer" column. For each question,
  `SessionService.deriveOutcomes` takes the highest-confidence event and marks it
  satisfied when `confidence >= ` the configured threshold (default `0.6`,
  `roadtosale.outcome.confidence-threshold`).

## Domain notes

- **`session_events` is append-only.** Events are inserted with
  `INSERT ... ON CONFLICT (session_id, cue_id) DO NOTHING`, so re-posting the
  same `cueId` is a no-op. `confidence` is `numeric(4,3)` with a `0..1` CHECK;
  `step_no` has a `>= 1` CHECK.
- **`users.username` is globally unique.** It is the login identity (credentials
  carry no dealership), so it must be unambiguous across tenants.
- **Photos** are stored on local disk behind the `StorageService` interface
  (`LocalDiskStorage` writes `<sessionId>/<uuid>.<ext>`). Uploads are restricted
  to JPEG/PNG/WebP by sniffing the file's leading bytes (not the declared type),
  and capped at 10 MB. Bytes are served only through the authed, tenant-scoped
  content endpoint — there is **no** public `/files/**` handler (that was an
  IDOR and was removed; see `config/WebConfig.java`).

## Cross-cutting

- **Envelope + errors** — every response is `ApiResponse`;
  `GlobalExceptionHandler` maps exceptions to 400/401/404/409/413/500.
- **Health** — `GET /health` (public, root) opens a DB connection to prove
  reachability.
- **Logging** — request method/path/status/latency, never secrets or tokens.
- **OpenAPI** — springdoc serves `/swagger-ui.html` and `/v3/api-docs`.

## Known v1 limitations

Refresh tokens are **not** rotated or revoked (stateless, valid until expiry; no
logout/denylist — v2), and `LoginRequest.deviceType` is validated but unused
(kept for forward device-tracking). Details:
[../../docs/backend/architecture.md](../../docs/backend/architecture.md#known-v1-limitations--deferred).

## See also

- System architecture + isolation rule: [../../docs/backend/architecture.md](../../docs/backend/architecture.md)
- Internal API: [api.md](./api.md)
- Request walkthroughs: [flows.md](./flows.md)
- Test plan: [../../docs/backend/testing.md](../../docs/backend/testing.md)
- Build / run / test: [../README.md](../README.md)
</content>
