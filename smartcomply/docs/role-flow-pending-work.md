# Role-flow pending work — what to build + what to fix

> Companion to `docs/role-flow-test-plan.md` (the spec) and the
> `smartcomply-angular/qc/src/roles/` Playwright suite (the
> implementation).
>
> Two categories of pending work surfaced by the test suite. Both
> categories are tracked **in the test code** so the list shrinks
> automatically as items ship: a test marked `@gap` or `test.fixme(true)`
> shows up as **skipped** in any test run, with its reason printed
> inline. When the gap closes, the `test.fixme()` line gets removed
> and the test starts running for real.

This doc is what to share with the team when assigning work — each
item has the spec source (`role-flow-test-plan.md` §...), what to
build, who's affected, and where the corresponding test lives.

---

## Category A — Design gaps (15)

Features described in `docs/role-flows.md` that **haven't been built**
in the SPA yet. These tests are `test.fixme()`'d until the screen,
route, CTA, or filter exists.

How to see them: `cd smartcomply-angular && npm run e2e:gaps`.

### A1. Auditor — `/my-audits` queue replaces generic dashboard landing
- **Spec section:** role-flows.md §1 "Today's gap"
- **Test:** `qc/src/roles/01-auditor.spec.ts:41`
- **What to build:** Mobile auditor lands on a queue of their open
  assignments instead of a generic dashboard. Two stacked lists: **In
  Progress** (resume here) and **Assigned** (start next). Each card:
  dealership + city + % complete + days until target.
- **Backend ready?** Yes — `GET /api/audit/myAssignments` already
  returns the data shape needed.
- **Affected roles:** Auditor (Role 1).

### A2. Audit Validator — `/inbox/audits-to-validate` queue
- **Spec section:** role-flows.md §2 "Today's gap"
- **Test:** `qc/src/roles/02-audit-validator.spec.ts:56`
- **What to build:** Status-scoped inbox showing inspections where
  `status=SUBMITTED`. Columns: dealership · city · operator · submitted
  date · # NOT-OK answers · # photos. Sidebar count badge.
- **Backend ready?** Existing audit-detail endpoint surfaces these;
  needs a filtered list endpoint OR FE filters audit-detail client-side.
- **Affected roles:** Audit Validator (Role 2).

### A3. Audit Approver — `/inbox/audits-to-approve` queue
- **Spec section:** role-flows.md §3 "Today's gap"
- **Test:** `qc/src/roles/03-audit-approver.spec.ts:43`
- **What to build:** Same shape as A2 but filters `status=VALIDATED`.
- **Affected roles:** Audit Approver (Role 3).

### A4. Manager — "New intervention from here" CTA on dealer/region BI
- **Spec section:** role-flows.md §4 "Today's gap"
- **Test:** `qc/src/roles/04-manager.spec.ts:41`
- **What to build:** Contextual button on dealer-dashboard and
  regional-dashboard that deep-links to the intervention form with
  `auditId` + scope pre-filled. Today the manager has to navigate to
  `/interventions/new` and re-enter the audit + scope.
- **Backend ready?** Yes — `/intervention-assignment/createDraft`
  accepts `auditId` + scoping.
- **Affected roles:** Manager (Role 4).

### A5. Manager — region picker for multi-region managers
- **Spec section:** role-flows.md §4 "Lands on"
- **Test:** `qc/src/roles/04-manager.spec.ts:63`
- **What to build:** If a manager owns 2+ regions, show a picker where
  the national breadcrumb would otherwise sit. Today multi-region
  managers default to the first region.
- **Affected roles:** Manager (Role 4).

### A6. Dealer Principal — `/my-plans` renamed to `/my-dealership`
- **Spec section:** role-flows.md §5 "Today's gap"
- **Test:** `qc/src/roles/05-dealer-principal.spec.ts:52`
- **What to build:** Route rename + page repositioning. The principal's
  landing should be their dealership's full picture, not just the ack
  list. Existing `/my-plans` content becomes one of two sections on the
  new page.
- **Affected roles:** Dealer Principal (Role 5).

### A7. Dealer Principal — audits-of-my-dealership section
- **Spec section:** role-flows.md §5 "(a) My audits"
- **Test:** `qc/src/roles/05-dealer-principal.spec.ts:67`
- **What to build:** Add a top section to the dealer-principal landing
  showing all audit submissions at any of their dealership's locations.
  Each row: location · period · score · "View report" link to the
  existing `/audit-report/:ucId` route (no new screen needed).
- **Backend ready?** Existing audit-report endpoints; needs a
  dealership-scoped audits list (`GET /api/audit/...?auditeeId=...`) OR
  the FE filters from existing data.
- **Affected roles:** Dealer Principal (Role 5).
- **Constraint:** the audit list must be server-side-filtered to the
  principal's auditee only (not just hidden in the UI).

### A8. Builder — unified `/campaigns` route with Audits | Interventions tabs
- **Spec section:** role-flows.md §7 "Today's gap"
- **Test:** `qc/src/roles/07-builder.spec.ts:64`
- **What to build:** Replace two separate pages (`/audits`,
  `/interventions`) with one `/campaigns` page that has two tabs. Each
  tab shows the existing list + the existing "+ New Campaign" CTA. The
  current routes can redirect to the tabbed page.
- **Affected roles:** Builder (Role 7); read-only for Manager, HoDD,
  Principal.

### A9. Builder — Audit Detail page (`/campaigns/audits/:auditId`)
- **Spec section:** role-flows.md §7 "Shared screen: Audit Detail"
- **Test:** `qc/src/roles/07-builder.spec.ts:83`
- **What to build:** A new page with the 3 tabs: **Locations** (the
  assignment list), **Interventions** (filtered by this audit),
  **BI summary** (the existing MainDashboard scoped to this audit).
  Quick-stats strip at the top + page-level CTAs ("+ Add locations",
  "+ New intervention from this audit", "Close audit").
- **Backend ready?** Mostly. `GET /api/audit/{id}` returns audit +
  assignments; the per-audit intervention list needs an endpoint or
  client-side filter.
- **Affected roles:** Builder (write), Manager / HoDD / Principal (read).

### A10. Builder — "+ New intervention from this audit" CTA
- **Spec section:** role-flows.md §7 "Cross-creation"
- **Test:** `qc/src/roles/07-builder.spec.ts:103`
- **What to build:** Deep-link button on each audit row in the
  campaigns list (and on the audit-detail page) that opens the
  intervention form with `auditId` pre-filled. The question picker
  already filters out questions claimed by other active campaigns, so
  this is purely a deep-link CTA + URL parameter handling.
- **Affected roles:** Builder (Role 7).

### A11. Template Creator — unified `/templates` lifecycle landing
- **Spec section:** role-flows.md §8 "Today's gap"
- **Test:** `qc/src/roles/08-template-creator.spec.ts:69`
- **What to build:** One page listing all templates with status pills
  (Draft / Submitted / Validated / Approved). Single "+ New Template"
  CTA. Each row clickable to edit (Draft) or view (others). Today the
  flow is scattered across submenu items (create, browse, revise).
- **Affected roles:** Template Creator (Role 8).

### A12. Template Validator — `/inbox/templates-to-validate` queue
- **Spec section:** role-flows.md §9 "Today's gap"
- **Test:** `qc/src/roles/09-template-validator.spec.ts:26`
- **What to build:** Status-scoped queue (`status=SUBMITTED`). Same
  shape as A2 but for templates. Each row: template name · creator ·
  date submitted · # questions.
- **Affected roles:** Template Validator (Role 9).

### A13. Template Validator — status filter exposes SUBMITTED
- **Spec section:** role-flows.md §9 "Secondary"
- **Test:** `qc/src/roles/09-template-validator.spec.ts:59`
- **What to build:** The existing templates list status filter today
  exposes only "New | Implemented". Add SUBMITTED, VALIDATED, APPROVED
  options so the validator can scope their view without a dedicated
  queue page (and so the dedicated queue from A12 can reuse the same
  filter under the hood).
- **Affected roles:** Template Validator (Role 9); also helps A12.

### A14. Template Approver — `/inbox/templates-to-approve` queue
- **Spec section:** role-flows.md §10 "Today's gap"
- **Test:** `qc/src/roles/10-template-approver.spec.ts:26`
- **What to build:** Same as A12 but `status=VALIDATED`.
- **Affected roles:** Template Approver (Role 10).

### A15. Template Approver — status filter exposes VALIDATED
- **Spec section:** role-flows.md §10 "Secondary"
- **Test:** `qc/src/roles/10-template-approver.spec.ts:59`
- **What to build:** Mirror of A13 for the VALIDATED filter option.
- **Affected roles:** Template Approver (Role 10).

---

## Category B — Discovery fixmes (12)

Backend behaviors the tests **discovered** that don't match
`role-flow-test-plan.md`. Each is either a real backend bug, a
contract drift between doc and code, or a missing enforcement that
the test surfaced.

How to see them: `grep -rn "test.fixme(true," qc/src/roles/`.

### B1. `/intervention-assignment/myPlans` empty right after activate
- **Spec section:** role-flow-test-plan.md §4 L2-PRINCIPAL
- **Tests:** `l2-dealer-principal.spec.ts:30` (primary), `l3-reinspect.spec.ts:30`,
  `l4-principal-wrong.spec.ts:29` (blocked on same issue)
- **What was expected:** After creating an intervention, activating it,
  and assigning a plan to a dealer principal, `GET /api/intervention-assignment/myPlans`
  as that principal should return the plan immediately.
- **What actually happens:** Returns `[]` right after activation.
  Likely either (a) plan creation is async and lags behind the activate
  response, (b) plans are scope-filtered by inspection status we're not
  satisfying (e.g. only surface when the parent inspection has a
  particular state), or (c) the plan is created but bound to a
  different field than the test queries.
- **Fix needed:** Either make the plan creation synchronous (recommend),
  or document the lag and provide a polling endpoint, or fix the scope
  filter. Whatever the answer, update `role-flow-test-plan.md` §4
  L2-PRINCIPAL to reflect the actual semantics.
- **Affected:** L2-PRINCIPAL, L3-REINSPECT, L4-PRINCIPAL-WRONG, L5-INTERV-DELTA.

### B2. Backend doesn't enforce `remarks` required on INVALIDATED
- **Spec section:** role-flow-test-plan.md §4 L4-DECLINE-NOCOMMENT
- **Test:** `l4-decline-nocomment.spec.ts:27`
- **What was expected:** POST validation with `status=INVALIDATED` and
  empty `remarks` returns HTTP 400 with a "comment required" message.
- **What actually happens:** Server accepts the request with HTTP 200,
  the inspection moves to INVALIDATED with empty remarks.
- **Fix needed:** Tighten the validator in `UserChecksheetValidationServiceImpl`
  (or wherever) to reject `INVALIDATED` without non-empty `remarks`.
  This is a real backend gap — role-flows.md "Cross-role conventions"
  explicitly says "Decline always requires a comment. No silent
  rejections; the bounced-to role always sees why."

### B3. Backend returns 422 on invalid state transition (spec said 400/409)
- **Spec section:** role-flow-test-plan.md §4 L4-INVALID-TRANS
- **Test:** `l4-invalid-trans.spec.ts:74`
- **What was expected:** Attempting to APPROVE an inspection that's at
  SUBMITTED (skipping VALIDATED) returns 400 or 409.
- **What actually happens:** Returns 422 ("Unprocessable Entity").
- **Fix needed:** Either change the backend to use the documented
  status code, or update the test plan + this doc to make 422 the
  contract. **Recommend:** keep 422 (it's semantically correct —
  unprocessable due to state) and update the docs.

### B4. Auditor (OPERATOR role) can validate own submission
- **Spec section:** role-flow-test-plan.md §4 L4-AUDITOR-NOVAL
- **Test:** `l4-auditor-noval.spec.ts:69` (conditional fixme — fires
  only on unexpected pass)
- **What was expected:** OPERATOR role lacks CHKSHEET_DATA_VALIDATE
  permission, so POST `/userChecksheetValidation/addUserChecksheetValidation`
  as operator returns 401/403.
- **What actually happens:** Test triggers the conditional fixme
  branch — backend allows the call. If it actually rejected, the spec
  passes; if it returned 200, this fires. Need to confirm one way or
  the other on UAT.
- **Fix needed:** Verify the role-permission mapping; tighten if
  OPERATOR shouldn't have data-validate.

### B5. DATA_VALIDATOR can approve (not just validate)
- **Spec section:** role-flow-test-plan.md §4 L4-VAL-NOAPP
- **Test:** `l4-val-noapp.spec.ts:69` (conditional fixme)
- **What was expected:** Validator role (CHKSHEET_DATA_VALIDATE) lacks
  CHKSHEET_DATA_APPROVE, so the approval call returns 401/403.
- **What actually happens:** Currently the seed user
  `KIA_SALES_AUDIT_DATA_VALIDATOR` has the DEPT_ADMIN role, which has
  BOTH validate and approve permissions. So the conditional fixme
  fires on every run.
- **Fix needed:** Either (a) split the seed user's role into a
  validator-only role (recommend — gives realistic role boundaries),
  or (b) accept that DEPT_ADMIN can do both and document this in the
  spec.

### B6. Cross-tenant write succeeds
- **Spec section:** role-flow-test-plan.md §4 L4-CROSS-TENANT
- **Test:** `l4-cross-tenant.spec.ts:64`
- **What was expected:** Operator B cannot POST to
  `/userChecksheet/createOrUpdate` for operator A's inspection. Should
  be 403.
- **What actually happens:** Flaky between runs — sometimes the
  cross-tenant write is accepted under parallel load. This needs
  isolated investigation to determine if it's a real bug or a test
  race.
- **Fix needed:** Confirm the assignment-owner check on the
  createOrUpdate write path. If it's truly missing under some
  conditions, tighten the check.

### B7. Duplicate ACTIVE intervention on same question allowed
- **Spec section:** role-flow-test-plan.md §4 L4-DUP-INTERV
- **Test:** `l4-dup-interv.spec.ts:85` (conditional fixme)
- **What was expected:** Two interventions on the same audit + same
  question, both ACTIVE → activate of the 2nd returns 409 (conflict).
- **What actually happens:** Unknown — the conditional fixme fires if
  the activation succeeds. Worth confirming the actual behavior
  manually.
- **Fix needed:** Verify the conflict detection logic in
  `InterventionServiceImpl.activate`. role-flows.md §7 says "The form's
  question picker already filters out questions claimed by other
  active/draft interventions" — but the BACKEND should also reject
  this at activate time, not just hide it in the FE.

### B8. `auditedLocations` counts ASSIGNED, not APPROVED
- **Spec section:** role-flow-test-plan.md §4 L5-RECENCY (documented finding)
- **Test:** `l5-bi-recency.spec.ts:32` (passing — asserts invariants
  only; the doc/code drift surfaced via console.log)
- **What was expected:** Per `role-flows.md §6` ("Recency panel counts
  locations including unaudited ones"), and the architecture doc CTE
  description, `auditedLocations` should = locations with at least one
  APPROVED inspection.
- **What actually happens:** Counts locations with ANY assignment
  (including ASSIGNED, IN_PROGRESS, etc.).
- **Fix needed:** Reconcile doc vs implementation. **Recommend:** fix
  the doc — the API's current behavior ("audited" = assigned in scope)
  is reasonable for the recency panel. Update role-flows.md and
  architecture.md to match. OR fix the CTE to filter on `status =
  'APPROVED'`.

### B9. avgScore = null when inspections have no scored answers
- **Spec section:** role-flow-test-plan.md §4 L5-AVG, L5-BAND-3, L5-RED-DEALERS
- **Test:** `l5-avg.spec.ts:31`, `l5-band-3.spec.ts:33`, `l5-red-dealers.spec.ts:32`
  (all assertions softened to invariants; full BI verification deferred)
- **What was expected:** 0-score inspections should land in `redCount`
  (zero is below the red threshold) and contribute to `avgScore`.
- **What actually happens:** Inspections with 0 answers are excluded
  from the band/avg calculation entirely. `avgScore = null`,
  `redCount = 0`.
- **Fix needed:** Either (a) include 0-answer inspections as red (they
  ARE failing — no positive evidence), or (b) extend the test builder
  to bind answers so we can craft specific scores. **Recommend:** (b),
  since (a) might over-pollute the BI. Builder extension blocked on
  fetching the template's question-result-ids — see B10.

### B10. No public API to list a template's questions
- **Spec section:** role-flow-test-plan.md §3.2 (deferred template create)
- **Tests:** affects L5 BI scoring tests (B9 above) + L2-TPLCREATE +
  L2-TPLVAL-APP (`l2-template-creator.spec.ts:23`, `l2-template-validator-approver.spec.ts:23`)
- **What was expected:** Tests should be able to call something like
  `GET /api/checksheet/{id}/questions` to learn the question-result-ids
  needed for answer binding. Today the builder workarounds use a `psql`
  read for known-stable seed templates.
- **Fix needed:** Add a `GET /api/checksheet/{id}/questions` endpoint
  (or expose the data in `/checksheet/getChecksheetDetail`) that
  returns `[{questionId, questionResultId, options: [...]}]`. This
  unblocks B9 + answer-binding + L5 full coverage.

### B11. Full template create blocked by layered role check
- **Spec section:** role-flow-test-plan.md §3.2 (deferred), §6 gap A11
- **Tests:** `l2-template-creator.spec.ts:23`, `l2-template-validator-approver.spec.ts:23`
- **What was expected:** A test user with `SUBDEPT_ADMIN` role +
  `CHECKSHEET_MANAGEMENT_DETAIL_CREATE` permission can call POST
  `/checksheet/createChecksheet` and get back an id.
- **What actually happens:** Returns 403 "You do not have permission
  to create or edit checksheets". The check is a layered combination
  of `permissionService.hasPermission(...)` + department-scoped
  preparer role match that's hard to satisfy from outside.
- **Fix needed:** Loosen the check OR document precisely what
  user-role-department combination satisfies it. Then the test
  fixture can spin up the right user and L2-TPLCREATE passes.

### B12. DECLINED → SUBMITTED re-submit path missing in builder
- **Spec section:** role-flow-test-plan.md §4 L3-DECLINE-BOUNCE
- **Test:** `l3-decline-bounce.spec.ts` (re-submit half marked fixme)
- **What was expected:** `progressInspection({to: 'DECLINED'})` then
  re-driving back through IN_PROGRESS → SUBMITTED should work via the
  builder.
- **What actually happens:** The builder's `progressInspection` uses
  rank-based gating (only advances) and has no recovery path from
  DECLINED. The first half of the test (SUBMITTED → DECLINED) works;
  the re-submit half is fixme'd.
- **Fix needed:** Extend the builder with explicit
  `restartInspection(insp)` or treat DECLINED specially so subsequent
  progress calls can re-drive. Backend already supports the bounce
  (DECLINED is not terminal — see `role-flows.md §2` and `app architecture.md`
  "DECLINED bounces back to IN_PROGRESS").

---

## How this list shrinks

Each item in this doc maps 1:1 to a `test.fixme()` line in the
Playwright suite. When the work lands:

1. Open the spec file (`qc/src/roles/...`)
2. Remove the `test.fixme(true, '...')` line
3. Run `npm run e2e:roles` — the test executes for real now

The corresponding entry in this doc can be deleted in the same PR.
Either the test passes (great, ship it) or it fails for a real reason
(re-open with a new note).

This is how the spec, the test suite, and this pending-work doc stay
in lock-step.
