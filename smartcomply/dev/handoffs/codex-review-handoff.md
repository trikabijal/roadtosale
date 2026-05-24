# SmartComply Review Handoff

## Scope

This file is a review-only handoff for another Codex session.

Repository:

- `smartcomply` backend

Related sibling repos:

- `../smartcomply-angular`
- `../auditpro-mobile-app`

Reviewed branch:

- `feat/improvement-campaigns`

Primary architectural change under review:

- `src/main/resources/db/migration/V1.28__collapse_inspections.sql`

## Review posture

The branch is materially better than the earlier architecture, but it should not be treated as “done.”

The remaining risks are now concentrated in:

1. migration correctness and business-state preservation
2. BI/query performance under scale
3. residual pre-collapse semantics in DTOs/services/tests

## Strong findings

### 1. State model is still not fully migrated

Most important concern:

- some services/read models still think in the old status language

Observed examples:

- `InterventionServiceImpl.toDTO()` still counts `COMPLETED` / `NON_COMPLIANT`
- decline paths in validation/approval still write `INVALIDATED` / `NOT_APPROVED`
- V1.28 `inspections` constraint expects the new status family

Files:

- `src/main/java/com/checkSheet/service/InterventionServiceImpl.java`
- `src/main/java/com/checkSheet/service/UserChecksheetValidationServiceImpl.java`
- `src/main/java/com/checkSheet/service/UserChecksheetApprovalServiceImpl.java`
- `src/main/resources/db/migration/V1.28__collapse_inspections.sql`

Interpretation:

- the schema has moved farther than the service/read layer
- the collapse is real, but the semantics are not yet uniformly absorbed

### 2. Decline semantics are still weak

Important judgment:

- mapping legacy decline states to `IN_PROGRESS` is probably the wrong long-term semantic choice

Reason:

- it erases the distinction between:
  - never started
  - actively being worked
  - declined / sent back

Preferred direction:

- preserve `DECLINED` as current state
- move from `DECLINED` to `IN_PROGRESS` only on explicit reopen/edit
- if needed, add subtype/history-backed read semantics instead of flattening business meaning

### 3. BI correctness looks better than BI cost

Current view:

- `AuditServiceImpl.scopeCte()` looks logically plausible
- the bigger risk is performance, not obvious score math drift

Why:

- latest-wins reconstruction is expensive
- the same expensive rollup shape appears to be recomputed across multiple reporting endpoints
- index strategy looks only partially aligned with actual filter/join shapes

High-risk area:

- large-tenant latency and planner cost

Primary file:

- `src/main/java/com/checkSheet/service/AuditServiceImpl.java`

### 4. DTO/read surfaces still carry pre-collapse mental models

Important concern:

- architecture says “single reused intervention plan row”
- some DTO/service code still talks as if there are separate reinspection waves

Example risks:

- fake or misleading read fields
- approval/submission timestamps being conflated
- compatibility fields surviving longer than intended

Primary file:

- `src/main/java/com/checkSheet/service/InterventionAssignmentServiceImpl.java`

### 5. Overlay/read completeness is still suspect

Observed concern:

- some overlay response fields remain effectively stubbed/empty

Primary file:

- `src/main/java/com/checkSheet/service/AuditServiceImpl.java`

Interpretation:

- even if the collapse is structurally correct, not every read-side feature appears fully caught up

## Migration-specific conclusions

### What looks improved

- the newer hard-fail checks in §5.0 appear to cover the nastiest row-identity remap ambiguity for active rows
- this is a real improvement over the earlier version

### What is still not proven

1. full conservation of business meaning during backfill
2. full conservation of child-row correctness across every rewritten dependent table
3. whether decline-state collapse was the right semantic decision

### Practical conclusion

The migration may be relationally safer now, but it is not yet semantically beyond question.

## Behavior conclusions

### Late activation / already-approved audits

Current judgment:

- the branch now appears conceptually correct for “campaign created/activated after some audits are already approved”
- the lifecycle tests support that claim

Main concern left:

- not whether plans appear at all
- whether they appear with exactly the right scope and are described consistently by read models

## Test conclusions

### What is good

- T1.0-A/B/C are the right canonical runtime chain
- newer invariants around one active plan per `(intervention, location)` and in-place reinspection are useful

Primary file:

- `src/test/java/com/checkSheet/audit/InterventionLifecycleE2ETest.java`

### What is still missing

1. assert no plans are created for targeted locations whose audits were never approved
2. assert tracked-question set equals the exact intersection of:
   - original failing questions
   - intervention scope
3. dedicated regression coverage for migration/backfill correctness, not only V1.28-native runtime behavior

## Best next questions for a new Codex session

1. Which exact native queries duplicate `scopeCte()` work, and what is the likely planner cost of each?
2. Which composite or partial indexes would actually support the post-collapse BI access paths?
3. Where do old statuses still leak through service code, DTOs, tests, or frontend contracts?
4. Are any intervention summary/dashboard surfaces still reporting dead legacy concepts?
5. Is there any trustworthy automated test for pre-V1.28 migration/backfill correctness?

## Suggested next-pass focus

If another session continues, the most valuable narrow pass would be:

1. map every hot BI query
2. map current indexes against those query predicates and join keys
3. identify stale status semantics across code/tests/contracts
4. identify which read fields are now misleading after the collapse

## Short bottom line

The architecture is better.

The branch still looks vulnerable in four places:

1. stale status/read semantics
2. decline-state design
3. BI/query cost at scale
4. incomplete proof that migration preserved meaning, not just rows
