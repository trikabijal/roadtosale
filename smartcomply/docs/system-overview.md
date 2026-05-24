# AuditPro -- System Overview

This is the orientation doc. Read it first if you have never looked at
the codebase before, or if you need a working mental model of how the
pieces fit together. Deeper material is cross-linked at the end of each
section.

---

## 1. What AuditPro is

AuditPro is a digital audit-management platform built for OEMs that run
periodic compliance audits across their dealer network. The reference
deployment is **Kia**, where every Kia showroom in India gets walked
through ~55 checkpoints (branding, cleanliness, staffing, EV
infrastructure, etc.) every six months. Other OEMs onboard via the same
codebase with a per-tenant overlay.

The system is three deliverables that share one Postgres database and
one LLM module:

| Repo | What it is | URL |
|---|---|---|
| `smartcomply` (this repo) | Spring Boot 3.1.4 / Java 17 REST backend. Internal artifact name still `smartcomply`. Powers everything below. | `:8089` |
| `smartcomply-angular` | Angular BI dashboards + audit/intervention admin web app. Used by OEM HQ and regional managers. | `:4200` |
| `auditpro-mobile-app` | Ionic + Capacitor field-auditor app. Used on tablets and phones by the auditors who walk the dealerships. | `:8100` (PWA) |

The backend depends on the **Trika LLM module** (`com.trika:llm`) for
photo assessment via Anthropic / OpenAI / Gemini Flash. The JAR is
checked into `lib/` so local builds need no auth — see
[`build.md`](./build.md).

The customer (OEM) is multi-tenant: terminology, BI insights, seed
users / dealerships, and Flyway data migrations are tenant-specific.
The base build is generic AuditPro; the Kia overlay is the most
complete worked example. See
[`onboarding-new-tenant.md`](./onboarding-new-tenant.md).

---

## 2. The five anchor entities

The audit + intervention domain is built around the **`Inspection`** entity. Four anchor concepts:

| Entity | Table | One-line role |
|---|---|---|
| `Checksheet` | `checksheets` | Reusable **template** — questions, options, scoring rules. |
| `Audit` | `audits` | A **campaign** — name + dates + status + which template. |
| `Intervention` | `interventions` | An **improvement campaign** layered on an audit, targeting failing questions. |
| `Inspection` | `inspections` | The **runtime row**. `kind='AUDIT'` for original audit visits; `kind='INTERVENTION'` for re-inspection waves. One per (audit, location) or (intervention, location). Owns status, answers, photos, judgements via child tables. |

See **[schema.md](./schema.md)** for the canonical schema reference: every table, every column, every constraint, the V1.28 collapse mapping, ER diagram, and migration history.

Why this matters operationally: the V1.28 collapse means one submission state machine (ASSIGNED → IN_PROGRESS → SUBMITTED → VALIDATED → APPROVED, with DECLINED branching back), one photo-upload path, one AI-assessment path, one BI rollup query — regardless of whether the inspection is an audit visit or a re-inspection wave.

---

## 3. The audit lifecycle

The audit side is the original AuditPro flow. It pre-dates interventions
and is unchanged by V1.27.

### 3.1 State machine

```
IN_PROGRESS ── operator submits ──▶ SUBMITTED ── validator approves ──▶ VALIDATED ── approver approves ──▶ APPROVED
                                        │                                   │                                  │
                                        │                                   │                                  └─ NOT_APPROVED (terminal — operator must create a new UC for the same assignment)
                                        │                                   └─ INVALIDATED (terminal — same)
                                        └ ...
```

INVALIDATED and NOT_APPROVED are terminal. Re-do means a new
`user_checksheet` row against the same `audit_assignment_id`.

### 3.2 What happens at each step

| Step | Actor | What happens server-side |
|---|---|---|
| **Get assignments** | Operator opens mobile app | `GET /api/audit/myAssignments` returns rows where `operator_user_id = me` and assignment is active. |
| **Start UC** | Operator | `POST /api/userChecksheet/createOrUpdate` with `auditAssignmentId`. Server resolves audit + location + checksheet from the assignment. UC created with status IN_PROGRESS. |
| **Answer questions** | Operator | `POST /createOrUpdateUserChksAns` per-answer. Photo evidence (`createUserChksAnsFile`) allowed on any answer type. AI assessment runs in the background and writes to `ai_assessments`. |
| **Submit** | Operator | UC.status flips to SUBMITTED. Email goes out to data validators. |
| **Validate** | Data Validator | `POST /api/userChecksheetValidation/addUserChecksheetValidation` flips to VALIDATED. |
| **Approve** | Data Approver | `POST /api/userChecksheetApproval/addUserChecksheetApproval` flips to APPROVED. **At this point** `UserChecksheetApprovalServiceImpl` publishes a `UserChecksheetApprovedEvent` (AFTER_COMMIT, async). |
| **BI sees the data** | OEM HQ / regional managers | Once APPROVED, the UC is included in every BI query that joins `audit_signal` (which filters `uc.status = 'APPROVED'`). |

The `UserChecksheetApprovedEvent` is the hinge between the audit side
and the intervention side. See section 4.

Full flow with code-level steps: [`flows.md`](./flows.md) §7-§8.
Mobile API contract: [`api.md`](./api.md) §"Mobile API flow (audit lifecycle)".

---

## 4. The intervention lifecycle (V1.27+)

Interventions are reactive: an audit identifies failures, and an
intervention is the structured follow-up to fix specific failing
questions across a subset of dealers. They are modelled as a parallel
hierarchy to `Audit` because their cadences, scopes, and question
sets differ — see the rationale in
[`architecture.md`](./architecture.md) §"Intervention layer".

### 4.1 Author the campaign

Admin (with `INTERVENTION_MANAGE`) creates a draft via
`POST /api/intervention/createDraft` with:

- Name, theme, priority (P1/P2/P3), target_date.
- A list of `chksQuestionId` to track.
- A targeting mode: **ALL** (every audit_assignment in the audit),
  **BY_REGION** (every aa whose state.region_id matches), or
  **MANUAL** (an explicit list of audit_assignment_ids).

Admin then calls `POST /api/intervention/{id}/activate`. The activate
call:

1. Validates question scope is non-empty.
2. Checks no other ACTIVE intervention on the same audit cycle claims
   any of the same questions — if it does, returns **HTTP 409** with
   a `QuestionConflictDTO[]` body. (The frontend has a preview
   endpoint, `GET /{id}/activationConflicts`, that surfaces this
   without committing.)
3. Materialises the target set by writing
   `intervention_assignment_targets` rows — one per audit_assignment
   that will be considered for plan creation.
4. Flips intervention.status from DRAFT → ACTIVE.

### 4.2 Plan instantiation on the next audit approval

When a `UserChecksheetApprovedEvent` fires (audit-side approval — see
3.2):

`InterventionInstantiationListener.onApproved` runs (AFTER_COMMIT, async):

- Find all `intervention_assignment_targets` for `aa.id = ucEvent.aaId`.
- For each ACTIVE intervention on that target whose **question scope ∩
  failing answers** on this UC is non-empty:
  - Create one `intervention_assignment` row (`status=PENDING`).
  - Create `intervention_assignment_questions` rows for the overlap
    set — these are the questions this plan will track.

Idempotent: if the listener fires again on retry, no duplicate plan is
created.

### 4.3 Dealer Principal acknowledges

The dealer principal logs in (new `DEALER_PRINCIPAL` role,
`INTERVENTION_ASSIGNMENT_ACKNOWLEDGE` permission), opens **My Plans**
(`GET /api/intervention-assignment/myPlans` — server-scoped to
`audit_assignments.dealer_principal_user_id = me`), and clicks
Acknowledge on a plan.

`POST /api/intervention-assignment/{id}/acknowledge`:
- Verifies caller is the dealer principal on the underlying aa.
- Plan flips PENDING → IN_PROGRESS, stamps `acknowledged_at` and
  `acknowledged_by`.

### 4.4 Re-inspection wave

The operator opens their mobile **My Assignments** list — re-inspection
plans appear alongside original audit assignments. They tap one and
fill the same `/question` screens. The mobile app POSTs to the **same**
`createOrUpdate` endpoint, but with `interventionAssignmentId` instead
of `auditAssignmentId`. The server creates a `user_checksheet` row
parented by `intervention_assignment_id`.

The submission goes through SUBMITTED → VALIDATED → APPROVED exactly
like an audit UC. On final APPROVE, the same
`UserChecksheetApprovedEvent` fires.

This time the listener routes differently:

- `if uc.intervention_assignment_id IS NOT NULL`:
  → `evaluatePlanCompletion(uc)`. If every tracked question on the plan
    is OK on this UC's answers, plan flips IN_PROGRESS → COMPLETED with
    `completed_at` set. If some are still NOT OK or missing, the plan
    stays IN_PROGRESS — operator can run another wave.

### 4.5 Daily overdue sweep

`InterventionOverdueScheduler.markNonCompliantIfOverdue()` runs at
02:30 server-local. Any plan past its `target_date` and still in
PENDING or IN_PROGRESS gets flipped to NON_COMPLIANT with
`closure_reason = "Auto-closed: target_date passed without completion"`.
Terminal — admins close manually with `closeNonCompliant` if they want
a custom reason.

Full code-level intervention flow: [`flows.md`](./flows.md) §"Intervention
lifecycle flow". Test plan and lifecycle vocabulary:
[`test-plan-intervention.md`](./test-plan-intervention.md).

---

## 5. Critical principle: BI shows post-intervention current state

This is the design hinge of the whole intervention system. **The audit
BI dashboards reflect the current effective state of each
`(audit_assignment, question)` pair, not the original audit's frozen
state.** If a dealer originally failed a question, then a re-inspection
flipped it OK, the BI shows the question as OK and the dealer's score
reflects that lift. The audit-report screen still shows the full
chain for audit trail.

### 5.1 The single change point: `audit_signal`'s `pct_ok` LATERAL

Every BI metric query in `AuditServiceImpl` derives from one shared
CTE built by `scopeCte(level)`. The CTE's `pct_ok` is computed via a
LATERAL subquery that does this:

For each (audit_assignment, chks_question_result_id) pair, take the
**latest in time** answer across:

1. The original audit UC's answers.
2. Every intervention re-inspection UC on the same audit_assignment,
   filtered to `status = 'APPROVED'`.

Latest in time = `ORDER BY submitted_at DESC NULLS LAST, uca_id DESC`.
That answer's judgement is what counts toward `pct_ok`.

This means:

- Three interventions producing an OK→NotOK→OK chain on one question
  → BI shows OK (latest wins).
- A re-inspection UC submitted but not yet APPROVED → score unchanged
  (the LATERAL filters to APPROVED).
- An audit_assignment with no interventions → its score equals its
  original audit UC's score (no re-inspection rows, so the latest is
  the original).

### 5.2 Why one change point matters

Every downstream metric — band counts (green/amber/red), the
per-region table, the per-dealer "red list", what's-failing by
category, top failing checkpoints, AI insights — `SELECT FROM
audit_signal`. Extending the CTE's `pct_ok` LATERAL once means every
BI panel inherits the post-intervention rollup automatically.
Reconciliation across panels is by construction; numbers cannot
diverge between, say, the national band counts and the regional table.

The **recency panel** (counting *all* locations, audited or not) joins
`auditee_locations` directly with the same scope filter — the only
structurally different query, and it doesn't care about scoring.

The CTE definition lives in
`src/main/java/com/checkSheet/service/AuditServiceImpl.java::scopeCte`.
Do not introduce parallel scoring queries elsewhere.

See [`architecture.md`](./architecture.md) §"Scoring system" for the
full scoring rules and §"BI dashboards -- single shared scope CTE" for
the structural design.

---

## 6. The BI layer

OEM HQ and regional managers consume the BI through
`smartcomply-angular`. There are four drill levels, all served by the
same `AuditStatsDTO` shape — only the scope filter changes.

| Level | Endpoint |
|---|---|
| National | `GET /api/audit/{auditId}/stats/national` |
| Region | `GET /api/audit/{auditId}/stats/region/{regionId}` |
| Dealer (auditee) | `GET /api/audit/{auditId}/stats/dealer/{auditeeId}` |
| Location | `GET /api/audit/{auditId}/stats/location/{locationId}` |

Each response carries the same panels:

| Panel | What it shows |
|---|---|
| **Band distribution** | Green / amber / red counts based on `pct_ok` thresholds. |
| **Avg score** | Mean `pct_ok` across the scope's audit_assignments. |
| **What's failing** | Failure rate per category (header-data level), surfaced as cards. |
| **Top failing checkpoints** | Question-level failure rates ranked. |
| **Regional / per-dealer / per-location table** | Drill-down list with each row's score and status. The per-dealer table groups by auditee with a `locationCount`. |
| **Red dealers list** | Dealers below the red threshold, ordered worst-first. |
| **Recency** | Counts locations including unaudited ones (separate query — see §5.1). |
| **AI insights** | Tenant-configured cross-checkpoint correlations (e.g., Kia's "paver damage + signage damage co-occurrence"). Loaded from `tenants/<id>/config/insights.yaml`. |
| **Intervention summary** | Active campaign counts, P1 completion rate, network score delta, top campaigns. Lives at `/api/audit/{auditId}/intervention-summary/{level}`. Reads the same `audit_signal` CTE — its numbers reconcile with the audit stats. |

Because every panel reads from `audit_signal`, the band counts, the
score on each row of the per-dealer table, and the dealer's score
shown on the dealer drill page are the same number, sourced from the
same CTE row. There is no path by which they can drift.

Frontend integration: [`flows.md`](./flows.md) §"UI rendering reference"
and §"Permission-based UI rules" (absorbed from the old
frontend-contract.md).

---

## 7. The audit-report screen

The audit-report (`/audit-report/{userChecksheetId}` in the Angular app)
is the per-UC walk-through: every checkpoint, the operator's answer,
the photo(s), the AI verdict, and the auditor's comment. Pre-V1.27
this was a flat per-UC view. V1.27 added two enhancements:

1. **Header score chip — original→current.** When an
   intervention has shifted the score, the header shows
   "was 67% → +14 pts" (i.e. originalScore vs currentScore from the
   overlay). The big score number itself is the rolled-up
   currentScore — same value the BI shows.
2. **Per-question chain chips.** For each question that was touched
   by one or more re-inspection waves, the row shows additional
   "RE-AUDIT" chip blocks stacked under the original answer. Each chip
   names the intervention, the auditor, the verdict (color-banded),
   the comment, the timestamp, and the photo URLs. Chips are ordered
   chronologically (oldest first).

Both are driven by `GET /api/audit/userChecksheet/{ucId}/improvement-overlay`
(`AuditServiceImpl::improvementOverlay`). The overlay returns:

- `originalScore` / `currentScore` / `scoreDelta`
- `questionFlags[chksQuestionId]` — which intervention plans flag this question.
- `reAuditAnswers[chksQuestionId]` — the temporal chain of re-inspection answers.
- `headerSnapshots[]` — per-plan summary for the header strip.

The audit-report keeps the full chain visible regardless of what the
BI shows — that's its job as the audit-trail surface.

---

## 8. Tenant overlay model

AuditPro is delivered as a generic product; tenant-specific
configuration is layered in at runtime and at build time.

| Layer | What it overrides | Mechanism |
|---|---|---|
| **Terminology** | UI labels — Kia maps `Checksheet → Audit`, `Section → Dealership`, `Operator → Auditor`. PRD's "Improvement Campaign" / "Improvement Plan" labels stay UI-facing; code stays `Intervention` / `InterventionAssignment`. | Per-tenant Angular i18n / label maps in `smartcomply-angular`. |
| **BI insights** | The "AI insights" panel's correlation patterns and message templates. | `tenants/<id>/config/insights.yaml`, loaded at JVM startup by `TenantInsightsConfig`. |
| **Seed data** | Users, departments, dealerships, geography (countries / regions / states / cities). | Flyway migrations under `src/main/resources/db/tenants/<id>/V100.x__*.sql`. Loaded only when `tenant.id` matches. |
| **Demo data** | Showcase audits with photos and AI assessments (Kia: 200 audits, ~1500 Gemini calls). | `dev/seed-<id>-demo.py` — manual, only against UAT, never prod. |

The `apply-tenant.mjs` script in the frontend repo does the build-time
flip; on the backend, the tenant is selected by environment variable
(`TENANT_ID=kia`) — no source-tree mutation. Adding a new tenant:
follow [`onboarding-new-tenant.md`](./onboarding-new-tenant.md).

The Kia tenant overlay is committed to the repo as the worked example.
Every other tenant (Ford, etc.) is opt-in.

---

## 9. AI photo assessment

For Kia and any tenant that opts in, the auditor's photos go through
an AI evaluation pipeline:

1. Operator uploads a photo via `POST /api/userChecksheet/createUserChksAnsFile`
   (multipart). Photos are allowed on any answer type, not just
   `FILE_UPLOAD`.
2. The system optionally runs `POST /api/ai/assess` with the photo +
   the question's OK / NOT OK criteria text.
3. Trika LLM module dispatches to the configured provider — Gemini
   Flash is the default for Kia (cheapest at acceptable quality);
   Anthropic and OpenAI are alternatives via the same module.
4. The verdict (suggested judgement, explanation, confidence) lands
   in the `ai_assessments` table.
5. The audit-report screen surfaces the AI verdict alongside the
   auditor's verdict on every per-question row. The same surfacing
   applies to intervention re-inspection UCs — the AI assessment
   path is shared with audit fills.

The AI is advisory: the auditor still records their own judgement.
This is intentional — see [`architecture.md`](./architecture.md)
§"AI photo assessment" for capability map and prompt design.

---

## 10. Permissions matrix

Spring Security uses fine-grained permission codes (e.g.
`USER_CREATE`). The full endpoint→permission mapping is in
[`api.md`](./api.md) §"Appendix: Permission Endpoint Mapping" and the
UI-side rendering rules are in [`flows.md`](./flows.md) §"Permission-based
UI rules". Key codes for the audit + intervention surface:

| Code | What it unlocks |
|---|---|
| `AUDIT_VIEW` (and BI-related) | Read audit list, audit detail, BI stats. |
| `CHECKSHEET_FILL_LISTING`, `CHECKSHEET_FILL_ANSWER` | Operator fill flow. |
| `CHECKSHEET_DATA_VALIDATE`, `CHECKSHEET_DATA_APPROVE` | Validator / approver steps on UCs. |
| `INTERVENTION_MANAGE` | Create / edit / activate / close interventions. |
| `INTERVENTION_ASSIGNMENT_VIEW` | Read plans (lists, detail, BI summary). |
| `INTERVENTION_ASSIGNMENT_MANAGE` | Close-as-non-compliant, admin overrides. |
| `INTERVENTION_ASSIGNMENT_ACKNOWLEDGE` | Dealer principal acknowledges their own plans. The /myPlans endpoint enforces `audit_assignments.dealer_principal_user_id = caller` regardless of permission. |
| `INTERVENTION_CONDUCT` | Operator runs a re-inspection wave (UC fill against an intervention_assignment). |
| `SUPER_ADMIN` (role) | Bypasses all permission checks. |

The `DEALER_PRINCIPAL` role is new in V1.27. Tenant onboardings need
to grant the new INTERVENTION_* permissions and create the role —
covered in [`onboarding-new-tenant.md`](./onboarding-new-tenant.md).

---

## 11. Data flow walkthrough — Modi Kia Bangalore lifts 67% → 81%

A single concrete trace through the system. Setup: audit "FY26 H1 Kia
Showroom Audit" is ACTIVE. Modi Kia Bangalore (auditee_id=42, location_id=137)
has audit_assignment_id=1380 to operator KIA_DEMO_AUDITOR_007.

### Step 1: Original audit fill

```
POST /api/userChecksheet/createOrUpdate { auditAssignmentId: 1380, status: IN_PROGRESS, ... }
  → user_checksheets (id=81, audit_assignment_id=1380, intervention_assignment_id=NULL)

POST /api/userChecksheet/createOrUpdateUserChksAns × 54
  → user_checksheet_answers × 54 (24 with judgement=2 NOT_OK, 30 with judgement=1 OK)
  → ai_assessments × 30 (the answers that had photos)

(later, validator + approver approve)
  → user_checksheets.id=81 status=APPROVED
  → publishes UserChecksheetApprovedEvent(aaId=1380, iaId=null)
```

BI at this point: dealer 42's `pct_ok` = 30/54 = 55.56% (red band).
The audit-report shows 24 red rows.

### Step 2: Admin creates intervention

```
POST /api/intervention/createDraft { name: "Q3 Cleanliness Push", priority: P1,
                                      auditId: 7, questionIds: [197,198,201,205,209],
                                      targetingMode: BY_REGION, targetRegionId: 3 }
  → interventions (id=12, status=DRAFT)

POST /api/intervention/12/activate
  → intervention_assignment_targets × 38 (every aa in region 3 — including aa 1380)
  → interventions.status = ACTIVE
```

### Step 3: Listener creates the plan

The audit UC 81's APPROVE event was published in step 1, but
intervention 12 didn't exist then. To trigger plan creation, run
another audit pass (or the listener also runs on intervention activate
if a "back-fill against existing approved UCs" pathway is configured —
PRD §3.1.6).

When the listener fires for UC 81:

```
intervention_assignment_targets has aa=1380 → intervention 12 active
intervention 12 questions ∩ UC 81 failing answers = {197, 198, 201, 209}  -- 4 of 5 failed

→ INSERT intervention_assignments (id=51, intervention_id=12, audit_assignment_id=1380, status=PENDING)
→ INSERT intervention_assignment_questions × 4 (for q 197, 198, 201, 209)
```

### Step 4: Dealer principal acknowledges

```
POST /api/intervention-assignment/51/acknowledge
  → intervention_assignments.id=51 status=IN_PROGRESS, acknowledged_at=now, acknowledged_by=<DP user>
```

### Step 5: Re-inspection wave 1 (auditor flips 3 of 4)

```
POST /api/userChecksheet/createOrUpdate { interventionAssignmentId: 51, status: IN_PROGRESS, ... }
  → user_checksheets (id=204, intervention_assignment_id=51, audit_assignment_id=NULL)

POST /createOrUpdateUserChksAns × 4
  → user_checksheet_answers — q 197 OK, q 198 OK, q 201 OK, q 209 still NOT_OK

(validator + approver approve)
  → user_checksheets.id=204 status=APPROVED
  → publishes UserChecksheetApprovedEvent(aaId=null, iaId=51)
  → listener calls evaluatePlanCompletion(204)
  → q 209 still NOT_OK → plan stays IN_PROGRESS
```

BI now: dealer 42's `pct_ok` = 33/54 = 61.11%. The score moved because
the `audit_signal` LATERAL picks up the latest answer per
(aa, qrid). The audit-report header reads "was 55% → +6 pts" and rows
197/198/201 each show a green RE-AUDIT chip stacked below the
original red row.

### Step 6: Re-inspection wave 2 (closes the last one)

```
POST /api/userChecksheet/createOrUpdate { interventionAssignmentId: 51, ... }
  → user_checksheets (id=235)

POST /createOrUpdateUserChksAns — q 209 now OK

(approve)
  → user_checksheets.id=235 status=APPROVED
  → listener: evaluatePlanCompletion sees all 4 tracked questions OK
  → intervention_assignments.id=51 status=COMPLETED, completed_at=now
```

### Step 7: BI reflects the lift

`pct_ok` for aa 1380 is now 34/54 = 62.96% (still amber). To get to
81% would require a second intervention covering the other 14 still-failing
questions and another wave that flips most of them. The mechanic
extends linearly: each subsequent intervention activation +
re-inspection chain plays the same beat through the same listener +
the same CTE. Only the audit-report's per-question chip stack and the
intervention-summary's `topCampaigns` panel grow.

### Final state visible across the system

| Surface | Shows |
|---|---|
| `/api/audit/7/stats/national` | dealer 42 contributes 62.96% to its region's avg, sits in amber band. |
| `/api/audit/7/stats/dealer/42` | avgScore reflects the rolled-up 62.96% (one location, no other interventions). |
| `/api/audit/7/intervention-summary/national` | activeCampaigns=1, totalAssignments=38, p1CompletionRate=1/(plans created so far) for dealer 42's plan, networkScoreDelta reflects this dealer's +7.4. |
| `/api/audit/userChecksheet/81/improvement-overlay` | originalScore=55.56, currentScore=62.96, scoreDelta=+7.40. `reAuditAnswers[197]` length=1, `reAuditAnswers[209]` length=2. |
| `/audit-report/81` (frontend) | Header chip "was 55% → +7 pts", rows 197/198/201/209 each show RE-AUDIT chip blocks; q 209 has two chips (NotOK then OK) in chronological order. |

---

## Cross-references

| Doc | Purpose |
|---|---|
| [`api.md`](./api.md) | Full REST endpoint reference, including §"Mobile API flow (audit lifecycle)" (mobile contract) and §"Appendix: Permission Endpoint Mapping". |
| [`architecture.md`](./architecture.md) | Detailed schema rationale, security, infrastructure, intervention layer rationale, scoring rules, AI photo assessment, and the V1.24+ evolution history. |
| [`schema.md`](./schema.md) | Canonical table-by-table reference + ER diagram. |
| [`flows.md`](./flows.md) | Code-level walkthroughs of each major flow, including intervention lifecycle, plus UI rendering / permission-based UI rules. |
| [`role-flows.md`](./role-flows.md) | Per-role UX walkthroughs (work-in-progress). |
| [`build.md`](./build.md) | Build, test, Docker, LLM module update. |
| [`../dev/test-plans/test-plan-intervention.md`](../dev/test-plans/test-plan-intervention.md) | Frozen test plan — the canonical intervention-lifecycle vocabulary. |
| [`../dev/runbooks/dedupe-runbook.md`](../dev/runbooks/dedupe-runbook.md) | V1.25 + V1.26 dedupe migrations background. |
| [`../dev/changelogs/changes-tldr.md`](../dev/changelogs/changes-tldr.md) | Recent infrastructure / config changes (CI, env vars, AI keys). |
| [`../tenants/_docs/onboarding-new-tenant.md`](../tenants/_docs/onboarding-new-tenant.md) | Adding a new OEM tenant (users, dealerships, permissions, role). |
| [`../tenants/_docs/tenant-overlay.md`](../tenants/_docs/tenant-overlay.md) | Tenant overlay mental model and mechanism. |
