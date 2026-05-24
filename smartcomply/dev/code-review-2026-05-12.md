# Adversarial code review — feat/improvement-campaigns @ 0385c62
Date: 2026-05-12
Reviewer: Claude (self-review for Bijal)
Scope: 41 commits, ~79k LOC change, V1.27 → V1.30 + collapse + tests.

I went deep on migration backfill, the audit-report authorization rewrite,
the BI scope CTE, status enum drift, the listener async path, and the
private endpoints. Below are the findings I can defend with a file:line and
an attack/scenario. Italicised confidence reflects how easy this is to
reproduce vs. how much I'm guessing about call-graph context.

## TL;DR — top issues by severity

| # | Severity | Category | Title | File:line |
|---|---|---|---|---|
| 1 | CRITICAL | Privilege escalation | `createOrUpdate` blindly writes caller-supplied `status` — operator can set `APPROVED` and bypass the entire validator/approver pipeline | `service/UserChecksheetServiceImpl.java:428` |
| 2 | CRITICAL | Authorization gap | `createAudit` and `addAuditAssignments` have NO auth gate — any authenticated user can spawn audit campaigns + attach assignments to arbitrary audits | `service/AuditServiceImpl.java:91-187`, `controller/AuditController.java:24-44` |
| 3 | CRITICAL | Schema drift (silent data loss) | `UserChecksheetDAO.getDeclinedUserChecksheets` queries `status IN ('INVALIDATED','NOT_APPROVED')` — never matches post-V1.28 (CHECK forbids them; DECLINED is the only stored value). Returns empty list to every mobile caller. Also SQL-injects `userId`. | `DAO/UserChecksheetDAO.java:109` |
| 4 | HIGH | Schema drift | `UserChecksheetDAO.getRespectedUserChecksheet` filters out `'INVALIDATED','NOT_APPROVED'` and accepts caller-supplied `status` via string concat — both legacy literals (no rows match) and SQL injection vector | `DAO/UserChecksheetDAO.java:46,56` |
| 5 | HIGH | Schema drift | `DashboardServiceImpl` references `"NOT_APPROVED"` / `"INVALIDATED"` in `Set.of(...)`-based status comparisons (post-V1.28 status is `DECLINED`) → "In Progress" classification is wrong for declined inspections | `service/DashboardServiceImpl.java:353,383` |
| 6 | HIGH | Authz IDOR | `assertUserChecksheetVisible` (audit-report read) and `getImprovementOverlay`'s inline visibility check **don't enforce kind=AUDIT for the report stack** — an attacker can pass an INTERVENTION-kind id to `getUserChecksheetWithAnswers` and read its content as if it were an audit | `service/UserChecksheetServiceImpl.java:2602-2634`, `service/AuditServiceImpl.java:556-570` |
| 7 | HIGH | Data integrity | Operators can re-edit `SUBMITTED` inspections via createOrUpdate UPDATE branch (no transition guard) → silently moves a validator-owned record back to operator's pocket, losing the "submitted" snapshot | `service/UserChecksheetServiceImpl.java:329-472` |
| 8 | HIGH | Async correctness | Listener's instantiation is `@Async @TransactionalEventListener(AFTER_COMMIT)` but: no `TaskExecutor` bean → SimpleAsyncTaskExecutor (no pool, no backpressure); only `CustomException` is caught — `DataIntegrityViolationException` (unique-violation race) is silently lost; no DLQ / retry; partial-commit risk | `event/InterventionInstantiationListener.java:30-47` |
| 9 | HIGH | Multi-tenant correctness | `addAuditAssignments` cannot set `dealerPrincipalUserId` — newly created audit campaigns on a non-Kia tenant produce inspections with NULL DP. The ack flow throws 403 for every plan derived from them. | `service/AuditServiceImpl.java:166-178`, `DTO/AuditAssignmentCreateDTO.java` |
| 10 | HIGH | Performance | Every BI dashboard load runs the same scopeCte 6+ times (band, what's-failing, top-failing, level table, red dealers, plus N×insights). The CTE contains a LATERAL DISTINCT ON over `user_checksheet_answers` — no caching, no result reuse, no covering indexes on the join keys it needs | `service/AuditServiceImpl.java:1023-1444` |
| 11 | MEDIUM | Type drift | `Inspection.submissionVersion` is `Byte` (signed 8-bit) but column is `SMALLINT` (16-bit). Silent truncation > 127. Annotated as known but not fixed. | `entity/Inspection.java:147-157` |
| 12 | MEDIUM | Tests not parallel-safe | `InterventionLifecycleE2ETest` and `InterventionMultiE2ETest` both use `@DirtiesContext(AFTER_CLASS)`. Each test class destroys the Spring context → no parallel context reuse, "60s suite" claim is contradicted by the annotation | `test/.../InterventionLifecycleE2ETest.java:57-58`, `InterventionMultiE2ETest.java:35-36` |
| 13 | MEDIUM | Tests not self-sufficient | `InterventionMultiE2ETest.setup` reads `audits WHERE deleted_at IS NULL ORDER BY id LIMIT 1` — depends on demo seed; fails on a clean DB. Contradicts the "owns its fixtures" methodology in the test plan. | `test/.../InterventionMultiE2ETest.java:69-79` |
| 14 | MEDIUM | Error handling | `getUserChecksheetWithAnswers` catches `Exception` and converts to "Error fetching data" 500 → loses cause + context; failures are invisible past the log | `service/UserChecksheetServiceImpl.java:1315-1318` |
| 15 | MEDIUM | Code smell | `@Data` on `Inspection` (Lombok) auto-generates `equals`/`hashCode` across ALL fields including LAZY associations → calling `equals` triggers a cascade of LazyInitializationException-risk loads; standard JPA anti-pattern | `entity/Inspection.java:65-74` |
| 16 | LOW | Dead code | `cleanupAuditCascade`, `teardown` issue `DELETE FROM inspections WHERE intervention_id = ?` TWICE — second is a no-op | `test/.../InterventionLifecycleE2ETest.java:151-152,177-178,205-206` |
| 17 | LOW | Doc drift | `CLAUDE.md` and `audit-flow.md` still describe `audit_assignments` table and mobile sending `auditAssignmentId` — but post-V1.28 it's an Inspection with `assignmentKind` discriminator. The legacy field is preserved in code for FE compat (good) but docs read as if pre-V1.28. | `CLAUDE.md`, `docs/audit-flow.md` |

---

## Critical findings (ship-blockers)

### F1. `createOrUpdate` accepts caller-supplied status — privilege escalation

- **Where:** `src/main/java/com/checkSheet/service/UserChecksheetServiceImpl.java:428`
- **What it does today:**
  ```java
  userChecksheet.setStatus(userChecksheetDTO.getStatus());
  ```
  The operator's POST body's `status` field is written directly to
  `inspections.status` with no validation against the V1.28 enum, no
  transition check (e.g. SUBMITTED → APPROVED illegal), and no role check.
  The owner-check at line 334-337 only verifies the caller is the
  operator on the row — it does NOT restrict what they can do once they
  own it.
- **Why it's wrong:** The validate/approve pipeline gates content review
  through `UserChecksheetValidationServiceImpl` /
  `UserChecksheetApprovalServiceImpl`, which run permission checks AND
  write `*_history` lineage rows. By having the operator POST
  `{ "id": 17, "status": "APPROVED" }` to `/api/userChecksheet/createOrUpdate`,
  the inspection jumps straight to APPROVED — no validator entry, no
  approver entry, no history row. The post-V1.28 DB CHECK only restricts
  the *value* (must be one of the 6 enum strings); it doesn't gate
  transitions. The instantiation listener subscribes to
  `UserChecksheetApprovedEvent`, which is published from the
  approval-service code path — not from createOrUpdate — so plans would
  NOT auto-spawn for this elevated UC. But the BI consumes
  `status='APPROVED'` directly: the inspection counts toward the score,
  toward "audited locations," toward red/amber/green bands. Anyone who can
  log in as an operator can fabricate their own approval.
- **How to break it:** Log in as any KIA_DEMO_AUDITOR_*. Find an
  inspection they own in `myAssignments`. POST
  `{ id: <ucId>, status: "APPROVED" }` to `/api/userChecksheet/createOrUpdate`.
  Check the BI — that location now contributes a real APPROVED inspection
  to whatever the operator wrote.
- **Fix:** Replace line 428 with a transition table:
  ```java
  String newStatus = userChecksheetDTO.getStatus();
  String currentStatus = userChecksheet.getStatus();
  if (!isLegalOperatorTransition(currentStatus, newStatus)) {
      throw new CustomException("Illegal status transition " + currentStatus + " → " + newStatus, HttpStatus.FORBIDDEN);
  }
  ```
  Legal operator-driven transitions are: `ASSIGNED → IN_PROGRESS`,
  `IN_PROGRESS → SUBMITTED`, `DECLINED → IN_PROGRESS` (reopen),
  `IN_PROGRESS → IN_PROGRESS` (save without submit). Validator/approver
  paths must stay in their own services.
- **Confidence:** **High.** I read the full method, traced the status
  field, checked the call sites, and confirmed there's no method-level
  Spring Security annotation that would intercept it. The
  `assertUserChecksheetVisible` gate is read-only — it's not called from
  this method. The "round-3 decline-state fix" doc even acknowledges
  "doesn't enforce status transitions today" as the rationale for NOT
  adding a reopen-guard. The author appears to be aware the gate doesn't
  exist; they treat it as "fine because the next-state move is
  semantically forward." That's wrong — `APPROVED` is also "forward."

### F2. `createAudit` and `addAuditAssignments` have no auth gate

- **Where:** `src/main/java/com/checkSheet/service/AuditServiceImpl.java:91, 133`
  (no `requireAuditView` / `requireAuditManage` call); reached via
  `controller/AuditController.java:24-44`.
- **What it does today:** `createAudit` only calls
  `utilityService.getCurrentLoggedInUser()` to grab the user id for
  `created_by`. No `permissionService.hasPermission(...)` check. Same
  for `addAuditAssignments`. The `requireAuditView()` helper *exists* at
  line 77-84 — it's just never called on the management endpoints.
  `SecurityConfiguration.java:60-63` only enforces "authenticated";
  there's no controller-level `@PreAuthorize`.
- **Why it's wrong:** Earlier review commit `7ca8c9e` claimed to "close
  auth holes — IDOR on userChecksheetMeta + role gating on /stats
  endpoints" — but the management endpoints (`/createAudit`,
  `/addAuditAssignments`) were not in scope. Today anyone with a JWT
  (including the test KIA_DEMO_AUDITOR accounts, the mobile operators,
  the dealer principals — anyone) can spam new audit rows and pollute
  the `audits` table. With `addAuditAssignments`, the same caller can
  attach assignments to any existing audit id and silently insert
  inspection rows. Each row carries `created_by = caller`, which makes
  the act traceable, but the damage to data quality is done before any
  alert fires.
- **How to break it:** Log in as any test operator. Curl:
  ```bash
  curl -X POST http://localhost:8089/api/audit/createAudit \
       -H "Authorization: Bearer $TOKEN" \
       -H "Content-Type: application/json" \
       -d '{"name":"PWNED","checksheetId":15}'
  curl -X POST http://localhost:8089/api/audit/addAuditAssignments \
       -H "Authorization: Bearer $TOKEN" \
       -H "Content-Type: application/json" \
       -d '{"auditId":<existing>,"assignments":[{"auditeeLocationId":1}]}'
  ```
  Both return 200.
- **Fix:** Add `requireAuditView()` (or better, a stricter
  `requireAuditManage()` permission that only Section Heads and Super
  Admins hold) at the top of both methods. The `AUDIT_MANAGE`
  permission doesn't seem to exist yet — file it as a follow-up but at
  minimum guard with the existing `requireAuditView()` which already
  rejects operators.
- **Confidence:** **High.** Read both methods top to bottom; the
  permission check is verifiably absent.

### F3. `getDeclinedUserChecksheets` queries impossible statuses + has SQL injection

- **Where:** `src/main/java/com/checkSheet/DAO/UserChecksheetDAO.java:109`
- **What it does today:**
  ```java
  String query = "SELECT id,status,checksheet_id,... FROM inspections "
               + "where operator_user_id = " + userId
               + " and status in ('INVALIDATED','NOT_APPROVED')";
  ```
  V1.28's CHECK constraint on `inspections.status` allows only
  `ASSIGNED|IN_PROGRESS|SUBMITTED|VALIDATED|APPROVED|DECLINED`. Neither
  `INVALIDATED` nor `NOT_APPROVED` can ever be stored. So this query
  returns the empty list, every call. Mobile operators trying to see
  their declined inspections see nothing.
- **Why it's wrong (twofold):**
  1. **Data drift.** V1.28's status mapping table (lines 178-189 of
     V1.28 migration) maps the legacy values to `DECLINED`, but the
     reverse — every code consumer — needs to flip from
     `('INVALIDATED','NOT_APPROVED')` to `('DECLINED')`. The author
     swept the entity but missed the DAO.
  2. **SQL injection on `userId`.** Even though `userId` comes from
     `Long`, building the query via string concat removes Hibernate's
     parameter binding. If a future caller passes a string-typed value,
     this becomes a vector. The legacy DAOs (UserChecksheetDAO.java:46
     status concat, ChecksheetDAO.java similar) have this everywhere
     — V1.27/V1.28 didn't introduce most of it but rebased onto the
     `inspections` table without taking the opportunity to fix.
- **How to break it:** Decline an inspection (validator or approver
  path). It writes `DECLINED` to `inspections.status` (verified in
  `UserChecksheetValidationServiceImpl.java:126`,
  `UserChecksheetApprovalServiceImpl.java:170`). Then call
  `/api/userChecksheet/getDeclinedUserChecksheets` as the operator —
  empty array. The mobile UX is broken.
- **Fix:**
  ```java
  String query = "SELECT id,status,checksheet_id,... FROM inspections "
               + "WHERE operator_user_id = :uid AND status = 'DECLINED'";
  return em.createNativeQuery(query, "getDeclinedUserChecksheets")
           .setParameter("uid", userId).getResultList();
  ```
  Use named parameters to fix the SQLi at the same time.
- **Confidence:** **High.** Grep + read; the CHECK constraint can't be
  bypassed at the DB level, so the legacy literals are guaranteed dead.

---

## High-severity findings

### F4. `getRespectedUserChecksheet` filters out legacy statuses + has SQLi

- **Where:** `DAO/UserChecksheetDAO.java:46, 56`
- **What it does today:** Status filter clause:
  ```sql
  (select * from inspections uc where uc.status not in('IN_PROGRESS','INVALIDATED','NOT_APPROVED') ...)
  ```
  Combined with optional caller-supplied `status` interpolated via
  `" and ( uc.status = '" + checksheetDTO.getStatus() + "')"`. Two bugs
  for the price of one: legacy literals will never match (no rows stored
  with those values), and the status filter is a SQL-injection vector.
- **Why it's wrong:** Same drift as F3. The "exclude declined +
  in-progress" intent is now expressed by listing the legacy values
  PLUS the V1.28 value (`DECLINED`); but it only lists the legacy ones.
  Net effect: declined inspections now appear in this list when they
  previously didn't. Subtly wrong, depends on what the UI does with
  the result.
- **Fix:** Replace with `not in ('IN_PROGRESS','DECLINED')` and switch
  status to a parameter binding.
- **Confidence:** **High.**

### F5. DashboardServiceImpl status-set predicates miss `DECLINED`

- **Where:** `service/DashboardServiceImpl.java:353, 383`
- **What it does today:**
  ```java
  Set.of("SUBMITTED", "VALIDATED", "IN_PROGRESS", "NOT_APPROVED", "INVALIDATED")
     .stream().anyMatch(shiftStatus::contains)
  ```
  Used to classify a shift as "In Progress." Neither legacy literal
  matches post-V1.28; `DECLINED` is missing.
- **Why it's wrong:** A shift where the inspection is in `DECLINED`
  state falls through to "Missed" or "NPD" branches, mis-classifying
  the operator's actual state.
- **Fix:** Replace the legacy literals with `"DECLINED"`.
- **Confidence:** **High.** Direct grep confirms; the dashboard render
  logic falls through to wrong branches.

### F6. Audit-report stack accepts INTERVENTION-kind UC ids — possibly correct, possibly leak

- **Where:** `service/UserChecksheetServiceImpl.java:1238` (only calls
  `assertUserChecksheetVisible`, no kind check);
  `service/AuditServiceImpl.java:567-570` (has the kind check, blocks
  intervention-kind from overlay).
- **What it does today:** `getUserChecksheetWithAnswers` doesn't
  restrict to `kind='AUDIT'`. So a caller can pass an
  INTERVENTION-kind id and read its content via the audit-report API.
  The visibility gate
  (`assertUserChecksheetVisible`) checks `operatorUser` and
  `dealerPrincipalUser` — both stamped on the intervention-kind row,
  so it passes. There's no `403` for cross-kind access.
- **Why it's wrong (maybe):** I'm uncertain whether reading an
  intervention-kind UC's content via the audit-report endpoint is
  considered out-of-bounds. The contents are operator-recorded answers
  + photos for a re-inspection wave — semantically similar to an audit.
  The DP and operator both legitimately see this content. But: the
  endpoint is named `getUserChecksheetWithAnswers` and the docs frame
  it as the AUDIT report. The improvement-overlay endpoint at line 567
  *explicitly* rejects intervention-kind. The two consumers of the same
  gate disagree.
- **Fix:** Either (a) add a `kind == AUDIT` gate to
  `getUserChecksheetWithAnswers` matching the overlay's contract, or
  (b) document explicitly that the report endpoint accepts both kinds
  and confirm the FE consumes intervention-kind reports correctly.
- **Confidence:** **Medium.** The information leak depends on whether
  re-inspection answers being viewable via this endpoint is an
  intentional product decision. The inconsistency between
  `getUserChecksheetWithAnswers` and `getImprovementOverlay` is real.

### F7. UPDATE path lets operator un-submit / re-edit SUBMITTED inspections

- **Where:** `service/UserChecksheetServiceImpl.java:329-472`
- **What it does today:** The UPDATE branch (`id != null`) only checks
  operator ownership; it does not check the current status. Combined
  with F1 (caller-supplied status is written verbatim), an operator
  who already submitted can:
  1. Receive their inspection back at SUBMITTED state.
  2. POST `{ id: 17, status: "IN_PROGRESS" }` — silently pulls it back
     out of the validator's queue.
  3. Modify answers, re-submit.
  Or: POST `{ id: 17, status: "APPROVED" }` and skip everyone.
- **Why it's wrong:** This is F1's other half — once you accept that
  caller-supplied status is the bug, you also see that the "operator
  can edit their own UC" rule needs to include "only while it's
  ASSIGNED, IN_PROGRESS, or DECLINED."
- **Fix:** See F1's transition table. Also gate answer-write paths
  (createOrUpdateUserChksAns, createUserChksAnsFile) by checking
  `parentUc.status NOT IN ('SUBMITTED','VALIDATED','APPROVED')` — they
  currently only check ownership, not state.
- **Confidence:** **High.**

### F8. Listener has no error handling for unique-violation races + no DLQ

- **Where:** `event/InterventionInstantiationListener.java:30-47`,
  `service/InterventionAssignmentServiceImpl.java:269` (the
  `repository.save(ia)` that hits the unique partial index).
- **What it does today:** `@Async @TransactionalEventListener(AFTER_COMMIT)`
  with `@EnableAsync` on Application (no `TaskExecutor` bean) — Spring
  defaults to `SimpleAsyncTaskExecutor` (new thread per task, unbounded).
  The catch block handles `CustomException` only. The save line at 269
  can throw `DataIntegrityViolationException` when two concurrent
  approval events for the same `(intervention_id, auditee_location_id)`
  pair both pass the existence check and race to insert.
- **Why it's wrong:**
  1. Unbounded thread creation under heavy approval load (e.g. an
     overnight job approving hundreds of UCs) — no backpressure.
  2. Unique-violation falls out of the async thread silently. No retry,
     no DLQ, no metric. The author's idempotency claim "skip if a plan
     already exists" only works under sequential execution — the
     check-then-insert is non-atomic.
  3. The `instantiateForApprovedAudit` method is `@Transactional` and
     iterates targets in a single transaction. If save #3 of 5 hits a
     constraint, all 5 roll back. The next event re-tries and partial
     work is lost.
- **How to break it:** Run two concurrent approval events targeting
  the same intervention scope (same audit, same location). Hard to
  manufacture without modifying the code; happens naturally when a
  scheduled job approves many inspections in parallel.
- **Fix:** (a) Configure a `ThreadPoolTaskExecutor` bean with bounded
  size; (b) wrap the save in a try/catch for
  `DataIntegrityViolationException` and treat as idempotent skip (the
  unique index *is* the source of truth); (c) move the per-target loop
  to its own per-target transaction via `Propagation.REQUIRES_NEW` so
  one failure doesn't roll back the others.
- **Confidence:** **Medium.** The race is theoretically possible; in
  practice, listener events for the same approval are deduped at the
  publisher (one event per UC approval). The cross-event race (two
  different UCs at the same location triggering plans for the same
  intervention) requires multiple audit-kind inspections at one
  location — schema rules out unless you've got mid-migration partial
  states. Still a latent class of bugs the design ignores.

### F9. New audit campaigns have no `dealer_principal_user_id` — ack flow breaks

- **Where:** `service/AuditServiceImpl.java:166-178`,
  `DTO/AuditAssignmentCreateDTO.java`.
- **What it does today:** `addAuditAssignments` builds the Inspection
  row with kind=AUDIT and only sets `operatorUser`. The DTO has no
  field for `dealerPrincipalUserId` or `regionOwnerUserId`. The DP
  inheritance from audit-kind → intervention-kind in the listener
  (line 264 of `InterventionAssignmentServiceImpl`) carries through
  whatever's on the audit row — which is `null`.
- **Why it's wrong:** Then the ack endpoint at
  `InterventionAssignmentServiceImpl.acknowledge`:153-158 throws 403
  because `plan.getDealerPrincipalUser() == null`. The whole V1.30 ack
  flow only works for inspections backfilled by V100.004 (the Kia
  tenant seed that manually sets `dealer_principal_user_id`). A fresh
  non-Kia tenant can't ack anything.
- **Fix:** Either (a) extend the DTO and let the admin pass DP/region
  owner at addAuditAssignments time, or (b) derive DP at instantiation
  from `auditee_locations → auditees → dealer_principal_user_id` (a
  column that doesn't exist today — would require a schema change),
  or (c) document that ack is "Kia-only for now" and disable the
  endpoint on other tenants.
- **Confidence:** **High.**

### F10. BI scope CTE is recomputed many times per dashboard load + no covering index

- **Where:** `service/AuditServiceImpl.java:1023-1444`.
- **What it does today:** Each call to `buildStats` issues:
  - 1 CTE for band counts
  - 1 CTE for what's-failing
  - 1 CTE for top-failing-checkpoints
  - 1 CTE for level table (`buildLevelTable`)
  - 1 CTE for red dealers
  - N CTEs for AI insights (one per pattern in `tenantInsights.getCorrelations()`)
  - Plus the recency block (separate query, no CTE reuse).
  The CTE itself contains a `LEFT JOIN LATERAL (DISTINCT ON (qrid))`
  on `user_checksheet_answers` for every outer row — so the LATERAL
  fires once per inspection in scope. At Kia's seed scale (200 audit
  inspections, 5 interventions per audit, ~100 questions each), that's
  ~100k inner rows scanned per LATERAL × 200 outer × 6 panels = on the
  order of 100M+ row touches per dashboard load. The indexes that exist
  (`idx_inspections_audit_id`, `idx_user_checksheet_answers_inspection_id`)
  help but the LATERAL's filter on `chks_question_result_id IS NOT NULL`
  + sort by `submitted_at DESC NULLS LAST, uca.id DESC` isn't backed by
  a composite index on `user_checksheet_answers(inspection_id,
  chks_question_result_id, submitted_at)`.
- **Why it's wrong:** This is the BI dashboard's hot path. Without
  materialised view or a per-request memoization, every page load
  re-derives the full rollup. At the FE's polling cadence (each
  drill-down click is a new round trip), this is a real cost.
- **Fix:**
  1. Cache the CTE result for the duration of a single
     `buildStats` call (build the row set once, pass to each panel
     builder).
  2. Add `CREATE INDEX idx_user_checksheet_answers_for_rollup ON
     user_checksheet_answers(inspection_id, chks_question_result_id,
     submitted_at DESC, id DESC)`.
  3. Long-term: materialised view refreshed on inspection.status →
     APPROVED transitions.
- **Confidence:** **Medium.** I haven't EXPLAIN ANALYZE'd the queries
  on the demo DB. The shape is concerning; the actual runtime needs
  measurement before this becomes ship-blocking.

---

## Medium findings

### F11. submissionVersion type drift

- **Where:** `entity/Inspection.java:147-157`, column is SMALLINT.
- The author flagged it in comments ("M-TYPES-001"). Still a real bug:
  if any inspection ever crosses 127 versions (unlikely in practice but
  not impossible for tenants with many decline/reopen cycles),
  Hibernate truncates silently. The DB CHECK doesn't catch it; the
  Java `Byte` overflows to negative. Negative numbers might break
  downstream comparisons in the `*_history` lookup queries
  (`findByInspectionId_IdAndVersion`).
- **Fix:** Change Java field to `Short`. Cascades through
  `UserChecksheetDTO`, the validation/approval services, history
  repositories. A focused PR.

### F12. Test parallel-safety contradicted by @DirtiesContext

- **Where:** `InterventionLifecycleE2ETest.java:57-58`,
  `InterventionMultiE2ETest.java:35-36`.
- Both use `@DirtiesContext(classMode = AFTER_CLASS)`. The plan
  promises 60s suite via "Single Spring boot context per test class
  group, reused via `@SpringBootTest` + `@DirtiesContext.NEVER`." The
  code does the opposite — each class destroys and re-creates the
  context. Surefire `forkCount=1C` was supposed to parallelize across
  cores; with context destruction, every fork serializes its own
  bootstrap.
- **Fix:** Drop `@DirtiesContext` on both classes once the test
  fixtures are confirmed isolated (cleanup blocks already exist;
  removing the dirties annotation should be safe once
  `cleanupStaleTestInterventions` is verified complete).

### F13. Tests depend on seeded demo data

- **Where:** `InterventionMultiE2ETest.java:69-79`.
- `setup()` does `SELECT id, checksheet_id FROM audits WHERE
  deleted_at IS NULL ORDER BY id LIMIT 1` to pick an arbitrary audit,
  and uses `KIA_SALES_DEPT_HEAD` as the admin login. Both depend on
  `seed-kia-demo.py` having run. The test plan explicitly says
  tests must own their fixtures.
- **Fix:** Create a fresh audit + assignments inside `setup()` (the
  Lifecycle test already does this — extract the helper).

### F14. Broad error swallow on read path

- **Where:** `service/UserChecksheetServiceImpl.java:1315-1318`.
- `catch (Exception e) { log.error(...); throw new
  CustomException("Error fetching data", HttpStatus.INTERNAL_SERVER_ERROR); }`.
  The cause is logged but the wrapping CustomException doesn't carry
  it, so the GlobalExceptionHandler renders a generic message and the
  next time this fails in prod, debugging starts from the log line and
  not the stack trace at the boundary.
- **Fix:** `throw new CustomException("Error fetching data", e,
  HttpStatus.INTERNAL_SERVER_ERROR)` — the constructor exists
  (verified in `createOrUpdateUserChecksheet`).

### F15. Inspection.@Data + JPA = LazyInitializationException landmines

- **Where:** `entity/Inspection.java:65-74` declares both `@Data` and
  `@Entity`.
- Lombok's `@Data` generates `equals`, `hashCode`, and `toString`
  using every field — including the LAZY-loaded associations (audit,
  intervention, auditeeLocation, operatorUser, dealerPrincipalUser,
  regionOwnerUser, checksheet, etc.). Calling `.equals` on a detached
  Inspection (or in a stream that materialises a Set) loads all
  associations. Calling `.toString` is even worse.
- **Fix:** Replace `@Data` with `@Getter @Setter` + explicit
  `equals/hashCode` using id only. Same for other JPA entities that
  use `@Data`. Tracked at standard JPA-Lombok-anti-pattern issue.

---

## Performance concerns

(consolidated into F10 above — same query shape, same concern.)

Additional smaller concerns:
- `InterventionServiceImpl.toDTO` (line 492-528) issues 3 queries per
  intervention in the `list()` endpoint (targetRepository + assignments
  + assignments-completed-filter), so `findByDeletedAtIsNullOrderByCreatedAtDesc`
  + map-to-DTO is O(N) queries on the intervention count. Pagination
  is missing on `/list`. At 50+ interventions this is a real cost.
- `InterventionAssignmentServiceImpl.toDTO` (line 328-396) issues 1
  query per plan via `iaQuestionRepository.findByInspectionIdAndDeletedAtIsNull`.
  `myPlans`, `findByIntervention`, `findByDealer`, `findByLocation` all
  paginate by calling `toDTO` in a `.map()` — N+1.

---

## What I checked and found OK

- V1.28 migration backfill identity (§5 IDENTITY-MAP CORRECTNESS) —
  the pre-flight uniqueness checks at lines 460-511 of the migration
  are thorough; the hard-fail-vs-silently-skip semantics are correct.
- V1.28 unique partial indexes (`uk_inspections_audit_loc`,
  `uk_inspections_intervention_loc`) — properly partial on
  `deleted_at IS NULL`, so soft-deleted rows don't collide.
- V1.29 refresh_token migration — correct dynamic-named-constraint
  drop, idempotent composite-key add. Hibernate `@ManyToOne` switch
  on the entity side is consistent.
- Visibility gate `assertUserChecksheetVisible` — three callers
  (`getUserChecksheetWithAnswers`, the downloadXlsx path, the
  downloadPdf path) all go through the same `getUserChecksheetWithAnswers`
  entry, so the gate is enforced once (good DRY). The
  `getImprovementOverlay` has its own inline copy (acceptable, the
  three checks are identical), and `getUserChecksheetMeta` has its
  own inline copy. All three implementations agree on the three
  allowed roles: AUDIT_VIEW perm, operator, dealer principal.
- `claimedQuestions` endpoint is gated by `INTERVENTION_MANAGE`.
- `addAuditAssignments` has correct idempotency: the dedup-within-
  request (`seen.add(...)`) and the catch on
  `DataIntegrityViolationException` for concurrent inserts.

## What I could NOT verify

- **Whether all `/api/userChecksheet/...` answer/photo write endpoints
  enforce a status guard.** I confirmed `createOrUpdate` doesn't,
  `createOrUpdateUserChksAns` only checks ownership, and
  `createUserChksAnsFile` only checks ownership. There may be more
  write endpoints (matrix answers, trace values, judgements,
  judgement files, general field values) — I'd need to audit each
  for "is the parent inspection in a writable state."
- **Whether the FE actually calls `/api/intervention-assignment/{id}/closeNonCompliant`.**
  Backend returns 501. If the FE expects a 200, the user sees a
  "Plan close-as-non-compliant is not implemented" error message
  including the internal "M-COMP-001" issue id — an internals leak
  per Phase 4.2.
- **Whether the existing seed data was affected by V1.28's hard-fail
  guards (RAISE EXCEPTION).** The author claims "verified zero on the
  dev DB"; I trust that but a fresh-tenant migration on an unknown
  state could surprise.
- **The Inspection `@OneToOne` → `@ManyToOne` Hibernate auto-generated
  unique index drop (V1.29).** I don't have a guarantee that on
  long-lived databases Hibernate didn't generate the constraint with
  a different name on some installs. The dynamic-name drop should
  handle this but I haven't traced every Hibernate version's
  constraint-naming rules.
- **Concurrency on intervention activation vs. listener.** The
  listener fires AFTER_COMMIT on UC approval. Activation runs the
  back-fill `instantiateForApprovedAudit` for every approved UC of
  every target. If a UC approves WHILE activation is running, the
  listener and back-fill could both try to create the same plan.
  Idempotent via the unique index, but I haven't traced whether the
  back-fill's surrounding transaction holds locks that would serialize.

## Suggested follow-up priorities

1. **F1 + F7 fix together** — transition table on `createOrUpdate`
   and the answer-write endpoints. This is the single biggest privilege
   escalation here.
2. **F2** — add `requireAuditView()` (or build `AUDIT_MANAGE`) on the
   audit-management endpoints.
3. **F3 + F4 + F5** — sweep legacy status literals (INVALIDATED,
   NOT_APPROVED, PENDING, COMPLETED, NON_COMPLIANT) across the entire
   Java codebase. Grep `INVALIDATED\|NOT_APPROVED\|"PENDING"\|"COMPLETED"\|"NON_COMPLIANT"`
   from the project root and reconcile every hit.
4. **F8** — bound the async pool, handle the unique-violation race
   explicitly, and split the per-target loop into `REQUIRES_NEW`
   transactions to limit blast radius.
5. **F9** — design the DP source-of-truth for non-Kia tenants. Either
   require it on `AuditAssignmentCreateDTO` or move it to `auditees`
   (a column the auditee owns, inherited by all its inspections).

