# V1.28 production-deployment hardening

## Why

The V1.28 schema-collapse migration (3 tables → 1 `inspections`) was
authored against the dev DB (222 inspection rows). External code review
caught operational issues that won't surface on dev but will bite at
real production scale and during a real rolling deploy. They need to be
addressed before this migration runs on UAT/prod.

## Blockers

1. **Maintenance window required, not yet documented.** V1.28 drops
   `user_checksheets`, `audit_assignments`, `intervention_assignments`.
   Once V1.28 commits, any old-code instance behind a load balancer that
   still references those tables will return 500s. Document the
   maintenance-window requirement in the deploy runbook AND in the V1.28
   migration header. Decide explicitly: rolling deploy is **not safe**
   for this migration — production must take a maintenance window.

2. **Rollback path missing.** §7 of V1.28 drops the `_legacy_uc_id`,
   `_legacy_aa_id`, `_legacy_ia_id` temp columns. Once that runs, the
   pre-collapse identity is gone — there's no way to reconstruct which
   UC mapped to which inspection. Either:
   - Keep the `_legacy_*_id` columns for one deploy cycle (drop them in
     a follow-up V1.30 once the deploy is judged successful), OR
   - Add a parallel rollback SQL file that recreates the three tables
     and copies data back from the inspections rows.

3. **`orphan_answers` verification runs after `DROP TABLE`.** §8's hard-
   fail can no longer compare backfilled rows against source rows
   because §6 has already dropped the sources. Move row-count assertions
   ABOVE §6.

## Production-scale warnings

4. **Indexes built non-CONCURRENTLY.** §1's 10 b-tree index creates hold
   ACCESS EXCLUSIVE for the build duration — fine on 222 rows, minutes
   each on millions of rows, all serial, all blocking writes. Move
   indexes to a follow-up migration that uses CREATE INDEX CONCURRENTLY
   (which Flyway can't run in a transaction; needs `outOfOrder` or a
   separate non-Flyway runner).

5. **Child-table FK migrations rewrite tables under ACCESS EXCLUSIVE.**
   §4's per-child `ADD COLUMN inspection_id` + `ALTER COLUMN ... SET
   NOT NULL` + `ADD CONSTRAINT FOREIGN KEY` pattern blocks writes for
   each table's full scan. Eleven tables, sequential. Cumulative blocking
   could be 10+ minutes on a tenant with millions of rows. Mitigation:
   `ADD CONSTRAINT ... NOT VALID` + `VALIDATE CONSTRAINT` separately.

6. **Backfill UPDATE without index on `_legacy_*_id`.** §4's
   `UPDATE child SET inspection_id = i.id FROM inspections i WHERE
   i._legacy_uc_id = child.user_checksheet_id` does a sequential scan
   of inspections per child row. Add `CREATE INDEX ON
   inspections(_legacy_uc_id)` immediately after §2's INSERTs.

7. **Backfill + drops in one transaction.** A failure in §4 row 9 of 11
   rolls back §2/§3's INSERTs; on retry, BIGSERIAL inspections.id has
   advanced, breaking idempotency for any external consumer that
   captured an id mid-flight. Split V1.28 into V1.28a (backfill) and
   V1.28b (drops).

8. **AA-side multi-row dedupe missing.** §3 hard-fails on multi-wave IAs
   but there's no equivalent guard for multiple active UCs on the same
   AA. V1.26 supposedly fixed this but if a tenant escapes that fix the
   §2b INSERTs hit `uk_inspections_audit_loc`. Add the dedupe guard.

9. **Hibernate-named FK drops assumed.** §4's
   `DROP CONSTRAINT IF EXISTS fk_user_checksheet_answers_user_checksheet_id_user_checksheets_`
   assumes the exact name Hibernate generated on the dev box. If a UAT
   environment was bootstrapped at a different Hibernate version, the
   IF EXISTS makes the DROP a no-op and then the column drop fails. Use
   the V1.29 pattern (DO block introspecting `pg_constraint`).

10. **V1.29 not scoped to current_schema.** The DO block matches `relname
    = 'refresh_token'` across all schemas. If a tenant schema also has a
    `refresh_token` table, the constraint drop runs against both. Add
    `cls.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname =
    current_schema())`.

11. **V1.29 missing dedupe pre-check.** `ADD CONSTRAINT UNIQUE(user_id,
    device_type)` will abort if any tenant `refresh_token` table has
    duplicates (which the broken @OneToOne path may have created — that's
    exactly what V1.29 fixes!). Add a hard-fail/dedupe block before line
    45.

## Acceptance

- [ ] Maintenance-window requirement documented in V1.28 header + deploy
  runbook.
- [ ] Rollback path: either preserve `_legacy_*_id` for one cycle OR ship
  a rollback SQL alongside.
- [ ] §8 verification moved above §6 drops.
- [ ] Production-scale lock concerns either fixed in-migration OR
  addressed by a documented maintenance-window plan with measured time
  estimate from a production-sized snapshot.
- [ ] V1.29 schema scoping + dedupe pre-check added.

## Source

External code review of V1.28 + V1.29, 2026-05-10 (smartcomply branch
`feat/improvement-campaigns`).
