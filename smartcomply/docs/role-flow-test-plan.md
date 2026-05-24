# Role-flow E2E Test Plan (v2 — spec-first)

> **Status: DRAFT. This plan is the spec.** Tests are written to the
> plan, not the other way around. If a flow changes, the plan changes
> first; tests follow.
>
> **Replaces v1.** The earlier version was a coverage inventory
> generated *after* tests were written — it described what the test
> code happened to do. That was backwards. This v2 is the spec; the
> `tests/e2e/roles/` suite is the implementation.

## 1. Methodology

The plan owns:

- **What** to test (each test case has a TC-ID and an expected outcome).
- **What state** the system has to be in before the test runs
  (preconditions, expressed as API calls).
- **What "passed" means** (assertions, either API response shape or
  specific UI elements).
- **What happens to test data** after the test runs (cleanup, either
  API delete or "ignored — unique-ID semantics").

Conventions every test must follow:

1. **No reliance on the demo seed.** Every test creates its own users,
   templates, audits, and inspections via the public REST API. Tests
   portable across local, UAT, or a freshly restored DB without
   `seed-kia-demo.py` running first.

2. **Run-nonce-suffixed names.** All artifacts use a
   `${prefix}-${RUN_NONCE}-${specName}` stem. Parallel runs are safe;
   stale rows from past runs are trivially identifiable.

3. **API for setup; UI for the role flow.** Setup goes through the
   API because UI setup is brittle and slow. The UI is exercised
   only on the screens the role itself lands on — that's what's
   being tested.

4. **Cleanup is best-effort.** Soft-delete via API where the entity
   supports it. Where it doesn't (e.g. an APPROVED inspection can't
   be un-approved without breaking BI), unique-ID semantics carry it.

5. **Parallel-safe.** No test reads a row another test writes. No test
   assumes ordering with another test. The full suite runs at
   `workers=10` with no shared-state collisions.

6. **20-second wall-clock @ 10 workers.** The slowest spec sets the
   budget. Tests with many independent API setup calls should group
   them with `Promise.all`.

### Seed dependencies (explicit list)

What's read-only from the seed (not created by tests, not cleaned up):

- **Auditees + locations** — no AuditeeController exists; 627 dealerships +
  1,162 locations are picked from `GET /api/audit/{seedAuditId}` assignments.
- **Templates** — full template create requires preparer-role + department
  context that's hard to satisfy from outside (backend's
  `ChecksheetServiceImpl.createChecksheet` does a layered permission check
  that rejects SUPER_ADMIN-created test users). Tests that need a template
  call `pickSeedTemplate()` which returns one of the 13 seed `APPROVED`
  checksheets. The dedicated **L2-TPLCREATE** test (template-creator role
  authoring a new template via UI) is the one exception and is currently
  `test.fixme()` until we resolve the backend permission story.
- **One bootstrap admin** (`Z006135`, SUPER_ADMIN) — needed to create test
  users. Cannot itself be created via API since user creation requires an
  authenticated session.
- **Roles + permissions + departments** — these are seed-only data anyway
  (no admin API to create roles at runtime in normal operation).

Everything else — test users, audits, assignments, inspections, answers,
interventions, ack plans — is API-created per test with run-nonce-suffixed
names.

## 2. Test layers

Five layers, top-down. Lower layers don't have to run on every push;
higher layers do.

| Layer | What it proves | Run on |
|---|---|---|
| **L1 — Smoke** | Each role can log in and land on their primary screen. | every push |
| **L2 — Happy path** | Each role can complete their primary task end-to-end. | every push |
| **L3 — Cross-role lifecycle** | Multi-role sequences and state-progression scenarios work. | every push |
| **L4 — Negative** | Permission denials, validation failures, decline flows, ordering violations. | every push |
| **L5 — BI verification** | Numbers on the BI dashboard match the data state. The CTE math is correct. | every push |

## 3. Reusable fixtures (`tests/e2e/fixtures/api-builders.ts`)

Every test calls these instead of touching the seed. The factories
return typed handles.

### 3.1 Users + roles

```typescript
createUser({ role: RoleCode, dept?: string }) → User
  // POST /api/user/createOrEditOperator
  // Returns { id, username, password, accessToken }; login is implicit.

createDealerPrincipal({ auditeeId }) → User
  // Same as createUser({role: 'DEALER_PRINCIPAL'}) plus a binding.
```

### 3.2 Templates

```typescript
createTemplateDraft({
  name?: string,
  questions: Question[],     // [{name, resultType, options?}, ...]
}) → Template (DRAFT)

submitTemplate(t: Template) → Template (SUBMITTED_FOR_VALIDATE)
validateTemplate(t: Template, asUser: User) → Template (VALIDATED)
approveTemplate(t: Template, asUser: User) → Template (APPROVED)

createApprovedTemplate(spec) → Template (APPROVED)   // chains the four above
```

### 3.3 Auditees + locations

```typescript
createAuditee({ name?, code? }) → Auditee
addLocation(a: Auditee, { city, address }) → AuditeeLocation
```

### 3.4 Audits + assignments

```typescript
createAudit({
  template: Template (APPROVED),
  startDate?: string,
  endDate?: string,
}) → Audit

addAssignment(audit: Audit, {
  location: AuditeeLocation,
  operator: User,
  dealerPrincipal?: User,
}) → Inspection (kind='AUDIT', status='ASSIGNED')
```

### 3.5 Inspection lifecycle

The central abstraction. Drives ASSIGNED → IN_PROGRESS → SUBMITTED →
VALIDATED → APPROVED in one call:

```typescript
progressInspection(insp: Inspection, {
  to: 'IN_PROGRESS' | 'SUBMITTED' | 'VALIDATED' | 'APPROVED' | 'DECLINED',
  answers?: AnswerSpec[],    // required to reach SUBMITTED
  asValidator?: User,        // required to reach VALIDATED
  asApprover?: User,         // required to reach APPROVED
  declineReason?: string,    // required for DECLINED
}) → Inspection
```

Most tests use `progressInspection(insp, {to: 'APPROVED', ...})` to
set up the state they need without spelling out every step.

### 3.6 Interventions + plans

```typescript
createIntervention({
  audit: Audit,
  name?: string,
  priority?: 'P1' | 'P2' | 'P3',
  targetDate?: string,
  questionIds?: number[],         // omit → picks all failing questions
  targetingMode?: 'ALL' | 'BY_REGION' | 'MANUAL',
  targetAssignmentIds?: number[],
}) → Intervention (DRAFT)

activateIntervention(i: Intervention) → Intervention (ACTIVE)
ackPlan(planId: number, asUser: User) → Plan
```

### 3.7 BI helpers

```typescript
biNational(audit: Audit, asUser: User) → AuditStatsDTO
biRegional(audit: Audit, regionId: number, asUser: User) → AuditStatsDTO
biDealer(audit: Audit, auditeeId: number, asUser: User) → AuditStatsDTO
biLocation(audit: Audit, locationId: number, asUser: User) → AuditStatsDTO
```

## 4. Test cases

Naming: `L<layer>-<scope>-<seq>`. Scope = primary subject. Seq = small
running number.

### L1 — Per-role smoke (10 cases)

| ID | Role | Preconditions | Steps | Expected |
|---|---|---|---|---|
| **L1-AUDITOR** | Auditor | `createUser({role: OPERATOR})` | Login (API) + open `/my-audits` | URL settles off `/login`; SPA shell renders |
| **L1-AUDITVAL** | Audit Validator | `createUser({role: DATA_VALIDATOR})` | Login + open landing | Same |
| **L1-AUDITAPP** | Audit Approver | `createUser({role: DATA_APPROVER})` | Login + open landing | Same |
| **L1-MANAGER** | Manager | `createUser({role: SECTION_HEAD})` | Login + `/main-dashboard` | KPI cards render; subtitle = national or region |
| **L1-PRINCIPAL** | Dealer Principal | `createDealerPrincipal({auditeeId})` | Login + `/my-plans` | Page mounts; ack list renders (possibly empty) |
| **L1-HODD** | HoDD | `createUser({role: DEPT_ADMIN})` | Login + `/main-dashboard` | National subtitle; band-distribution panel visible |
| **L1-BUILDER** | Builder | `createUser({role: DEPT_ADMIN with INTERVENTION_MANAGE})` | Login + `/audits` | List header renders; "+ New Audit" CTA visible |
| **L1-TPLCREATE** | Template Creator | `createUser({role: SUBDEPT_ADMIN with TEMPLATE_CREATE})` | Login + `/checksheet-management` | Page mounts; list pane renders |
| **L1-TPLVAL** | Template Validator | `createUser({role: SUBDEPT_ADMIN with TEMPLATE_VALIDATE})` | Login + landing | Same |
| **L1-TPLAPP** | Template Approver | `createUser({role: SUBDEPT_ADMIN with TEMPLATE_APPROVE})` | Login + landing | Same |

**Cleanup for all L1**: soft-delete user.

### L2 — Per-role happy path (10 cases)

| ID | Role | Preconditions | Steps | Expected |
|---|---|---|---|---|
| **L2-AUDITOR** | Auditor | createApprovedTemplate + createAudit + addAssignment (operator = test user); `progressInspection({to: 'IN_PROGRESS'})` | Login as operator; open `/my-audits`; assert assignment is visible in In Progress | Card with test audit name; tap routes to checkpoint screen (if implemented) |
| **L2-AUDITVAL** | Audit Validator | Inspection at SUBMITTED via builders | Login as validator; open `/audit-report/:id`; click Validate | Status = VALIDATED post; BI `validatedCount` 0→1 |
| **L2-AUDITAPP** | Audit Approver | Inspection at VALIDATED | Login as approver; open audit-report; click Approve | Status = APPROVED; BI `greenCount`/`amberCount`/`redCount` +1 per score |
| **L2-MANAGER** | Manager | createAudit + 1 APPROVED inspection in manager's region | Login as manager; open `/main-dashboard`; click region row | Drills to regional dashboard; 1 dealer in scope |
| **L2-PRINCIPAL** | Dealer Principal | createAudit + assignment with `dealerPrincipal = test user`; intervention ACTIVE; plan auto-created targeting this assignment | Login as principal; open `/my-plans`; click Acknowledge | `acknowledged_at` set (verify API); button leaves unacked list |
| **L2-HODD** | HoDD | createAudit + 5 APPROVED inspections (varying scores) | Login as HoDD; open `/main-dashboard` | KPI strip: `auditsCompleted=5`, `avgScore≈mean(scores)`; band split matches actual |
| **L2-BUILDER-AUDIT** | Builder | Approved template exists | Login; `/audits/new`; fill name + template + dates; submit | New audit in list; `GET /audit/list` returns it |
| **L2-BUILDER-INTERV** | Builder | createAudit + 3 APPROVED inspections (some failing) | Login; `/interventions/new`; pick audit; pick failing questions; Save & Activate | Intervention ACTIVE; targets include the 3 inspections' failing questions |
| **L2-TPLCREATE** | Template Creator | (none) | Login; `/checksheet-management/create`; fill name + ≥1 question; save Draft | Template in DRAFT; visible filtered |
| **L2-TPLVAL-APP** | Tpl Validator + Approver | Template at SUBMITTED_FOR_VALIDATE | Login validator; validate; login approver; approve | Template = APPROVED; usable for new audits |

**Cleanup for L2**: soft-delete audit/intervention/template; user cleanup at suite teardown.

### L3 — Cross-role lifecycle (6 cases)

| ID | Scenario | Preconditions | Steps | Expected |
|---|---|---|---|---|
| **L3-FULLCYCLE** | Audit cycle: assign → fill → validate → approve → BI reflects | Approved template + audit + 1 assignment; all role users | progressInspection to APPROVED | After APPROVE, national stats: `totalAudits=1`; `greenCount`/`amberCount`/`redCount` matches score; `pct_ok` per CTE |
| **L3-INTERV-BEFORE** | Intervention created before any inspection approved | Approved template + audit + 5 ASSIGNED inspections | createIntervention + activate | activate returns 409 OR intervention activates with `targetAssignmentIds = []`. Document which one — both are acceptable, must be consistent. |
| **L3-INTERV-PARTIAL** | Intervention with 3 of 5 inspections APPROVED | Same + `progressInspection({to: 'APPROVED'})` for 3 | createIntervention without explicit targets; activate | Targets = 3 (only APPROVED); failing-question filter considers only those 3 |
| **L3-INTERV-AFTER** | Intervention after all 5 APPROVED | All 5 to APPROVED | createIntervention; activate | Targets = 5 |
| **L3-DECLINE-BOUNCE** | Validator declines, operator re-submits | Inspection at SUBMITTED | `progressInspection({to: 'DECLINED'})`; operator updates an answer; back to SUBMITTED | Final status SUBMITTED; decline message preserved in validation history |
| **L3-REINSPECT** | Re-inspection wave (post-intervention) | Audit + 1 APPROVED inspection failing on Q5; intervention ACTIVE targeting this assignment + Q5; principal acked | Operator's `/my-audits` now shows a new INTERVENTION-kind inspection; complete via `progressInspection({to: 'APPROVED'})` | BI `pct_ok` for that location reflects re-inspection's Q5 answer, not original |

### L4 — Negative (7 cases)

| ID | Scenario | Preconditions | Steps | Expected |
|---|---|---|---|---|
| **L4-AUDITOR-NOVAL** | Auditor can't validate | OPERATOR user; inspection at SUBMITTED | POST `/userChecksheetValidation/addUserChecksheetValidation` as operator | 403 |
| **L4-VAL-NOAPP** | Validator can't approve | DATA_VALIDATOR + inspection at VALIDATED | POST approval as validator | 403 |
| **L4-CROSS-TENANT** | Operator A can't fill operator B's inspection | Two operators; B has IN_PROGRESS inspection | A POSTs `createOrUpdate` for B's inspection | 403 |
| **L4-DECLINE-NOCOMMENT** | Decline without comment | Inspection at SUBMITTED | POST validation status=DECLINED + empty comment | 400 with "comment required" |
| **L4-DUP-INTERV** | Two ACTIVE interventions claiming same question | Audit + Q1; I1 ACTIVE on Q1 | Create I2 on same audit + Q1; activate I2 | 409 (or graceful equivalent) |
| **L4-PRINCIPAL-WRONG** | User X can't ack principal Y's plan | Two principals; plan belongs to Y | X POSTs `/intervention-assignment/{plan.id}/acknowledge` | 403 |
| **L4-INVALID-TRANS** | Cannot APPROVE without VALIDATED | Inspection at SUBMITTED (not VALIDATED) | POST approval | 400 with "must be validated first" (or 409 — document behavior) |

### L5 — BI verification (6 cases)

Setup known data via API → verify `GET /api/audit/{id}/stats/*` matches.
Assertions are on the JSON response (the SPA renders verbatim).

| ID | Setup | Verify |
|---|---|---|
| **L5-BAND-3** | 3 APPROVED inspections with scores 90, 60, 30 | `biNational(audit, hodd)` → `greenCount=1, amberCount=1, redCount=1, totalAudits=3` |
| **L5-AVG** | 4 APPROVED with scores [100, 80, 60, 40] | `avgScore ≈ 70` |
| **L5-RED-DEALERS** | 3 dealers, one avg=40, two avg≥70 | `redDealers.length=1`; auditee = the 40-scorer |
| **L5-RECENCY** | Audit + 10 assignments; 3 APPROVED, 7 ASSIGNED | `auditedLocations=3, totalLocations=10, neverAudited=7` |
| **L5-WHATSFAILING** | 5 APPROVED, all failing on Q1 (category=Branding) | `whatsFailing[0].category='Branding', failurePct=100` |
| **L5-INTERV-DELTA** | Original score 40; intervention re-inspection lifts to 80 | BI `pct_ok` for that location = 80 (latest-wins), not 40 |

## 5. Out of scope (deferred)

- **Photo upload + AI assessment in auditor flow.** Multipart upload from Playwright is heavy; LLM call has cost concerns. Track separately.
- **Mobile gestures** (swipe, long-press). Auditor mobile spec uses viewport sizing only.
- **Real-time / WebSocket flows.** None today; if dashboards become live, add an L6.
- **Permission-management UI.** Out of role-flow scope.
- **Tenant onboarding.** Covered by `tenants/_docs/onboarding-new-tenant.md`.

## 6. role-flows.md gaps (tracked separately)

These are `@gap`-tagged `test.fixme()` cases in the specs (one per
gap from `docs/role-flows.md`). They appear in `npm run e2e:gaps`
output and turn green automatically when the gap closes. Separate
from L1–L5: those test what exists; gap tests describe what should
exist.

Current gap inventory (15):
1. §1 Auditor — `/my-audits` queue replaces generic-dashboard landing
2. §2 Audit Validator — `/inbox/audits-to-validate` queue
3. §3 Audit Approver — `/inbox/audits-to-approve` queue
4. §4 Manager — contextual "New intervention from here" CTA on dealer/region
5. §4 Manager — region picker for multi-region managers
6. §5 Dealer Principal — `/my-plans` → `/my-dealership` rename
7. §5 Dealer Principal — audits-of-my-dealership section on the page
8. §7 Builder — unified `/campaigns` route with Audits|Interventions tabs
9. §7 Builder — `/campaigns/audits/:auditId` detail with Locations/Interventions/BI tabs
10. §7 Builder — audit-row "New intervention from here" CTA
11. §8 Tpl Creator — unified `/templates` lifecycle landing
12. §9 Tpl Validator — `/inbox/templates-to-validate` queue
13. §9 Tpl Validator — status filter exposes SUBMITTED
14. §10 Tpl Approver — `/inbox/templates-to-approve` queue
15. §10 Tpl Approver — status filter exposes VALIDATED

## 7. Performance contract

Steady-state wall-clock at `workers=10`:

| Layer | Target | Why |
|---|---|---|
| L1 | < 3 s | Smoke fast enough to run on every commit |
| L1+L2 | < 10 s | The "I broke a role's flow" gate |
| L1–L4 | < 15 s | Add negative tests without slowing the loop |
| L1–L5 | < 20 s | The full contract |

If a layer exceeds its target, the slowest test gets optimised first
(API setup over UI navigation; parallelise independent setup calls).

## 8. Revision history

- **v2 — 2026-05-13 (draft).** Promoted from inventory to spec.
  Replaced "describe what tests exist" phrasing with "describe what
  should be tested". Added L3 cross-role lifecycle, L4 negative, L5
  BI verification layers. Each test case has explicit preconditions,
  steps, expectations, cleanup. API builder contract in §3 —
  implementation lives in
  `smartcomply-angular/tests/e2e/fixtures/api-builders.ts`. The
  earlier seed-user-based fixtures (`SEED_USERS` map) are deprecated;
  no test in this plan touches them.

- **v1 — 2026-05-13 (deprecated).** Inventory of the 10 role specs
  written without an upfront spec. Documented today-state coverage +
  role-flows.md gap list. Reorganised here under §6.
