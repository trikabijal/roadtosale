# Refresh architecture docs for the V1.28 schema collapse

## Why

V1.28 collapsed three runtime tables (`audit_assignments`,
`intervention_assignments`, `user_checksheets`) into a single
`inspections` table with a `kind` discriminator. CLAUDE.md was updated
in the V1.28 PR (the most critical doc since it drives Claude's
context). Other architecture docs still describe the pre-V1.28
three-table model and will mislead new contributors and future Claude
runs.

## Files needing updates

Found by an automated doc-drift scan against `feat/improvement-campaigns`:

### Stale architecture descriptions

- **`docs/system-overview.md`** lines 48–78 (entity diagram), 123/182/207
  (request bodies referencing auditAssignmentId/interventionAssignmentId),
  450/506/528 (SQL examples with old column names), 182/227/492 (status
  PENDING references).
- **`docs/architecture.md`** lines 18–19, 24–27 (three-table description),
  61 (endpoint contract), 947–962 (lifecycle diagram), 982–995 (event
  flows referencing audit_assignment_id/intervention_assignment_id),
  994–995 (PENDING/IN_PROGRESS reference).
- **`docs/audit-flow.md`** lines 3, 16, 18, 25, 190–191 — pre-V1.28 entity
  references throughout.
- **`AGENTS-backend.md`** lines 141–148, 166–167, 185 — entity glossary
  needs the kind-discriminator note.
- **`docs/api.md`** line 45 (auditAssignmentId in createOrUpdate
  contract — verify whether this is intentional backward-compat or
  needs updating).
- **`docs/changes-tldr.md`** line 10 — V1.27 mentioned, V1.28 not yet
  added.
- **`docs/database-analysis.md`** lines 67–83 — child-table FK column
  names changed user_checksheet_id → inspection_id.
- **`docs/scoring-system.md`** lines 243, 274, 278 — references to
  audit_assignment in score logic (cosmetic).

### Already done

- `CLAUDE.md` — updated in the V1.28 PR (domain model + audit_signal CTE
  + migration list).
- `docs/test-plan-intervention.md` — Revision 2026-05-10c added in the
  V1.28 PR.

## Acceptance

- [ ] Each file updated to the post-V1.28 model.
- [ ] Status enum references corrected (PENDING → ASSIGNED, COMPLETED →
      APPROVED for plan completion, NON_COMPLIANT noted as deferred per
      M-COMP-001).
- [ ] Pre-V1.28 sections preserved as historical context where
      educational; otherwise replaced.
- [ ] Run a final grep across `docs/` for `audit_assignment_id`,
      `intervention_assignment_id`, `user_checksheet_id` (column refs)
      and `AuditAssignment` / `InterventionAssignment` (entity-level)
      to confirm nothing's missed.

## Source

External code review of V1.28, 2026-05-10. Automated doc-drift agent
output covered every changed area.
