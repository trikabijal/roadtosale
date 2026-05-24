# V1.28 backfill correctness — fixture-based test against pre-V1.28 state

## Why

The InterventionLifecycleE2ETest cases (T1.0-A/B/C) create fresh
V1.28-native data and exercise the runtime intervention pipeline. They
DON'T exercise the migration's backfill from pre-V1.28 state — exactly
where the highest-risk defects sit.

Found by external code review of branch `feat/improvement-campaigns`,
2026-05-10. Two HIGH-severity findings (the IAT/IAQ identity-mapping
bug fixed in commit 400ed34) were latent for the same reason: dev DB
happened to have no soft-deleted historical UCs, so the bug never
materialised in any test.

## What

A test (or test class) that:

1. Resets a clean DB to a pre-V1.28 state — applies V1.18..V1.27 only.
2. Seeds representative pre-V1.28 fixtures that exercise the edge
   cases V1.28 can mis-handle. At minimum:
   - An `audit_assignment` with one ACTIVE UC plus one or more
     soft-deleted historical UCs (V1.26 dedupe edge).
   - An `intervention_assignment` with one ACTIVE UC plus soft-deleted
     historical waves.
   - A UC with status `INVALIDATED` (validator decline path).
   - A UC with status `NOT_APPROVED` (approver decline path).
   - At least one row in `intervention_assignment_targets` and
     `intervention_assignment_questions` so the §5 remap runs.
3. Runs V1.28.
4. Asserts post-state correctness:
   - Every IAT row's `inspection_id` points at the ACTIVE inspection
     for its legacy `audit_assignment_id` (not a soft-deleted one).
   - Every IAQ row's `inspection_id` points at the ACTIVE inspection
     for its legacy `intervention_assignment_id`.
   - Counts: total inspections == sum(active+deleted UCs across all
     three legacy tables) ± the §2a/§3a "no UC at all" rows.
   - INVALIDATED/NOT_APPROVED legacy rows mapped to DECLINED
     (post-decline-state-semantics fix; was previously IN_PROGRESS).
   - The §5.0 hard-fail aborts cleanly when seeded with an
     intentionally ambiguous AA (multiple active inspections sharing
     legacy id).
   - **Full row-shape / count conservation** across the 11 rewritten
     child tables (answers, answer_files, validations, _history,
     approvals, _history, judgements, judgement_files, matrix_answers,
     trace_values, gen_field_values). For each:
     - Pre-migration `count(*) WHERE deleted_at IS NULL`
     - Post-migration `count(*) WHERE deleted_at IS NULL`
     - Must match. Reviewer round 3 noted §5.0's hard-fail block only
       covers identity remap (no orphaned rows); it does NOT prove that
       no rows were dropped or duplicated by the column rename in §4.

## Acceptance

- [ ] Test class created under `src/test/java/com/checkSheet/audit/`
      (e.g. `V128MigrationE2ETest`).
- [ ] Uses Testcontainers Postgres OR a dedicated test DB so it can
      apply migrations from V1.18 forward without polluting the
      shared dev DB.
- [ ] All 5 fixture scenarios above asserted.
- [ ] Runs in CI (gating). Total runtime ≤ 30s for the class.

## Out of scope

- Performance / lock-duration testing on production-sized data
  (covered by issue #20).
- Frontend-driven scenarios (the fixture test is backend-only).

## Source

External code review of V1.28 commit `cb68567`, 2026-05-10. Reviewer
quote: "the canonical lifecycle tests do not exercise the migration
risk at all. They create fresh V1.28-native data, so they are useful
for post-collapse runtime behavior, but they do not validate backfill
correctness from a real pre-V1.28 state — which is exactly where the
highest-risk defect now sits."
