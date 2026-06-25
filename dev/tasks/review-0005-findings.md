# Review Findings Tracker — 0005 Road to Sale Backend

Source: 5-agent code review (rubric ~/.claude/workflows/code-review*.md + PRD→code→tests→docs traceability), 2026-06-25.
Status legend: ⬜ open · ✅ fixed · 🟡 deferred-with-rationale (v1-acceptable, tracked).

## BLOCKERS — security / correctness
- ⬜ B1 Core: hardcoded fallback JWT secret accepted; secret-padding masks weak secrets. → fail-fast on prod if default/short; remove padding.
- ⬜ B2 Core: photo upload no size cap; oversize → 500. → set multipart max-file-size; handle MaxUploadSizeExceededException → 413.
- ⬜ B3 Core: photo upload accepts any content-type. → MIME allow-list (jpeg/png/webp) + magic-byte sniff; reject → 400.
- ⬜ B4 Core: `/files/**` public + not tenant-scoped (photo-bytes IDOR). → remove public static handler; serve via authed tenant-scoped endpoint.
- ⬜ T1 Tests: req #23 (secrets-never-logged) untested. → add assertion-on-logs test.
- ⬜ T2 Tests: Core health test is false-confidence (static "ok"). → make it actually prove DB reachability.
- ⬜ T3 Tests: req #8 (body dealership_id ignored) untested. → add forged-dealershipId test.

## WARNINGS — should fix
- ⬜ W1 Core: GET /sessions unbounded + N+1 event load. → pagination + batch findBySessionIdIn.
- ⬜ W2 Core: confidence unbounded numeric, no 0..1 CHECK. → numeric(4,3) + CHECK.
- ⬜ W3 Core: step_no unvalidated. → @Min(1) + CHECK.
- ⬜ W5 Core: login lookup global (findByUsername) vs per-dealership unique → latent multi-tenant break. → make username globally unique; update PRD/docs.
- 🟡 W6 Core: refresh token no rotation/revocation. → v1-acceptable per reviewer; document limitation + track.
- ⬜ BFF1: requireAuth relies on implicit short-circuit. → explicit `return reply...`.
- ⬜ BFF2: photo relay buffers fully but comment claims "never buffered". → stream via undici, or correct comment.
- ⬜ BFF3: events array no maxItems / implicit bodyLimit. → add maxItems + explicit bodyLimit.
- ⬜ TR1 Trace: Core path deviation vs PRD §6.2 (no /api/v1, /checksheet singular). → add /api/v1 to Core resource controllers + /checksheets plural; update BFF client + docs.
- ⬜ TR2 Trace: backend/docs/{api,architecture,flows}.md missing (acceptance §11#8). → add module docs.

## TEST WARNINGS — missing negatives / weak assertions
- ⬜ TW1 expired access+refresh token paths untested. → add.
- ⬜ TW2 MOCK session type untested. → add.
- ⬜ TW3 list ordering "recent first" unproven (single row). → 2-session ordering test.
- ⬜ TW4 token claims (userId+dealershipId) not directly tested. → decode-claims test.
- ⬜ TW5 OpenAPI/Swagger untested both layers. → hit /v3/api-docs + BFF /docs.
- ⬜ TW6 photo path-traversal not tested. → negative test (../, bad filename).
- ⬜ TW7 E2E low-outcome assertion is conditional (silently skips). → assert presence then false.
- ⬜ TW8 Core idempotency asserts response only, not row count. → add Core row-count assertion.
- ⬜ TW9 testing.md is post-hoc prose, not a plan (no Facade Coverage Ledger). → rewrite as real plan mapping PRD→behavior→tier→test.

## NITS — fix cheap, drop rest
- ⬜ N1 Core: dev seed logs plaintext password. → remove from log.
- ⬜ N6 Core: requireOwnedSession duplicated in 2 services (security primitive). → extract shared helper.
- ⬜ BFF-N: unused @fastify/jwt dependency. → remove.
- ⬜ BFF-N: /health contract missing `coreReachable`. → add to openapi.yaml.
- 🟡 N3 Core: deviceType validated but unused. → drop field or keep; low priority (keep, documented).
- ✅ Docs: reviewer found docs accurate (zero blockers); minor NITs folded into TR2/doc refresh.
