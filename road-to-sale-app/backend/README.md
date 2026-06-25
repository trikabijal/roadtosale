# Road to Sale — Core API (Java / Spring Boot)

The source of truth for the Road to Sale domain: dealerships, users, sessions,
session events, and trade-in photos. Multi-tenant (one hosted instance serves
many dealerships). The BFF is its only client; the mobile app never talks to it
directly.

- **Stack:** Java 17, Spring Boot 3.3.5 (Web, Security, Data JPA, Validation),
  Flyway, PostgreSQL 16, JWT (jjwt), springdoc OpenAPI.
- **Port:** 8090 (dev).
- **Envelope:** every JSON response (success and error) is
  `ApiResponse { status, message, data }`.

## Build / run / test

```bash
./build.sh        # mvn clean package (skips tests)
./run.sh          # mvn spring-boot:run -Dspring-boot.run.profiles=dev (needs Postgres)
./test.sh         # mvn test — uses zonky embedded Postgres, no Docker
```

`run.sh` (dev profile) needs a reachable Postgres; see `.env.example` for
`SPRING_DATASOURCE_*`. The test suite launches a real Postgres binary in-process
(zonky `embedded-postgres`), so no Docker daemon is required.

> **Apple Silicon note:** tests pull `embedded-postgres-binaries-darwin-arm64v8`.
> The Phase-1 placeholder versions were corrected to ship: `embedded-postgres`
> `2.2.2` (2.6.0 was never published), and `commons-lang3` is pinned to `3.18.0`
> (zonky 2.2.2 calls `SystemProperties.getUserName`).

## Endpoints

| Method & path | Auth | Notes |
|---|---|---|
| `GET /health` | public | 200 when the service + DB are reachable |
| `POST /auth/login` | public | `{username, password, deviceType}` → `{accessToken, refreshToken, user}` |
| `POST /auth/refresh` | public | `{refreshToken}` → `{accessToken, refreshToken}` |
| `GET /checksheet/{code}` | bearer | static NADA checksheet; unknown code → 404 |
| `POST /sessions` | bearer | `{type, checksheetCode, context?}` → `SessionDTO` (ACTIVE) |
| `GET /sessions` | bearer | caller's sessions, newest first |
| `GET /sessions/{id}` | bearer | one session incl. derived outcomes; not yours → 404 |
| `POST /sessions/{id}/submit` | bearer | `{transcript?}` → COMPLETED; already done → 409 |
| `POST /sessions/{id}/events` | bearer | batch append, idempotent by cueId → `{accepted}`; completed → 409 |
| `POST /sessions/{id}/photos` | bearer | multipart `{slot, file}` → `PhotoDTO` |
| `GET /sessions/{id}/photos` | bearer | photos for a session |
| `GET /files/**` | public | serves uploaded photo bytes (see below) |

Swagger UI at `/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs`.

## Auth

- BCrypt password hashes. Access token TTL 3600s, refresh 2592000s.
- Tokens carry `userId` and `dealershipId` claims. Refresh tokens carry
  `type=refresh` and cannot be used as access tokens.
- Public routes: `/health`, `/auth/**`, `/v3/api-docs/**`, `/swagger-ui/**`,
  `/files/**`. Everything else requires a valid access token (else 401).

## Multi-tenancy

`dealershipId` is read **only** from the access token, never from the request
body/query. Every query is scoped to it. A session/photo belonging to another
dealership returns **404** (not 403), so existence is never leaked.

## Outcome derivation

Outcomes are computed, never stored. For each `questionId` with events, the
highest-confidence event wins; `satisfied = confidence >= threshold`
(`roadtosale.outcome.confidence-threshold`, default 0.6). `progress.total` is the
number of questions in the session's checksheet; `progress.answered` is the count
of satisfied questions.

## Photo storage & file URLs

Photos are written via the `StorageService` interface (`LocalDiskStorage` impl)
under `roadtosale.storage.local-dir` (default `./data/photos`), namespaced by
session: `<sessionId>/<uuid>.<ext>`. The stored key is exposed as a retrievable
URL: `PhotoDTO.fileUrl = /files/<sessionId>/<uuid>.<ext>`, served by a public
resource handler. S3 is the likely later swap behind the same interface.

## Seed credentials (dev + E2E)

Seeded idempotently on startup under the `dev` profile (`DevSeedRunner`), and
available to tests via `SeedService.seed()`:

| Dealership | username | password | name | roles |
|---|---|---|---|---|
| Honda of Fremont | `rep1` | `password123` | Sam Rep | `SALESPERSON` |
| Honda of Oakland | `rep2` | `password123` | Riley Rep | `SALESPERSON` |

`rep1` and `rep2` are in different dealerships — use them to exercise the
cross-tenant 404 behavior.

### Example: login → create session (drive the Core directly)

```bash
# 1) login
curl -s localhost:8090/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"rep1","password":"password123","deviceType":"ios"}'
# → { "status":200, "message":"OK",
#     "data": { "accessToken":"<JWT>", "refreshToken":"<JWT>",
#               "user": { "id":"<uuid>", "username":"rep1", "name":"Sam Rep",
#                         "dealershipId":"<uuid>", "roles":["SALESPERSON"] } } }

# 2) create a session
curl -s localhost:8090/sessions -H "Authorization: Bearer <JWT>" \
  -H 'Content-Type: application/json' \
  -d '{"type":"LIVE","checksheetCode":"RTS_HONDA_V1",
       "context":{"customerName":"Jane Doe","vehicleOfInterest":"CR-V"}}'
# → { "status":200, "message":"OK",
#     "data": { "id":"<uuid>", "type":"LIVE", "status":"ACTIVE",
#               "checksheetCode":"RTS_HONDA_V1", "startedAt":"...",
#               "context":{"customerName":"Jane Doe","vehicleOfInterest":"CR-V"},
#               "progress":{"answered":0,"total":16}, "outcomes":[] } }

# 3) post a batch of events (idempotent by cueId)
curl -s localhost:8090/sessions/<id>/events -H "Authorization: Bearer <JWT>" \
  -H 'Content-Type: application/json' \
  -d '{"events":[{"cueId":"c1","questionId":"1","stepNo":1,
       "detectedAt":"2026-06-25T10:00:00Z","confidence":0.8,
       "transcriptSpan":"hi there","source":"feature"}]}'
# → { ..., "data": { "accepted": 1 } }
```

> Event `source` is lowercase (`feature` | `workflow`); photo `slot` is lowercase
> (`front_left`, `front_right`, `rear_left`, `rear_right`, `interior`, `odometer`,
> `vin`).
