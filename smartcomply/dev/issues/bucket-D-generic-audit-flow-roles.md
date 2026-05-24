# Replace dept-admin / section-admin role model with generic audit-flow roles

## Why

Today the role model leaks specific organisational structures into our
core code:

- `DEPT_ADMIN` (Department Admin)
- Section heads with their own admin role
- `MasterDepartmentController`, `DepartmentController`, etc. — ~25 endpoints
  scaffolding department/section CRUD

These names assume a particular organisational shape (Department → Section).
Real customers use different terminology — Region, Vertical, Business
Unit, Cluster — and most don't have the two-level Department/Section
nesting we hardcoded.

The audit lifecycle has clean, generic roles built into it:

| Lifecycle role | Currently called |
|---|---|
| Audit campaign creator | DEPT_ADMIN (cross-cutting capability) |
| Template author | CHECKSHEET_PREPARER |
| Template validator | CHECKSHEET_VALIDATOR |
| Template approver | CHECKSHEET_APPROVER |
| Field auditor (mobile) | OPERATOR |
| Inspection answer validator | DATA_VALIDATOR |
| Inspection answer approver | DATA_APPROVER |
| BI consumer | DEPT_HEAD / DEPT_ADMIN (provisional — see below) |

## What

1. Define the canonical audit-flow role list above as the only roles
   the core knows about.
2. Move tenant-specific organisational labels (Department, Section,
   Region, Cluster, etc.) into a tenant-overlay vocabulary — same idea
   as the Kia "Checksheet → Audit / Section → Dealership" customer
   terminology overrides we already do in the FE.
3. Retire `DEPT_ADMIN` / `DEPT_HEAD` as core roles. The capabilities
   they currently hold (audit creation, BI viewing) get unbundled into
   the generic flow role list.
4. The BI viewer role specifically — currently the BI dashboards are
   gated to `DEPT_HEAD` and `DEPT_ADMIN`. Decide whether BI is its own
   role (`AUDIT_BI_VIEWER`) or whether every authenticated user with
   any audit-flow role sees it. **TBD until we look at customer
   org structures more carefully.**

## Acceptance

- [ ] Authoritative list of audit-flow roles documented in
      `docs/architecture.md`.
- [ ] All `@PreAuthorize` / authorization checks in the core code use
      only audit-flow roles.
- [ ] Department / Section concepts live in tenant configs only —
      core has no direct knowledge.
- [ ] Migration plan for existing role assignments (DEPT_ADMIN users
      get mapped to whichever new audit-flow roles they actually need).

## Impact

Big. Touches authorization, every controller's `@PreAuthorize`,
existing user-role data model, demo seed data, and the ~25-endpoint
Bucket D surface (DepartmentController, MasterDepartmentController,
RoleController, PermissionController). Estimated 2-3 days focused.

Defer until **after** the V1.28 schema collapse stabilises and the
authorization map is in (issue tracking those: #15, #16).

## Source

API audit conversation 2026-05-10. Bucket D in the four-bucket
classification.
