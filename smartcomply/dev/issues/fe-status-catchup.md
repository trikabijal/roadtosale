# FE catch-up — adopt V1.28 inspection statuses; remove backend translation shim

## Why

V1.28 collapsed `audit_assignments`, `intervention_assignments`, and
`user_checksheets` into a single `inspections` table with a unified
status enum: `ASSIGNED | IN_PROGRESS | SUBMITTED | VALIDATED |
APPROVED | DECLINED`.

The smartcomply-angular FE (`InterventionAssignment` TS interface,
`my-plans`, `campaign-detail`, `dealer-dashboard`, etc.) still keys
off pre-V1.28 strings: `PENDING | IN_PROGRESS | COMPLETED | NON_COMPLIANT`.

To unblock the V1.28 backend deploy without touching FE, a TEMPORARY
wire-level translation lives in
`InterventionAssignmentServiceImpl.translateStatusForFE()`:

| V1.28 status | Translated wire string |
|---|---|
| ASSIGNED | IN_PROGRESS (deliberately not PENDING — suppresses Acknowledge CTA, see §M-ACK-001) |
| IN_PROGRESS / SUBMITTED / VALIDATED / DECLINED | IN_PROGRESS |
| APPROVED | COMPLETED |

This issue tracks the FE work to adopt the real V1.28 strings and rip
out the translation.

## What

Update FE to read V1.28 statuses directly:

1. `src/app/services/intervention.service.ts` —
   `InterventionAssignment.status` type → `'ASSIGNED' | 'IN_PROGRESS' |
   'SUBMITTED' | 'VALIDATED' | 'APPROVED' | 'DECLINED'`. Remove
   `acknowledgedAt`, `acknowledgedByUserId`, `completedAt`,
   `closedAsNonCompliantAt`, `closureReason` if FE no longer renders them
   (they're never set by the backend post-V1.28).
2. `dealer-principal/my-plans/my-plans.component.html` — pill-color bindings:
   - `pill-amber` on IN_PROGRESS / SUBMITTED / VALIDATED / DECLINED / ASSIGNED
   - `pill-green` on APPROVED
   - Remove `[class.pill-red]="p.status === 'NON_COMPLIANT'"` (state deferred)
3. `my-plans.component.ts` sort — replace PENDING/NON_COMPLIANT/COMPLETED ranks
   with V1.28 equivalents.
4. `campaigns/campaign-detail/*` — same pill + sort + count updates.
   `completed = plans.filter(p => p.status === 'APPROVED')`.
5. `dashboard/dealer-dashboard/*` — same pill update.
6. `dashboard-overview/recent-submissions-table.component.ts` `getStatusBadgeClass`
   — add `'DECLINED': 'bg-danger'`. Optionally add `'ASSIGNED': 'bg-secondary'`.
7. **Acknowledge CTA in `my-plans`** — keep hidden until M-ACK-001 ships.
   Either remove the `<div *ngIf="p.status === 'PENDING'">` block entirely,
   or gate it behind a feature flag.

## Backend cleanup (after FE is shipped)

In `src/main/java/com/checkSheet/service/InterventionAssignmentServiceImpl.java`:
- Delete `translateStatusForFE()` (and the comment block above it).
- Replace the `.status(translateStatusForFE(ia.getStatus()))` and
  `.latestReinspectionStatus(translateStatusForFE(ia.getStatus()))`
  call sites with `.status(ia.getStatus())` /
  `.latestReinspectionStatus(ia.getStatus())`.
- Update `InterventionAssignmentDTO` field comment from
  `// PENDING | IN_PROGRESS | COMPLETED | NON_COMPLIANT` to
  `// ASSIGNED | IN_PROGRESS | SUBMITTED | VALIDATED | APPROVED | DECLINED`.

## Out of scope

- `Intervention.status` (DRAFT | ACTIVE | CLOSED) — unchanged by V1.28; FE
  matches; nothing to do.
- Mobile app (`auditpro-mobile-app`) — already V1.28-compatible; no changes
  needed there.
- Audit-template `Checksheet.status` (CREATE_TEMPLATE / CREATE_CONTENT /
  SUBMITTED_FOR_VALIDATE / VALIDATED / APPROVED / INVALIDATED / NOT_APPROVED) —
  separate enum, untouched by V1.28; FE refs are correct.

## Source

V1.28 schema collapse + `feat/improvement-campaigns` branch overnight
confidence audit, 2026-05-10. See
`dev/overnight-confidence-report.md` decision D1.
