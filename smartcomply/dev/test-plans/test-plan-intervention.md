# Frozen Test Plan — Intervention pipeline (V1.27+)

> **Status: FROZEN.** This plan was authored on 2026-05-10 against the
> implementation at branch `feat/improvement-campaigns`. Per the test
> methodology, the plan is the contract — tests are written **to** the
> plan, not ad-hoc. If functionality changes, update the plan first; do
> not let plan and tests drift.
>
> **Revision 2026-05-10b:** Added T1.0-A/B/C lifecycle cases (the canonical
> end-to-end correctness gate), the timing-consistency rule sweep, and a
> T3.0 browser placeholder. Test now owns its own user fixtures via
> `/api/user/createUser` instead of depending on demo-seed accounts.
> Back-fill on activate is part of the V1.27 implementation contract.
>
> **Revision 2026-05-10c (post V1.28 schema collapse):** Three
> architectural changes the plan now reflects:
>
> 1. **V1.28 schema collapse.** The three tables (`audit_assignments`,
>    `intervention_assignments`, `user_checksheets`) became one
>    `inspections` table with a `kind` discriminator ('AUDIT' |
>    'INTERVENTION'). The audit-assignment row IS the kind=AUDIT
>    Inspection; the plan row IS the kind=INTERVENTION Inspection.
>    Test SQL queries reference the new schema; **test semantics are
>    unchanged.** Wherever the plan says "audit_assignment" or
>    "intervention_assignment", it now means "Inspection of the
>    appropriate kind".
>
> 2. **Plan status rename: `PENDING` → `ASSIGNED`.** The unified status
>    enum is ASSIGNED | IN_PROGRESS | SUBMITTED | VALIDATED | APPROVED
>    | DECLINED. References to `PENDING` in this plan now mean
>    `ASSIGNED`. Behaviour identical.
>
> 3. **Acknowledged + Non-compliant deferred.** ACKNOWLEDGED is a future
>    flow we don't model yet; NON_COMPLIANT is a derived state computed
>    from compliance thresholds, not stored. Affected sections:
>    - **T1.0 step 8** — "Acknowledge plans" **SKIPPED**. Re-inspection
>      runs directly after plan creation. Timing rule
>      `plan.acknowledged_at >= plan.created_at` removed; replaced with
>      `plan.created_at <= reinspection_uc.created_at`.
>    - **T1.6** — "Dealer principal acknowledgement" **DEFERRED** until
>      the ack flow is built. Track as M-ACK-001.
>    - **T2.4** — "Overdue sweep → NON_COMPLIANT" **DEFERRED** until
>      the compliance-threshold pipeline is built. Track as M-COMP-001.
>    - **T2.7** — Mandatory P1 — already noted as outstanding (M-PLAN-001).
>
> All other tests (T1.0-A/B/C, T1.1, T1.2, T1.3, T1.4, T1.5, T1.7, T1.8,
> T1.9, T1.10, T2.1, T2.2, T2.3, T2.5, T2.6) remain in scope. Their
> assertions are unchanged beyond the schema/noun adjustments above.
>
> **One-shot inspection model (T2.2 amendment).** V1.28 also dropped
> multi-wave re-inspection support: a unique partial index on
> `inspections(intervention_id, auditee_location_id) WHERE deleted_at
> IS NULL AND kind='INTERVENTION'` enforces one Inspection per
> (intervention, location). T2.2's "5 waves on one question" is no
> longer expressible as written. The replacement is: **T2.2 (revised)
> — five chained interventions, one wave each, on the same question;
> latest-wins still holds.** Each wave is a separate intervention.

> **Revision 2026-05-10d (post external-review round 3):** Two changes:
>
> 1. **Decline state preserves DECLINED.** Earlier revisions mapped
>    legacy INVALIDATED (validator decline) and NOT_APPROVED (approver
>    decline) to IN_PROGRESS, on the reasoning that "the operator goes
>    back to fixing." Reviewer round 3 pushed back: collapsing both
>    paths into IN_PROGRESS makes a declined inspection
>    indistinguishable from "never started," losing lineage. The fix:
>    write `DECLINED` on the inspection (V1.28 enum already permits it);
>    lineage of which path declined lives on the `*_history` row.
>    Operator's next `createOrUpdate` advances state forward via the
>    existing save path — no reopen-transition guard needed because
>    the UPDATE branch doesn't enforce status transitions today.
>    V1.28 backfill mapping updated to match.
>
> 2. **T1.0 invariants strengthened.** Two new assertions in
>    `InterventionLifecycleE2ETest`:
>    - **No plans for untouched targets** — when 5 audit assignments
>      are attached but only 3 reach APPROVED, the 2 unapproved
>      assignments must produce 0 plans even after late activation.
>    - **Tracked-question exactness** — each plan's
>      `intervention_assignment_questions` rows must equal the exact
>      intersection of (failing questions on the audit UC) ∩
>      (intervention's question scope). No drift, no over-tracking.

> **Methodology:** `~/.claude/workflows/create-test-plan.md` and
> `~/.claude/workflows/review-tests.md` are the source playbooks.
> Read those before implementing.

## Scope

Cover the complete lifecycle:
1. Audit template (Checksheet) creation → walk through approval states
2. Audit campaign creation + assignment attachment
3. Intervention (Improvement Campaign) draft → activate → conflict checks
4. Audit fill (operator submit → validate → approve)
5. **Plan instantiation** triggered by audit approval
6. Dealer principal acknowledges
7. Re-inspection wave fill (operator → validate → approve)
8. Plan completion evaluation
9. Overdue sweep (cron)
10. BI rollup verification at every level (national, region, dealer, location)
11. Audit-report overlay reflects the temporal chain

## Two surfaces, both required

Every meaningful flow gets BOTH:
- **Command-line test** (Spring REST + Testcontainers / curl + bash) — CI-runnable, gates merges.
- **Browser test** (Playwright headless) — catches UI regressions.

Neither replaces the other.

## Performance contract

Whole CI-gating (Tier 1+2 command-line) suite must finish in **60 seconds**.
Browser suite (Tier 3) must finish in **2-3 minutes**. Means:
- Single Spring boot context per test class group, reused via `@SpringBootTest` + `@DirtiesContext.NEVER`.
- Per-test DB isolation via `@Transactional` rollback OR a per-test schema reset (Testcontainers Postgres with REUSE flag).
- Surefire `forkCount=1C` (one fork per CPU).
- Playwright `workers=4` for browser tests.

---

# T1.0 — Full audit-to-BI lifecycle (the canonical chain)

The single test that proves the whole system works. Owns its fixtures end
to end — no dependency on demo-seed accounts, no reuse of pre-existing
audits. Three timing variants share the chain; the non-chain steps are
identical.

## Self-sufficient fixtures

The test creates and owns:
- `test-admin-{uuid}`        → DEPT_ADMIN @ Sales (uses INTERVENTION_MANAGE, AUDIT_VIEW)
- `test-validator-{uuid}`    → DEPT_ADMIN @ Sales (CHKSHEET_DATA_VALIDATE)
- `test-approver-{uuid}`     → DEPT_ADMIN @ Sales (CHKSHEET_DATA_APPROVE)
- `test-operator-A/B/C`      → OPERATOR  @ SPA
- `test-dp-{uuid}`           → DEALER_PRINCIPAL @ Sales (stamped on the
                                audit_assignments the test creates)

Roles `DEALER_PRINCIPAL` etc. ship with V1.27 / V100.003 — no role-create
needed. Users are created via `POST /api/user/createUser` and torn down
via `DELETE /api/user/{id}` (or soft-delete) in `afterAll()`.

The audit template is the seeded `Checksheet 15` — already APPROVED and
covers all four non-matrix question types (132 SUBJECTIVE_CONDITION, 7
OBJECTIVE, 12 SUBJECTIVE, 7 FILE_UPLOAD). Template creation is **out of
scope** for this plan — separate doc when we add it.

## The chain (shared across A/B/C)

| Step | Action | Asserts (data) | Asserts (timeline) |
|---|---|---|---|
| 1 | `POST /api/audit/createAudit` | audit row exists, status=ACTIVE | record `T0 = audit.created_at` |
| 2 | `POST /api/audit/addAuditAssignments` (5 locations across ≥2 regions) | 5 rows | each `aa.created_at >= T0` |
| 3 | Create 3 UCs through `createOrUpdate` | status=IN_PROGRESS | `uc.created_at >= aa.created_at` |
| 4 | Submit answers — **6 NOT-OK each, spanning all 4 question types** | answers persisted | `uca.created_at >= uc.created_at` |
| 5 | Submit → Validate → Approve each UC | uc.status=APPROVED | strictly increasing per UC |
| 6 | (Per timing variant — see below) Create + activate **Intervention A** (covers SUBJECTIVE_CONDITION + OBJECTIVE) and **Intervention B** (covers SUBJECTIVE + FILE_UPLOAD; no overlap with A) | both ACTIVE, target sets materialised on our 3 aa | `iv.activated_at >= iv.created_at` |
| 7 | (Variant-specific — see below) plans appear via listener and/or back-fill | up to 6 plans (3 UC × 2 iv where overlap exists) | `plan.created_at` ≥ relevant approval/activation |
| 8 | ~~Acknowledge plans as test-dp~~ **SKIPPED** (Rev 2026-05-10c) — plans go straight from ASSIGNED to operator pickup; no acknowledge step | n/a | n/a |
| 9 | 6 re-inspection UCs → start (status=IN_PROGRESS) → submit → validate → approve, all tracked questions OK | each re-inspection UC=APPROVED | `re_uc.submitted_at > original_uc.submitted_at` |
| 10 | Each plan flips to APPROVED (was COMPLETED pre-V1.28; same semantics — re-inspection's own approval terminal-state) | plan (the kind=INTERVENTION Inspection) status=APPROVED | `plan.approved_at >= re_uc.submitted_at` |
| 11 | `/stats/national` shows `avgScore > baseline` | strictly greater | — |
| 12 | `/intervention-summary/national` shows activeCampaigns=2, p1CompletionRate>0, networkScoreDelta>0 | numbers match | — |
| 13 | `/audit/userChecksheet/{id}/improvement-overlay` for each UC | `originalScore == baseline_pct[uc]`, `currentScore > originalScore`, `scoreDelta = current − original` (within 0.01) | `reAuditAnswers[qid]` strictly increasing by `answeredAt`, judgement chain `NOT_OK → OK` |
| 14 | `/stats/region/{rid}` consistent with national | rollup math holds | — |

## Timing variants

The intervention timing matrix is the load-bearing question — when can an
OEM activate an intervention relative to audit progress, and do plans
still appear?

| Variant | When intervention is activated | Plan creation mechanism | Expected outcome |
|---|---|---|---|
| **T1.0-A** | Step 6 runs **before** any UC is approved (between steps 4 and 5) | listener fires on each approval (step 5) | all 6 plans created during step 5 |
| **T1.0-B** | Step 6 runs **after** UC 1 is approved, **before** UC 2/3 | UC 1 → back-fill at activate-time; UC 2/3 → listener on subsequent approval | all 6 plans created (some via back-fill, some via listener) |
| **T1.0-C** | Step 6 runs **after** all 3 UCs approved (end of step 5) | back-fill at activate-time | all 6 plans created in step 6 |

All three variants must produce the SAME final state (steps 8-14 are
identical). The variants prove that:
1. The OEM can activate at any point in the audit cycle without losing plans.
2. Back-fill is idempotent — re-running activate (e.g. closing then
   reactivating) does not duplicate plans.
3. The audit_signal CTE picks the latest answer regardless of when the
   intervention activated.

## Back-fill behaviour (V1.27+ contract)

`InterventionServiceImpl.activateWithTargeting()` (and the legacy
`activate(id)`) sweep every materialized target's APPROVED audit UCs at
activation time and call `InterventionAssignmentService.instantiateForApprovedAudit(uc)`
on each. The same idempotency guard the listener uses
(`findByInterventionIdAndAuditAssignmentIdAndDeletedAtIsNull`) prevents
duplicates if the listener has already created a plan.

## Timing-consistency rule sweep

Run as a single helper at the end of the chain. Any inequality violation
fails the test with the offending pair printed.

```
Wall-clock window:
  T_TEST_START - 5s  ≤  every persisted timestamp X  ≤  T_TEST_END + 5s

Per UC (audit + re-inspection):
  uc.created_at  ≤  uc.submitted_at  ≤  uc.approved_at

Plan provenance (whichever path produced the plan; Rev 2026-05-10c —
acknowledge step removed):
  audit_uc.approved_at  ≤  plan.created_at
  plan.created_at       ≤  reinspection_uc.created_at
  reinspection_uc.created_at  ≤  reinspection_uc.submitted_at
  reinspection_uc.submitted_at ≤ reinspection_uc.approved_at
  (Note: post-V1.28 the "plan" IS the kind=INTERVENTION Inspection, so
   plan.created_at == reinspection_uc.created_at when the plan is the
   same row as the re-inspection UC. The chain still holds — we just
   compare against the same row's lifecycle timestamps.)

Overlay chain ordering:
  for each chain in overlay.reAuditAnswers[qid]:
      answeredAt strictly increasing (no ties unless same UC id)

Audit_signal latest-wins:
  for each (aa, qrid) the row that contributes to pct_ok IS the answer
  with the maximum (submitted_at, uca.id) — verified by separate query
  that emits any disagreement.
```

The 5-second window is generous for clock drift on CI runners and tight
enough to catch real bugs (the test itself runs in 8-15 seconds).

---

# Tier 1 — Critical path (must always pass on every PR)

These verify the core business flow. If any of these fail, the demo is dead.

## T1.1 — Audit creation (CLI)

| Test                                                           | Expected                                                               |
|----------------------------------------------------------------|------------------------------------------------------------------------|
| `POST /api/audit/createAudit` with valid checksheetId          | 200, status=true, returns `data.id` and status='ACTIVE'                |
| Same name twice                                                | 200, returns existing audit (no duplicate)                             |
| Invalid checksheetId                                           | 422 with descriptive message                                           |
| Missing name                                                   | 422                                                                    |

## T1.2 — Audit assignment attachment (CLI)

| Test                                                                          | Expected                                          |
|-------------------------------------------------------------------------------|---------------------------------------------------|
| `POST /api/audit/addAuditAssignments` with N locations, idempotency on rerun  | First call: N created. Second call: 0 created.    |
| Mix of valid and invalid `auditeeLocationId`                                  | 422 (whole batch rejected, no partial create)     |
| Optional `operatorUserId` per assignment                                      | When set, sticks; when null, allowed              |

## T1.3 — Intervention draft → activate (CLI)

| Test                                                                                        | Expected                                          |
|---------------------------------------------------------------------------------------------|---------------------------------------------------|
| `POST /api/intervention/createDraft` with valid auditId, priority, questionIds, targeting   | 200, status='DRAFT', returns id                   |
| `POST /api/intervention/{id}/activate` with targeting=ALL                                   | 200, status='ACTIVE', activatedAt set, target set materialised covers all aa on the audit |
| `activate` with targeting=BY_REGION + targetRegionId                                        | Target set materialised covers only aa whose state.region_id matches |
| `activate` with targeting=MANUAL + targetAuditAssignmentIds=[N1, N2]                        | Target set is exactly {N1, N2}                    |
| `activate` with no questions                                                                | 422 ("Cannot activate without any questions")     |
| `activate` an already-ACTIVE intervention                                                   | 422 (only Drafts can be activated)                |

## T1.4 — Question conflict on activate (CLI)

**Scenario**: two ACTIVE interventions on the same audit, both claiming
question 197.

| Test                                                                          | Expected                                            |
|-------------------------------------------------------------------------------|-----------------------------------------------------|
| Create A, activate A (questions = [197, 198])                                 | 200                                                 |
| Create B (questions = [197]), call `GET /{B}/activationConflicts`             | Returns one row referencing A with overlap=[197]    |
| Activate B                                                                    | **HTTP 409** with `QuestionConflictDTO[]` body      |
| Drop 197 from B (`POST /B/setQuestions [198]`), retry activate                | 200                                                 |

## T1.5 — Plan instantiation on audit approval (CLI)

**Scenario**: intervention X targets aa 1380 with question scope {197, 198}.
UC 81 (on aa 1380) is approved with NOT-OK answers on 197 and 198.

| Test                                                                          | Expected                                                                          |
|-------------------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| Approve UC via the existing flow (`addUserChecksheetApproval`)                | One kind=INTERVENTION Inspection (plan) created with status=ASSIGNED              |
| Same approval flow re-runs (idempotency, e.g. retry after transient failure)  | No duplicate plan created                                                         |
| UC has no failing answers on intervention's questions                         | No plan created                                                                   |
| Intervention status = DRAFT (not ACTIVE)                                      | No plan created                                                                   |
| Intervention deleted (deleted_at set)                                         | No plan created                                                                   |

## T1.6 — Dealer principal acknowledgement (CLI) — **DEFERRED (Rev 2026-05-10c)**

The acknowledge flow is not implemented in V1.28; ACKNOWLEDGED is a future
state we don't model. Tracked at GitLab follow-up **M-ACK-001**. When the
flow returns, the assertions below apply with `ASSIGNED` substituted for
`PENDING`.

> Original (preserved for reference):
>
> | Test                                                                                       | Expected                                                              |
> |--------------------------------------------------------------------------------------------|-----------------------------------------------------------------------|
> | Caller IS the dealer_principal_user on the underlying aa, plan status=PENDING              | 200, status flips to IN_PROGRESS, acknowledged_at + acknowledged_by set |
> | Caller IS NOT the dealer_principal_user                                                    | 403 Forbidden                                                         |
> | Plan status already IN_PROGRESS                                                            | 422 ("Only PENDING plans can be acknowledged")                        |
> | Plan status COMPLETED / NON_COMPLIANT                                                      | 422                                                                   |

## T1.7 — Re-inspection wave UC approval → plan completion (CLI)

**Scenario** (Rev 2026-05-10c): plan tracks {197, 198}. Operator drives the
re-inspection on the kind=INTERVENTION Inspection itself (no separate UC
row — the Inspection IS the plan, status flows ASSIGNED → IN_PROGRESS →
SUBMITTED → VALIDATED → APPROVED). Answers OK on both tracked questions.

| Test                                                                                      | Expected                                                                                |
|-------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------|
| All tracked questions OK on this Inspection                                               | Inspection's own approval terminal-state lands; final status=APPROVED                   |
| Some tracked questions OK, some not                                                       | Inspection still APPROVED (the validate/approve gate is on the form, not the question scope) — but the audit-report overlay shows the mixed result |
| Same Inspection approved a second time (re-fire — e.g. validate→approve→re-validate)     | Idempotent — already APPROVED stays APPROVED                                            |

## T1.8 — Audit_signal CTE rollup (CLI; pure SQL)

This is the single most important behavioural test. The CTE is the hinge
of the whole intervention scoring principle.

| Setup                                                                                                                           | Expected pct_ok for the audit UC's aa |
|---------------------------------------------------------------------------------------------------------------------------------|---------------------------------------|
| Audit UC alone, 24/54 NOT-OK                                                                                                    | 55.56                                 |
| Add intervention re-inspection wave APPROVED, flips 3 questions OK                                                              | 61.11 (3/54 lift)                     |
| Add a SECOND intervention re-inspection wave APPROVED later, flips 2 more OK                                                    | 64.81                                 |
| Add a THIRD wave (later submitted_at) that flips one back to NOT-OK                                                             | 62.96 (one question reverted)         |
| Add a FOURTH wave that flips that same question OK again                                                                        | 64.81                                 |
| A wave submitted but **NOT YET APPROVED** (status=SUBMITTED)                                                                    | Score unchanged (only APPROVED counted) |
| A second audit_assignment with no interventions on it                                                                           | pct_ok identical to its audit UC alone |

## T1.9 — Audit-report overlay (CLI + Browser)

Pre-condition: same setup as T1.8, three interventions on UC 81.

| CLI test                                                                  | Expected                                                                                       |
|---------------------------------------------------------------------------|------------------------------------------------------------------------------------------------|
| `GET /api/audit/userChecksheet/81/improvement-overlay`                    | originalScore=55.56, currentScore reflects rollup, scoreDelta = currentScore-originalScore     |
| `questionFlags[197]` length                                               | 1 entry (one intervention covers q 197) OR 2 if two overlapping interventions cover it         |
| `reAuditAnswers[197]` length                                              | Equals the number of re-inspection waves that touched q 197                                    |
| Each `reAuditAnswers[qid]` entry                                          | Has interventionId, interventionName, auditorName, judgement, comment, answeredAt, photoUrls   |
| Order of `reAuditAnswers[qid]`                                            | Chronological by answeredAt (oldest first)                                                     |

| Browser test                                                              | Expected                                                                                                     |
|---------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|
| Navigate to /audit-report/81 with three interventions touching q 197      | Header shows "was 55% → +N pts" chip; each row for q 197 shows three RE-AUDIT chip blocks in chronological order |
| Each chip block names the intervention                                    | Visible "Intervention X" label per block                                                                     |
| OK→NotOK→OK transitions visually distinct                                 | Green/red bands on each chip clearly differentiated                                                          |

## T1.10 — BI summary reflects current state (CLI + Browser)

Pre-condition: T1.8 setup applied to one dealer (so currentScore != originalScore).

| CLI test                                                                            | Expected                                                                |
|-------------------------------------------------------------------------------------|-------------------------------------------------------------------------|
| `GET /api/audit/{auditId}/intervention-summary/national`                            | activeCampaigns ≥ 1, p1CompletionRate matches plan COMPLETED ratio, networkScoreDelta reflects average dealer lift |
| `GET /api/audit/{auditId}/stats/national`                                           | greenCount/amberCount/redCount and avgScore reflect the rolled-up scores (the dealer that improved is now in the right band) |

| Browser test                                                                        | Expected                                                                                  |
|-------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------|
| Open /main-dashboard                                                                | Campaign Compliance Summary panel shows non-zero numbers                                  |
| Open /dealer-dashboard/{auditeeId} for the lifted dealer                            | Dealer's score chip is now the rolled-up score, not the original                          |

---

# Tier 2 — Integration & multi-intervention chaos

These exercise the harder edge cases. Run on every PR, but allowed to be
slower than Tier 1.

## T2.1 — Three-or-more interventions on one assignment

A single audit_assignment with 4 active interventions, each touching 2-3
questions, some overlapping.

| Test                                                                          | Expected                                            |
|-------------------------------------------------------------------------------|-----------------------------------------------------|
| Audit UC approval                                                             | Up to 4 plans created (one per matching intervention) |
| Each plan tracks the correct intersection (failing questions ∩ intervention scope) | Verified per-plan via `intervention_assignment_questions` |
| Audit-report overlay groups chips correctly per question                      | A question covered by 2 interventions shows 2 chips per wave |
| BI national summary's `topCampaigns` lists all 4 ordered by plan_count desc   | Stable ordering                                     |

## T2.2 — OK→NotOK→OK→NotOK→OK chain on one question (Rev 2026-05-10c)

V1.28 dropped multi-wave-per-intervention. Replacement: **5 chained
interventions, one wave each**, on the same question. Each intervention's
re-inspection contributes one entry to the chain. Same latest-wins
behaviour, same overlay assertions.

Same q across 5 chained interventions: orig NotOK, iv1 OK, iv2 NotOK
(regression), iv3 OK, iv4 NotOK (second regression), iv5 OK.

| Test                                                                              | Expected                                                            |
|-----------------------------------------------------------------------------------|---------------------------------------------------------------------|
| BI rollup pct_ok                                                                  | Reflects iv5's verdict (OK)                                         |
| Audit-report overlay `reAuditAnswers[q]` length                                   | 5 entries in order (one per intervention)                           |
| Each entry attributes to the right intervention                                   | Verified via `interventionName` field                               |

## T2.3 — Targeting modes

| Setup                                                                          | Expected materialised target set                                  |
|--------------------------------------------------------------------------------|-------------------------------------------------------------------|
| Audit has 200 aa across 6 regions; activate with targetingMode=ALL             | 200 InterventionAssignmentTarget rows                             |
| Same audit, activate with targetingMode=BY_REGION, targetRegionId=South        | Only the rows whose state.region_id=South                         |
| Same audit, targetingMode=MANUAL, targetAuditAssignmentIds=[1380, 1381]        | Exactly 2 target rows                                             |
| MANUAL with an aa that doesn't belong to the audit                             | Excluded silently (not failed)                                    |

## T2.4 — Overdue sweep — **DEFERRED (Rev 2026-05-10c)**

NON_COMPLIANT is a derived state in the V1.28 model (computed from
per-tenant compliance thresholds, not stored). The cron-driven overdue
sweep is deferred until that compliance pipeline is built. Track at
GitLab follow-up **M-COMP-001**.

> Original (preserved for reference):
>
> | Setup                                                                          | Expected                                                          |
> |--------------------------------------------------------------------------------|-------------------------------------------------------------------|
> | Plan with target_date < today, status=PENDING                                  | After cron run: status=NON_COMPLIANT, closed_as_non_compliant_at set, closure_reason auto-string |
> | Plan with target_date < today, status=COMPLETED                                | Unchanged (terminal)                                              |
> | Plan with target_date >= today, status=PENDING                                 | Unchanged                                                         |
> | Plan deleted (deleted_at set), past target                                     | Unchanged                                                         |

## T2.5 — Permission gating (CLI; auth-only matrix)

For every endpoint:

| Caller permission                          | Expected                                       |
|--------------------------------------------|------------------------------------------------|
| Has the right permission                   | 200                                            |
| Doesn't have the permission                | 403                                            |
| Not authenticated                          | 401                                            |

Specifically test:
- `INTERVENTION_MANAGE` on createDraft / update / activate / close / delete
- `INTERVENTION_ASSIGNMENT_VIEW` on myPlans / by* / detail
- `INTERVENTION_ASSIGNMENT_ACKNOWLEDGE` + dealer-principal-id match on acknowledge
- `INTERVENTION_ASSIGNMENT_MANAGE` on closeNonCompliant

## T2.6 — Multi-location dealer rollup

A dealer (auditees row) with 3 locations. Plans on 2 of the 3.

| BI endpoint                                                          | Expected                                                                            |
|----------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| `/api/audit/{aid}/stats/dealer/{auditeeId}`                          | avg_score includes all 3 locations' rolled-up pct_ok (the location with no plan stays at original) |
| `/api/audit/{aid}/intervention-summary/dealer/{auditeeId}`           | totalPlans = 2, dealerCount = 1                                                     |

## T2.7 — Mandatory P1 plan (no parent intervention)

PRD §2.2 — when a P1 question fails on an audit and no covering
intervention exists, a Mandatory P1 plan is created with `intervention_id`=NULL.

| Test                                                                | Expected                                                              |
|---------------------------------------------------------------------|-----------------------------------------------------------------------|
| Audit approval with P1 question NOT-OK, no active intervention      | Plan created with intervention_id=NULL, priority='P1'                 |
| Audit-report overlay shows it                                       | `questionFlags[q]` lists the plan with `campaignName='Mandatory P1 Plan'` |

> **NOTE** Mandatory P1 auto-creation is an outstanding piece — the listener currently only instantiates plans for matched-intervention scope. Track as follow-up #M-PLAN-001.

---

# Tier 3 — UI flows + edge cases (Browser-heavy)

## T3.1 — Audit list + create UI walk

| Step                                                                       | Expected                                                            |
|----------------------------------------------------------------------------|---------------------------------------------------------------------|
| Login as KIA_SALES_DEPT_HEAD, navigate to /audits                          | List shows existing audits with status pill + completion bar        |
| Click "New Audit", fill name + checksheetId + dates                        | Submit lands; success message; redirect to /audits                  |
| New audit appears in the list                                              | Visible after redirect                                              |
| Submit with empty name                                                     | Disabled button, no submission                                      |

## T3.2 — Intervention create three-mode picker walk

| Step                                                                                 | Expected                                                              |
|--------------------------------------------------------------------------------------|-----------------------------------------------------------------------|
| Open /interventions/new                                                              | Three-section form visible                                            |
| Pick an audit; question count appears                                                | "All" mode shows full count                                           |
| Toggle to By Section, click 2 zones                                                  | questionCount() updates to the union of those zones' questions        |
| Toggle to Manual, check 5 questions                                                  | questionCount() = 5                                                   |
| Toggle targetingMode = BY_REGION, enter regionId                                     | Form valid                                                            |
| Toggle targetingMode = MANUAL, enter aa ids                                          | Form valid                                                            |
| Click Save & Activate                                                                | Calls createDraft then activate; redirect to /interventions          |
| Activation conflict (force by overlapping with existing active intervention)         | UI shows the 409 error                                                |

## T3.3 — Dealer Principal "My Plans" walk

| Step                                                                       | Expected                                                              |
|----------------------------------------------------------------------------|-----------------------------------------------------------------------|
| Login as KIA_DEALER_PRINCIPAL_001, navigate to /my-plans                   | Lists ~10 plans (per V100.004 seed)                                   |
| Click "Acknowledge" on a PENDING plan                                      | Status flips to IN_PROGRESS in-place                                  |
| Plan with reinspectionUserChecksheetIds.length > 0                         | Re-inspections summary chip visible                                   |

## T3.4 — Audit-report multi-intervention chain (visual)

Pre-condition: three interventions touched UC 81's q 197, with an OK → NotOK → OK chain.

| Step                                                                                           | Expected                                                              |
|------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------|
| Open /audit-report/81                                                                          | Header: "was X% → +N pts" chip visible                                |
| Locate q 197 row                                                                               | Three RE-AUDIT chip blocks stacked under the original answer          |
| First RE-AUDIT chip                                                                            | Green band (OK), names intervention A                                 |
| Second RE-AUDIT chip                                                                           | Red band (NotOK), names intervention B                                |
| Third RE-AUDIT chip                                                                            | Green band (OK), names intervention C                                 |
| All three timestamps in chronological order                                                    | answeredAt ascending                                                  |

## T3.5 — Mobile re-inspection wave (browser-driven via ionic serve)

| Step                                                                                | Expected                                                                            |
|-------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| Login as KIA_DEMO_AUDITOR_001                                                       | My Assignments shows both audit and intervention assignments                        |
| Tap an intervention assignment                                                      | Same /question screens as audit assignments                                         |
| Submit answers                                                                      | UC pointing at intervention_assignment_id created                                   |
| Approval (separate user, same flow)                                                 | Plan completion eval fires; if all OK → plan COMPLETED                              |

## T3.0 — Browser equivalent of T1.0 (PLACEHOLDER — implementation deferred)

The T1.0 chain driven through the actual UI instead of HTTP-only. Same
fixtures (test-admin / test-validator / test-approver / test-operator-A/B/C
/ test-dp), same chain, same assertions — but every step goes through
the rendered Angular pages so we catch UI regressions the API tests
can't see.

**Steps (browser):**
1. Login as test-admin → `/audits/new` → fill name + checksheetId + dates → submit.
2. Use the API to attach 5 audit_assignments (no UI for this yet — covered
   by the GitLab "Audit assignment-attach UI" follow-up).
3. Open a second browser context as test-operator-A → fill UC through
   `/question` screens → submit.
4. Third context as test-validator → validate. Fourth as test-approver
   → approve.
5. Repeat steps 3-4 for two more UCs.
6. Back to test-admin → `/interventions/new` → toggle through three
   modes → save & activate.
7. Login as test-dp → `/my-plans` → click Acknowledge.
8. Run re-inspection waves through the same `/question` screens as
   step 3 (operator's My Assignments now lists intervention plans too).
9. Approve re-inspections via test-validator + test-approver.
10. Login as test-admin → `/main-dashboard` → assert the campaign-summary
    panel shows non-zero numbers.
11. Click into `/audit-report/{ucId}` → assert the "was X% → +N pts" chip
    is visible and the per-question chain chips render.

**Performance contract:** 5-8 minutes total. Allowed to run nightly /
on-demand instead of every PR while we stabilise selectors. Browser
runs against the same backend the CLI tests hit (no Spring boot in
Playwright — Spring runs alongside).

**Status: not yet implemented.** Tracked at GitLab issue (see Followups).
Implementation lifts on the existing Playwright harness; a deferred
follow-up because the CLI lifecycle covers the data correctness — this
test is purely the UI complement.

---

# Out of scope (deferred)

- Stress / load testing (Tier 4 — not part of CI gating)
- Mobile-native (Capacitor) build smoke; only ionic-serve covered
- Photo upload regression on intervention UCs (covered by audit photo tests, infrastructure is shared)
- Non-Kia tenant overlays (covered by tenant onboarding doc + dedicated overlay test plan)

---

# Followups

- **M-PLAN-001** — Mandatory P1 auto-creation (T2.7) is documented as an
  outstanding feature, not currently shipped. File a GitLab issue.
- **TEST-PERF-001** — once tests are implemented, profile the suite. If
  total CLI runtime > 60s, parallelize fixtures more aggressively or move
  long tests to Tier 3.
