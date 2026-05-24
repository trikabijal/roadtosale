# SmartComply Project Handoff Summary

## Scope

This repository, `smartcomply`, is the backend. It has two sibling/child frontend repositories that depend on it:

- `../smartcomply-angular` — web frontend
- `../auditpro-mobile-app` — mobile frontend

Current reviewed branch in the backend repo:

- `feat/improvement-campaigns`

This file is a session handoff for future Codex/code-review work.

## What the system does

SmartComply is an audit/compliance platform.

Core business flow:

1. Admins define checksheets/templates with question structure, scoring, roles, validators, approvers, etc.
2. Audits are created for locations/dealers/organizational units.
3. Audit assignments are created for operators/users at auditee locations.
4. Operators fill inspections (formerly split across audit assignments, user checksheets, and intervention reinspection rows).
5. Submitted inspections go through validation and approval.
6. Approved audits can trigger improvement campaigns / interventions.
7. Interventions create follow-up plans for targeted locations and tracked failing questions.
8. Reinspection happens against those plans, and BI/reporting computes latest/current compliance state.

## Current architecture direction

The branch `feat/improvement-campaigns` contains a major architectural change around **V1.28**:

- migration: `src/main/resources/db/migration/V1.28__collapse_inspections.sql`
- goal: collapse older audit/reinspection row models into a unified `inspections` table

After the collapse:

- one `Inspection` row represents either:
  - an audit inspection (`kind = AUDIT`)
  - an intervention plan/reinspection (`kind = INTERVENTION`)
- current status lives on that inspection row
- intervention “reinspection” is intended to reuse the same row in place rather than creating separate wave rows

This is the load-bearing change in the branch.

## Main backend areas reviewed

### 1. Migration / collapse

Primary file:

- `src/main/resources/db/migration/V1.28__collapse_inspections.sql`

Why it matters:

- creates the new `inspections` table
- backfills legacy audit rows
- backfills intervention rows
- repoints child tables to `inspection_id`
- rewires intervention targets/questions
- adds hard-fail guards for ambiguous backfill conditions

### 2. BI / analytics

Primary file:

- `src/main/java/com/checkSheet/service/AuditServiceImpl.java`

Critical function:

- `scopeCte()`

Why it matters:

- this is the heart of current/latest compliance reporting
- it reconstructs latest winning answers across approved audit + intervention inspection rows
- many higher-level BI endpoints depend on this logic

### 3. Intervention plan creation

Primary file:

- `src/main/java/com/checkSheet/service/InterventionAssignmentServiceImpl.java`

Critical function:

- `instantiateForApprovedAudit()`

Why it matters:

- creates intervention plans after approved audits
- now operates in the collapsed model where plans are inspection rows
- must remain idempotent and must select the correct failing/tracked questions

### 4. Lifecycle tests

Primary file:

- `src/test/java/com/checkSheet/audit/InterventionLifecycleE2ETest.java`

Important variants:

- T1.0-A
- T1.0-B
- T1.0-C

Why they matter:

- these are the canonical end-to-end correctness chain for timing-sensitive intervention behavior
- they test runtime behavior under different ordering of approval vs campaign creation/activation

## Current mental model of the product

### Audits

- An audit is defined centrally.
- Audit inspections are assigned to locations/operators.
- Operators answer checksheet questions.
- Inspection progresses through:
  - `ASSIGNED`
  - `IN_PROGRESS`
  - `SUBMITTED`
  - `VALIDATED`
  - `APPROVED`
  - `DECLINED` (intended new-state model)

### Interventions / improvement campaigns

- An intervention belongs to an audit.
- It targets a subset of auditee locations based on rules/selection.
- It scopes to a subset of questions/categories.
- When an audit inspection is approved, failing questions can instantiate an intervention plan for that location.
- The plan is now stored as an `INTERVENTION` inspection row.
- Reinspection is supposed to happen against that same plan row.

### BI / reporting

- Reporting is based on a “latest relevant approved answer wins” model.
- For a given audit/location/question, the system considers:
  - approved audit inspection answers
  - approved intervention inspection answers tied back to the same audit
- The reporting layer tries to compute current effective compliance from that chain.

## Strong findings from the latest review

### Still concerning

1. `InterventionServiceImpl.toDTO()` still appears to count dead legacy plan statuses like `COMPLETED` / `NON_COMPLIANT` instead of the post-collapse status model.
2. Validation/approval decline flows still appear to write old statuses like `INVALIDATED` / `NOT_APPROVED` while the V1.28 table constraint only allows the new inspection status set.
3. `scopeCte()` looks logically cleaner, but BI remains expensive because latest-wins logic is reconstructed repeatedly across multiple endpoints/queries.
4. Indexing added in V1.28 looks only partially aligned with actual hot query shapes; likely adequate for small/medium tenants, questionable at scale.
5. DTO/read models still carry pre-collapse semantics such as “reinspection waves” even though the architecture now wants a single reused plan row.
6. Some read surfaces remain visibly incomplete/stubbed, especially in the improvement overlay path.

### Less concerning than before

1. The architectural direction is better than the earlier pre-collapse model.
2. Late activation / backfill for already-approved audits looks conceptually correct.
3. The newer T1.0 lifecycle invariants are moving in the right direction.

## Recommended review priorities for a new session

If another Codex session continues the work, the highest-value next steps are:

1. **Performance/index review**
   - inspect every native SQL path that uses or duplicates `scopeCte()`
   - map likely planner pressure
   - propose concrete composite/partial indexes

2. **State-model cleanup review**
   - search for all legacy statuses:
     - `COMPLETED`
     - `NON_COMPLIANT`
     - `INVALIDATED`
     - `NOT_APPROVED`
   - confirm whether every caller/read model is aligned with V1.28 semantics

3. **Lifecycle/read-model coherence**
   - check whether intervention DTOs still expose fake/legacy fields
   - verify whether “approval time” vs “submitted time” is being conflated

4. **Test sufficiency**
   - verify no plans are created for unapproved targeted locations
   - verify tracked-question set equals exact failing-question intersection
   - consider whether migration/backfill correctness has any dedicated regression test at all

## Important files

- `src/main/resources/db/migration/V1.28__collapse_inspections.sql`
- `src/main/java/com/checkSheet/service/AuditServiceImpl.java`
- `src/main/java/com/checkSheet/service/InterventionServiceImpl.java`
- `src/main/java/com/checkSheet/service/InterventionAssignmentServiceImpl.java`
- `src/main/java/com/checkSheet/service/UserChecksheetServiceImpl.java`
- `src/main/java/com/checkSheet/service/UserChecksheetValidationServiceImpl.java`
- `src/main/java/com/checkSheet/service/UserChecksheetApprovalServiceImpl.java`
- `src/main/java/com/checkSheet/repository/UserChecksheetRepository.java`
- `src/test/java/com/checkSheet/audit/InterventionLifecycleE2ETest.java`
- `docs/test-plan-intervention.md`

## Practical note for the next reviewer

Do not assume the remaining risk is “feature correctness only.”

The deeper issues now are:

- semantic consistency after the collapse
- reporting cost under load
- whether read models and summaries still think in the old architecture
- whether migration preserved business meaning, not just row existence
