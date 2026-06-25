# Review Findings Tracker — 0005 Road to Sale Backend

Source: 5-agent code review (rubric ~/.claude/workflows/code-review*.md + PRD→code→tests→docs traceability), 2026-06-25.
Status legend: ⬜ open · ✅ fixed · 🟡 deferred-with-rationale (v1-acceptable, tracked).

> **Independent re-review verdict: GO (2026-06-25).** A separate verifier agent
> confirmed — by reading the code, not this tracker — that every B/W/T/TW finding
> is genuinely closed with real test assertions, no regressions, and the 3 🟡
> deferrals are legitimate. Only 3 LOW comment-phrasing nits remain (non-blocking).
>
> Closed 2026-06-25. All code findings fixed and verified green (Core 37 tests,
> BFF 18, headless E2E 15 checks). Doc/plan/PRD findings closed in the same pass:
> docs/backend/* refreshed to reality, `backend/docs/{api,architecture,flows}.md`
> added (TR2), `docs/backend/testing.md` rewritten as a real test plan with a
> Facade Coverage Ledger (TW9), the PRD reconciled with a "Post-review decisions"
> section, and deferrals (W6, N3) documented honestly.

## BLOCKERS — security / correctness
- ✅ B1 Core: hardcoded fallback JWT secret accepted; secret-padding masks weak secrets. → fixed: `JwtService` fails fast in prod on blank/dev-default/<32-byte secret; padding removed.
- ✅ B2 Core: photo upload no size cap; oversize → 500. → fixed: `spring.servlet.multipart.max-file-size` 10MB; `MaxUploadSizeExceededException` → 413 via `GlobalExceptionHandler`.
- ✅ B3 Core: photo upload accepts any content-type. → fixed: MIME allow-list (jpeg/png/webp) by magic-byte sniff; non-image → 400.
- ✅ B4 Core: `/files/**` public + not tenant-scoped (photo-bytes IDOR). → fixed: public static handler removed; bytes served via authed tenant-scoped `GET /api/v1/sessions/{id}/photos/{photoId}/content` (401 no token, 404 cross-tenant).
- ✅ T1 Tests: req #23 (secrets-never-logged) untested. → fixed: `SecurityAndTokenTest.noTokensOrPasswordsAppearInLogs`.
- ✅ T2 Tests: Core health test is false-confidence (static "ok"). → fixed: `ChecksheetAndHealthTest.healthIsPublicAndProvesDbReachability` opens a DB connection.
- ✅ T3 Tests: req #8 (body dealership_id ignored) untested. → fixed: `SecurityAndTokenTest.forgedDealershipIdInBodyIsIgnored`.

## WARNINGS — should fix
- ✅ W1 Core: GET /sessions unbounded + N+1 event load. → fixed: paginated (page+size or limit+offset; default 50, cap 200) + `findBySessionIdIn` batch load.
- ✅ W2 Core: confidence unbounded numeric, no 0..1 CHECK. → fixed: `numeric(4,3)` + `CHECK (0..1)`.
- ✅ W3 Core: step_no unvalidated. → fixed: `@Min(1)` + `CHECK (step_no >= 1)`.
- ✅ W5 Core: login lookup global (findByUsername) vs per-dealership unique → latent multi-tenant break. → fixed: `username` globally unique (`uq_users_username`); PRD/docs updated.
- 🟡 W6 Core: refresh token no rotation/revocation. → deferred to v2 (v1-acceptable). Documented in PRD §8, `docs/backend/architecture.md` (Known v1 limitations), and the testing.md deferrals.
- ✅ BFF1: requireAuth relies on implicit short-circuit. → fixed: explicit `return reply...` in `requireAuth`.
- ✅ BFF2: photo relay buffers fully but comment claims "never buffered". → fixed: content relay streams via undici; upload comment corrected.
- ✅ BFF3: events array no maxItems / implicit bodyLimit. → fixed: `maxItems: 500` on the events schema + explicit 1 MiB `bodyLimit` in `server.ts`.
- ✅ TR1 Trace: Core path deviation vs PRD §6.2 (no /api/v1, /checksheet singular). → fixed: Core under `/api/v1` + plural `/checksheets`; `/health` at root; BFF client + docs + PRD updated.
- ✅ TR2 Trace: backend/docs/{api,architecture,flows}.md missing (acceptance §11#8). → fixed: added concise Core module docs cross-linking the canonical `docs/backend/*`.

## TEST WARNINGS — missing negatives / weak assertions
- ✅ TW1 expired access+refresh token paths untested. → fixed: `SecurityAndTokenTest` (`expiredAccessTokenIsRejectedOnProtectedRoute`, `expiredRefreshTokenIsRejectedOnRefresh`).
- ✅ TW2 MOCK session type untested. → fixed: `SessionLifecycleTest.createMockSessionStartsActive`.
- ✅ TW3 list ordering "recent first" unproven (single row). → fixed: `SessionLifecycleTest.listReturnsMostRecentFirst` (2 sessions).
- ✅ TW4 token claims (userId+dealershipId) not directly tested. → fixed: `SecurityAndTokenTest.accessTokenCarriesUserIdAndDealershipIdClaims`.
- ✅ TW5 OpenAPI/Swagger untested both layers. → fixed: Core `OpenApiTest` hits `/v3/api-docs`; BFF health/docs exercised (`/docs` served, health test asserts envelope).
- 🟡 TW6 photo path-traversal not tested. → addressed **structurally**: the content endpoint takes a `photoId` UUID (not a filename) and storage writes server-generated `<uuid>` names, so no client-supplied path reaches the filesystem. No explicit `../` negative test (no reachable input). Noted in testing.md deferrals.
- ✅ TW7 E2E low-outcome assertion is conditional (silently skips). → fixed: E2E step i asserts the below-threshold outcome is present and `satisfied: false`.
- ✅ TW8 Core idempotency asserts response only, not row count. → fixed: `eventsAreIdempotentByCueId` asserts exactly one persisted row for the cueId.
- ✅ TW9 testing.md is post-hoc prose, not a plan (no Facade Coverage Ledger). → fixed: rewritten as a real plan with a Facade Coverage Ledger mapping every PRD §7 req + §11 AC to a behavior, tier, and real test.

## NITS — fix cheap, drop rest
- ✅ N1 Core: dev seed logs plaintext password. → fixed: plaintext password no longer logged by the seed.
- ✅ N6 Core: requireOwnedSession duplicated in 2 services (security primitive). → fixed: extracted to a shared helper.
- ✅ BFF-N: unused @fastify/jwt dependency. → fixed: dependency removed.
- ✅ BFF-N: /health contract missing `coreReachable`. → fixed: `coreReachable` in `openapi.yaml` and the BFF health response.
- 🟡 N3 Core: deviceType validated but unused. → kept (documented): validated on both layers, unused for now; retained for forward device-tracking. Noted in PRD §8 and `docs/backend/architecture.md`.
- ✅ Docs: reviewer found docs accurate (zero blockers); minor NITs folded into TR2/doc refresh — docs/backend/* refreshed to match the post-review code.
</content>
