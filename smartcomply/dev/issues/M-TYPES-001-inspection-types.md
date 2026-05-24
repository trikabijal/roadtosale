# M-TYPES-001: Type widening on Inspection fields

## Why

Two fields on `Inspection` are typed narrower than the column. Both
inherited from the legacy UserChecksheet entity (pre-V1.28). They work
in practice but are silent-data-loss risks.

## What

1. **`submissionVersion: Byte` vs column `SMALLINT`.** Java Byte is 8-bit
   (-128..127); Postgres SMALLINT is 16-bit. A UC that goes through
   >127 decline-resubmit cycles overflows. Unlikely in practice but a
   correctness risk.

2. **`waitingUserIds: List<Long>` vs column `INTEGER[]`.** Long is 64-bit;
   INTEGER is 32-bit. Hibernate auto-converts. User ids are 32-bit safe
   today (max ~thousands). If user-id sequence ever exceeds 2^31 we'd
   silently truncate.

## Why this is a follow-up, not part of V1.28

Both type-widenings cascade through 5+ services and DTOs that pass
these values around. Fixing during V1.28 would have forced a much
larger change across UserChecksheetValidationServiceImpl,
UserChecksheetApprovalServiceImpl, ApprovalDTO, ValidationDTO, etc.
Out of scope for the schema-collapse PR.

## Acceptance

- [ ] `Inspection.submissionVersion` widened to `Short`.
- [ ] All consumers (services + DTOs) pass `Short`.
- [ ] `Inspection.waitingUserIds` either widened to `List<Integer>` and
      column kept as `INTEGER[]`, OR widened to `List<Long>` and column
      ALTERED to `BIGINT[]` (decide based on user-id growth projection).
- [ ] All consumers updated.

## Source

External code review of V1.28, 2026-05-10.
