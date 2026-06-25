# Test Plan

This is the test plan for the Road to Sale backend stack (BFF + Core + Postgres
+ QA), written the way `~/.claude/workflows/create-test-plan.md` describes: a
**Facade Coverage Ledger** that maps every PRD requirement and acceptance
criterion to a behavior, a tier, and the real test that covers it. It is the
contract for what gets tested — not a description written after the fact.

See [README.md](./README.md) for how to run each layer, [api.md](./api.md) for
the facade shapes, and [data-model.md](./data-model.md) for the schema.

## How to read this plan

- **Facades.** Two public facades plus the running stack:
  1. **BFF** (app-facing) — the 11 HTTP endpoints in [api.md](./api.md). The
     mobile app talks only to this.
  2. **Core** (internal) — the `/api/v1` endpoints the BFF calls
     (`GET /health` stays at the root).
  3. **Full stack** — BFF → Core → Postgres, exercised through the BFF over HTTP
     by the QA harness, which then reads Postgres directly to confirm persistence.
- **PRD references** are to `dev/tasks/0005-prd-road-to-sale-backend.md`: §7
  functional requirements (#1–#25) and §11 acceptance criteria (AC1–AC8).
- **Tiers** (org standard):

| Tier | What it proves | Environment | When |
|---|---|---|---|
| **Tier 1 — Core slice** | The Core's controllers → services → DB behave correctly. | `@SpringBootTest` + MockMvc against a zonky embedded Postgres (no Docker). | Every commit |
| **Tier 1 — BFF unit** | The BFF's edge behavior (validation, auth presence, relay, multipart, 502). | vitest with the Core HTTP client mocked; nothing running. | Every commit |
| **Tier 2 — E2E full-stack** | The whole stack works through the public BFF facade and data persists. | Embedded Postgres + Core jar + BFF, all in-process; black-box over HTTP. | Before a PR / release |

There is no Tier 3 suite for the backend yet (no real-network external
dependencies to validate).

## Test files

| File | Layer / tier | Count |
|---|---|---|
| `backend/src/test/java/com/auditpro/roadtosale/AuthTest.java` | Core slice (T1) | 8 |
| `backend/src/test/java/com/auditpro/roadtosale/SessionLifecycleTest.java` | Core slice (T1) | 10 |
| `backend/src/test/java/com/auditpro/roadtosale/TenantIsolationTest.java` | Core slice (T1) | 4 |
| `backend/src/test/java/com/auditpro/roadtosale/PhotoTest.java` | Core slice (T1) | 6 |
| `backend/src/test/java/com/auditpro/roadtosale/ChecksheetAndHealthTest.java` | Core slice (T1) | 3 |
| `backend/src/test/java/com/auditpro/roadtosale/SecurityAndTokenTest.java` | Core slice (T1) | 5 |
| `backend/src/test/java/com/auditpro/roadtosale/OpenApiTest.java` | Core slice (T1) | 1 |
| `bff/tests/bff.test.ts` | BFF unit (T1) | 18 |
| `qa/tests/e2e.test.ts` | E2E full-stack (T2) | 15 |

**Core total: 37. BFF total: 18. E2E total: 15.**

## Facade Coverage Ledger

Every row references a public facade element. The PRD column ties each behavior
back to a requirement (`#n`) or acceptance criterion (`ACn`). The "Test (file)"
column names the test that covers it.

| Facade / Endpoint | Behavior | Inputs / State | Expected | Tier | PRD | Test (file) |
|---|---|---|---|---|---|---|
| `POST /auth/login` | Login returns tokens + user | Valid creds | 200, `{accessToken, refreshToken, user}` | T1 Core | #1 | `loginReturnsTokensAndUser` (AuthTest) |
| `POST /auth/login` | Wrong password rejected | Valid user, bad password | 401 | T1 Core | #1 | `loginWithWrongPasswordReturns401` (AuthTest) |
| `POST /auth/login` | Missing field rejected | No password | 400 | T1 Core | #21 | `loginMissingFieldReturns400` (AuthTest) |
| `POST /auth/login` (BFF) | Missing field rejected at the edge | No password | 400, Core not called | T1 BFF | #21 | "(a) login with missing password → 400" (bff) |
| `POST /auth/login` (BFF) | Forwards + relays envelope/status | Valid body | Core's exact envelope + status | T1 BFF | #25 | "(c) login forwards and relays" (bff) |
| Password storage | Hash only; never logged | Login + authed request | Password never in logs | T1 Core | #2, #23 | `noTokensOrPasswordsAppearInLogs` (SecurityAndTokenTest) |
| Access token claims | Carries `userId` + `dealershipId` (+ `type=access`) | Decoded token | Claims match user | T1 Core | #5 | `accessTokenCarriesUserIdAndDealershipIdClaims` (SecurityAndTokenTest) |
| `POST /auth/refresh` | New pair from valid refresh | Valid refresh token | 200, new access + refresh | T1 Core | #3 | `refreshIssuesNewPair` (AuthTest) |
| `POST /auth/refresh` | Garbage refresh rejected | Junk token | 401 | T1 Core | #3 | `refreshWithGarbageReturns401` (AuthTest) |
| `POST /auth/refresh` | Access token can't be used as refresh | Access token in refresh slot | 401 | T1 Core | #3 | `accessTokenCannotBeUsedAsRefreshToken` (AuthTest) |
| `POST /auth/refresh` | Expired refresh rejected | Forged expired refresh | 401 | T1 Core | #3 | `expiredRefreshTokenIsRejectedOnRefresh` (SecurityAndTokenTest) |
| Protected route | No token rejected | Authed route, no header | 401 | T1 Core | #4 | `protectedRouteWithoutTokenReturns401` (AuthTest) |
| Protected route | Bad token rejected | Garbage Bearer | 401 | T1 Core | #4 | `protectedRouteWithBadTokenReturns401` (AuthTest) |
| Protected route | Expired access token rejected | Forged expired access | 401 | T1 Core | #4 | `expiredAccessTokenIsRejectedOnProtectedRoute` (SecurityAndTokenTest) |
| Protected route (BFF) | No `Authorization` rejected at the edge | `GET /sessions`, no header | 401, Core not called | T1 BFF | #4 | "(b) GET /sessions without auth → 401" (bff) |
| Protected route (BFF) | Non-Bearer header rejected | Non-Bearer `Authorization` | 401, Core not called | T1 BFF | #4 | "(b) non-Bearer → 401" (bff) |
| Tenancy | Cross-tenant read blocked | rep1 reads rep2's session | 404 | T1 Core | #6, #7 | `rep1CannotReadRep2Session` (TenantIsolationTest) |
| Tenancy | Cross-tenant submit blocked | rep1 submits rep2's session | 404 | T1 Core | #7 | `rep1CannotSubmitRep2Session` (TenantIsolationTest) |
| Tenancy | Cross-tenant events blocked | rep1 posts events to rep2's session | 404 | T1 Core | #7 | `rep1CannotPostEventsToRep2Session` (TenantIsolationTest) |
| Tenancy | List scoped to caller | rep1 lists | Only rep1's sessions | T1 Core | #6 | `listIsScopedToCallerOnly` (TenantIsolationTest) |
| Tenancy | Body `dealershipId` ignored | Forged `dealershipId` in create body | Session owned by token's tenant; forged value not used | T1 Core | #8 | `forgedDealershipIdInBodyIsIgnored` (SecurityAndTokenTest) |
| `GET /checksheet/:code` | Loads by code | Known code | 200, `ChecksheetDTO` | T1 Core | #9 | `checksheetLoadsByCode` (ChecksheetAndHealthTest) |
| `GET /checksheet/:code` | Unknown code | Bad code | 404 | T1 Core | #10 | `unknownChecksheetReturns404` (ChecksheetAndHealthTest) |
| `GET /checksheet/:code` (BFF) | Auth required | No token | 401 | T1 BFF | #4 | "(b) GET /checksheet/:code without auth → 401" (bff) |
| `POST /sessions` | Create LIVE session | Valid body | 200, status `ACTIVE` | T1 Core | #11 | `createGetListSession` (SessionLifecycleTest) |
| `POST /sessions` | Create MOCK session | `type: MOCK` | 200, status `ACTIVE` | T1 Core | #11 | `createMockSessionStartsActive` (SessionLifecycleTest) |
| `POST /sessions` | Unknown checksheet | Bad `checksheetCode` | 404 | T1 Core | #10 | `createSessionWithUnknownChecksheetReturns404` (SessionLifecycleTest) |
| `POST /sessions` (BFF) | Bad enum rejected at the edge | Bad `type` | 400, Core not called | T1 BFF | #21 | "(a) create session with bad enum → 400" (bff) |
| `GET /sessions` | List most recent first | 2 sessions | Newest first | T1 Core | #12 | `listReturnsMostRecentFirst` (SessionLifecycleTest) |
| `GET /sessions` | Paginated, batched | List | One bounded page, events batch-loaded | T1 Core | #12 | `listReturnsMostRecentFirst` + `createGetListSession` (SessionLifecycleTest) |
| `GET /sessions/:id` | Single session w/ outcomes | Known id | 200, `SessionDTO` w/ `outcomes` | T1 Core | #13 | `createGetListSession` (SessionLifecycleTest) |
| `GET /sessions/:id` | Unknown id | Bad id | 404 | T1 Core | #7 | `getNonexistentSessionReturns404` (SessionLifecycleTest) |
| `POST /sessions/:id/submit` | Complete a session | Active session | 200, `COMPLETED`, `endedAt` set | T1 Core | #14 | `submitCompletesAndSecondSubmitConflicts` (SessionLifecycleTest) |
| `POST /sessions/:id/submit` | Double submit | Already completed | 409 | T1 Core | #14 | `submitCompletesAndSecondSubmitConflicts` (SessionLifecycleTest) |
| `POST /sessions/:id/submit` (BFF) | Relays Core's non-200 | Core returns 409 | 409 relayed verbatim | T1 BFF | #25 | "(c) relays Core's non-200" (bff) |
| `POST /sessions/:id/events` | Accept batch | Active session, batch | 200, `accepted` = new count | T1 Core | #15 | `createGetListSession` / events tests (SessionLifecycleTest) |
| `POST /sessions/:id/events` | Idempotent by cueId (rows) | Re-post same cueId | `accepted: 0`, exactly one row persists | T1 Core | #16 | `eventsAreIdempotentByCueId` (SessionLifecycleTest) |
| `POST /sessions/:id/events` | Events on completed session | Completed session | 409 | T1 Core | #17 | `eventsOnCompletedSessionReturn409` (SessionLifecycleTest) |
| `POST /sessions/:id/events` | Outcome = highest confidence ≥ threshold | Two events, 0.4 then 0.8 | Highest (0.8) wins, satisfied | T1 Core | #18 | `outcomeUsesHighestConfidenceAndThreshold` (SessionLifecycleTest) |
| `POST /sessions/:id/events` | Below threshold not satisfied | Event < 0.6 | `satisfied: false` | T1 Core | #18 | `outcomeBelowThresholdIsNotSatisfied` (SessionLifecycleTest) |
| `POST /sessions/:id/events` (BFF) | Confidence out of range | `confidence > 1` | 400 | T1 BFF | #21 | "(a) confidence out of range → 400" (bff) |
| `POST /sessions/:id/events` (BFF) | Empty batch | `events: []` | 400 | T1 BFF | #21 | "(a) empty event batch → 400" (bff) |
| `POST /sessions/:id/events` (BFF) | Over maxItems cap | > 500 events | 400, Core not called | T1 BFF | #21 | "(a) over the maxItems cap (500) → 400" (bff) |
| `POST /sessions/:id/photos` | Upload + return DTO | Valid image | 200, `PhotoDTO` w/ content `fileUrl` | T1 Core | #19 | `uploadSetsBffRelativeFileUrlAndContentEndpointServesBytes`, `pngUploadIsAccepted` (PhotoTest) |
| `POST /sessions/:id/photos` | Non-image rejected (magic-byte sniff) | Text bytes, spoofed `image/png` header | 400 | T1 Core | #19, #21 | `nonImageWithSpoofedImageHeaderIsRejected400` (PhotoTest) |
| `POST /sessions/:id/photos` | Oversize rejected | File > 10 MB | 413 | T1 Core | #19 | `oversizeUploadReturns413` (PhotoTest) |
| `POST /sessions/:id/photos` | Cross-tenant upload blocked | rep1 uploads to rep2's session | 404 | T1 Core | #7 | `uploadToOtherTenantSessionReturns404` (PhotoTest) |
| `POST /sessions/:id/photos` (BFF) | Forwards file + slot | Valid multipart | Relays Core's `PhotoDTO` | T1 BFF | #19 | "(d) uploads file + slot" (bff) |
| `POST /sessions/:id/photos` (BFF) | Invalid slot rejected | Bad `slot` | 400, Core not called | T1 BFF | #21 | "(d) rejects an invalid slot" (bff) |
| `GET /sessions/:id/photos` | List photos | Session w/ photos | 200, `PhotoDTO[]` | T1 Core | #20 | `uploadSetsBffRelativeFileUrlAndContentEndpointServesBytes` (PhotoTest) |
| `GET /sessions/:id/photos/:photoId/content` | Serve bytes (authed) | Valid token + own photo | 200, image bytes, correct `Content-Type` | T1 Core | #19 | `uploadSetsBffRelativeFileUrlAndContentEndpointServesBytes` (PhotoTest) |
| `GET /sessions/:id/photos/:photoId/content` | No token | Missing Bearer | 401 | T1 Core | #4 | `uploadSetsBffRelativeFileUrlAndContentEndpointServesBytes` (PhotoTest) |
| `GET /sessions/:id/photos/:photoId/content` | Cross-tenant blocked (IDOR) | rep2 reads rep1's photo | 404 | T1 Core | #7 | `contentEndpointForOtherTenantReturns404` (PhotoTest) |
| `GET /sessions/:id/photos/:photoId/content` (BFF) | No token at the edge | Missing Bearer | 401, Core not called | T1 BFF | #4 | "(f) missing Bearer → 401" (bff) |
| `GET /sessions/:id/photos/:photoId/content` (BFF) | Relays bytes + type | Core returns bytes | 200, bytes + `Content-Type` relayed | T1 BFF | #25 | "(f) happy path relays Core bytes" (bff) |
| `GET /sessions/:id/photos/:photoId/content` (BFF) | Relays Core 404 | Core returns 404 | 404 relayed | T1 BFF | #7 | "(f) Core 404 → relayed 404" (bff) |
| `GET /health` (Core) | Public, proves DB reachable | Service up | 200, DB connection opened | T1 Core | #22 | `healthIsPublicAndProvesDbReachability` (ChecksheetAndHealthTest) |
| `GET /health` (BFF) | Public, reports Core reachability | Service up | 200, `{ status, coreReachable }` | T1 BFF | #22 | "health returns ok and reports Core reachability" (bff) |
| OpenAPI (Core) | Docs present, list documented paths | `/v3/api-docs` | 200, documented paths present | T1 Core | #24 | `apiDocsArePresentAndIncludeDocumentedPaths` (OpenApiTest) |
| Core unreachable (BFF) | Maps to 502 | `CoreUnreachableError` | 502 `ApiResponse` | T1 BFF | #25 | "(e) maps CoreUnreachableError to 502" (bff) |
| Full lifecycle | Login → create → events → photo → submit → read | Full stack over BFF | Correct derived outcomes; data persists | T2 E2E | AC2 | `e2e.test.ts` steps a–i + DB checks |
| Idempotency (E2E) | Re-post batch | Same batch twice | `accepted: 0`; row count == distinct cueIds | T2 E2E | AC4 | `e2e.test.ts` f + "DB: session_events" |
| Tenant isolation (E2E) | rep2 reads rep1's session | Cross-tenant | 404 | T2 E2E | AC3 | `e2e.test.ts` k |
| Photo-content authz (E2E) | No token / cross-tenant | Missing token; rep2 reads rep1's photo | 401; 404 | T2 E2E | AC3, #4 | `e2e.test.ts` l |
| Auth (E2E) | Missing/expired tokens rejected; refresh works | Protected routes | Rejected; refresh succeeds | T2 E2E (+ T1 Core) | AC5 | `e2e.test.ts` + AuthTest / SecurityAndTokenTest |
| Health + OpenAPI (E2E) | Both expose health + docs | Stack up | 200 | T2 E2E (+ T1) | AC6 | `e2e.test.ts` a/b + OpenApiTest |
| Local stack runs | Stack boots from documented commands, no external services | Embedded PG + Core jar + BFF | All checks pass | T2 E2E | AC1 | `qa/tests/harness.ts` (`startStack`) |
| Tier-1 speed | Tier-1 runs fast and passes before commit | Core + BFF unit | Pass | T1 | AC7 | Core slice + BFF unit suites |

### Acceptance-criteria coverage (§11)

| AC | Criterion | Covered by |
|---|---|---|
| AC1 | Stack runs locally, no external services | E2E harness (`startStack`) boots embedded PG + Core + BFF |
| AC2 | Full session lifecycle through the BFF | `e2e.test.ts` steps a–i |
| AC3 | Tenant isolation (404 cross-tenant) | `e2e.test.ts` k + l; Core `TenantIsolationTest`, `PhotoTest` |
| AC4 | Idempotency (no duplicate events) | `e2e.test.ts` f + DB row-count; Core `eventsAreIdempotentByCueId` |
| AC5 | Auth: reject missing/expired tokens; refresh works | Core `AuthTest`, `SecurityAndTokenTest`; E2E auth steps |
| AC6 | Both services expose `/health` + OpenAPI | Core `ChecksheetAndHealthTest`, `OpenApiTest`; BFF health test; `/docs` served |
| AC7 | Tier-1 fast + green before commit; suite green before merge | Core slice + BFF unit suites (Tier 1) |
| AC8 | Each deployable has `api.md`, `architecture.md`, `flows.md` + README | `backend/docs/`, `bff/docs/`, this `docs/backend/` set + `backend/README.md`, `bff/README.md` |

## New negative / hardening tests added in the post-review round

These close the test gaps the review flagged:

- **Expired access + refresh tokens** — `SecurityAndTokenTest` forges expired
  tokens and asserts 401 on a protected route and on refresh.
- **MOCK session type** — `createMockSessionStartsActive` proves a MOCK session
  is created and starts `ACTIVE`.
- **List ordering** — `listReturnsMostRecentFirst` uses two sessions to prove
  "most recent first" (a single-row list could not).
- **Token claims** — `accessTokenCarriesUserIdAndDealershipIdClaims` decodes the
  token and asserts `userId`, `dealershipId`, and `type=access`.
- **OpenAPI** — `OpenApiTest` hits `/v3/api-docs` and asserts documented paths
  are present.
- **MIME allow-list / spoofed type** — `nonImageWithSpoofedImageHeaderIsRejected400`
  sends text bytes with a lying `image/png` header; the magic-byte sniff rejects
  it with 400.
- **Oversize upload** — `oversizeUploadReturns413` proves the handler maps the
  container's max-upload exception to 413.
- **Secrets not logged** — `noTokensOrPasswordsAppearInLogs` captures all log
  output and asserts the access token, refresh token, and password never appear.
- **Body `dealershipId` ignored** — `forgedDealershipIdInBodyIsIgnored` posts a
  forged `dealershipId` and proves the token's tenant wins.
- **Idempotency row count** — `eventsAreIdempotentByCueId` asserts not just the
  `accepted: 0` response but that exactly one row persists for the cueId.
- **Photo-content 401/404** — `PhotoTest` (`contentEndpointForOtherTenantReturns404`,
  and the no-token assertion inside the upload/serve test) plus the BFF
  `(f)` content-relay tests and E2E step l cover the IDOR fix.

## Test constraints

- **Isolation.** Core slice tests refresh the embedded Postgres after each
  method (`@AutoConfigureEmbeddedDatabase`) and re-seed the two standard
  dealerships/users, so tests do not share rows. The E2E harness puts all
  transient data (the PG data dir, the Core storage dir, child logs) under
  `qa/.tmp/` and removes it on teardown. BFF unit tests mock the Core client, so
  they touch no real I/O.
- **Black-box (E2E).** The QA harness drives the stack only through the BFF over
  HTTP. It imports no BFF or Core source. To prove data persisted, it connects
  to the embedded Postgres directly and queries the tables.
- **No network / no Docker.** Both the Core slice tests and the E2E use
  `embedded-postgres` (a real Postgres binary launched in-process). No Docker
  daemon is needed.
- **Determinism.** Confidence threshold is the configured default (0.6); the
  seed is fixed; no sleeps or timing assumptions.

## Deferred / not covered

| Item | Reason | Tracking |
|---|---|---|
| Photo filename path-traversal (`../`) negative test (TW6) | Addressed **structurally**, so there is no reachable input to test: the content endpoint takes a `photoId` **UUID** path param (not a filename), and `LocalDiskStorage` writes server-generated `<sessionId>/<uuid>.<ext>` names — the client never supplies a path. An explicit `../` negative test is not added because no public input maps to a filename. | Re-add if storage ever accepts client-supplied names. |
| Refresh-token rotation / revocation tests (W6) | Not implemented in v1 — tokens are stateless and valid until expiry (no logout/denylist). | v2 (see [architecture.md](./architecture.md#known-v1-limitations--deferred)) |
| `deviceType` behavior (N3) | Field is validated but unused; there is no behavior to test yet. | When device-tracking lands |
| Tier 3 (real external services) | The backend has no real-network external dependency to validate. | n/a |
| 413 through a real servlet container | MockMvc does not run Tomcat's multipart size parser, so `oversizeUploadReturns413` asserts the `GlobalExceptionHandler` mapping of `MaxUploadSizeExceededException` → 413 directly. The end-to-end size cap relies on `spring.servlet.multipart.max-file-size` (10 MB). | Covered enough for v1; full container test deferred. |

## CI mapping

- **Before every commit (Tier 1):** `backend/test.sh` (37 Core slice tests) and
  `bff/test.sh` (18 BFF unit tests). Target: fast, no services.
- **Before a PR / release (Tier 2):** `qa/test.sh` (15 full-stack E2E checks).
  The harness rebuilds the Core jar when sources are newer than the jar, then
  boots the whole stack in-process.
</content>
</invoke>
