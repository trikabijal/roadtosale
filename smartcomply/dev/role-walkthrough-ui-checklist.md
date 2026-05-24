# Role-by-role UI walkthrough — manual click-through checklist

This is the manual companion to `dev/role-walkthrough.py`. The script
exercises every backend endpoint each role hits; the checklist below
exercises every **screen** they actually see. The two together = a
real E2E pass.

Run order: backend must be up on :8089, FE on `ng serve` (port 4200).
Budget: ~45 min total for a clean run.

All passwords below are `12345678` for the demo seed users.

---

## 1. DEPT_ADMIN — `KIA_SALES_DEPT_HEAD`

Login screen → enter credentials → land on default landing page.

| # | Screen / action | Expected | Watch out for |
|---|---|---|---|
| 1.1 | National BI dashboard | Band chart (~60% green, 30% amber, 10% red), "What's Failing" with EV & Sustainability on top, Red Dealers list | If pills are gray-only or numbers are 0 across the board, the `audit_signal` CTE / scope query is broken |
| 1.2 | Drill into Region (click any region) | Same panels filtered to that region — South should show EV-heavy failures, North branding-heavy | If the table doesn't filter, route/param wiring is off |
| 1.3 | Drill into Dealership | Per-location roll-up + worst-location callout | locationCount per row should be sensible |
| 1.4 | Drill into Location | Single-site view + score trend + top-failing checkpoints | latest audit link should open the audit-report |
| 1.5 | Click an audit-report link from the location view | Full audit-report screen renders with location header + score + per-question cards | If header shows "Audit #N" instead of dealer name, the `userChecksheetMeta` payload didn't load |
| 1.6 | Audit list (left nav) | Two audits shown; "FY26 H1 Kia Showroom Audit" with 190 / 147 / etc counts | If `totalLocations` column is blank → field rename regression |
| 1.7 | Audit detail (click the audit) | List of 190 assignments, each row with location + operator + status | userChecksheetStatus="NOT_STARTED" for ASSIGNED, "APPROVED" for done |
| 1.8 | Intervention (Campaign) list | Empty page is OK in the demo DB (no interventions yet) | If the page errors, the InterventionDTO shape is wrong |
| 1.9 | Logout | Returns to login screen | — |

## 2. DATA_VALIDATOR — `KIA_SALES_AUDIT_DATA_VALIDATOR`

| # | Screen / action | Expected | Watch out for |
|---|---|---|---|
| 2.1 | Login → land on validator inbox / list (whichever the route defaults to) | List of inspections in SUBMITTED state awaiting validation | If empty, demo DB has no submitted inspections — that's OK |
| 2.2 | Open the validation history for any inspection (audit-report → validation tab, or direct route if available) | Validator history table renders with previous comments | If the table is blank, the `getUserChecksheetValidation` response shape changed |
| 2.3 | (If a SUBMITTED inspection exists) Validate → enter remarks → submit | Toast: "User checksheet validation added successfully" | If the inspection drops out of the list, status flipped correctly; if it sticks around with the same status, the validation update path is broken |
| 2.4 | (If a SUBMITTED inspection exists) Decline → enter remarks → submit | Inspection status flips to `DECLINED` (or shows as gray pill on submissions table) | This is the decline-semantics fix from D2 — pre-fix it'd go to IN_PROGRESS |

## 3. DATA_APPROVER — `KIA_SALES_AUDIT_DATA_APPROVER`

| # | Screen / action | Expected | Watch out for |
|---|---|---|---|
| 3.1 | Login → approver inbox | List of VALIDATED inspections (likely empty in demo DB) | — |
| 3.2 | Approval history view for any inspection | History table renders with status=APPROVED on approved rows | `getUserChecksheetApproval` shape match |
| 3.3 | (If a VALIDATED inspection exists) Approve | Status → APPROVED, audit drops from inbox | — |
| 3.4 | (If a VALIDATED inspection exists) Decline | Status → DECLINED | Same as 2.4 — pre-fix this would go to IN_PROGRESS |

## 4. DEALER_PRINCIPAL — `KIA_DEALER_PRINCIPAL_001`

| # | Screen / action | Expected | Watch out for |
|---|---|---|---|
| 4.1 | Login → My Plans page | Either the plans list (if seed has plans assigned to this DP) or "No plans yet" | If you see a backend error, the V1.30 columns aren't there |
| 4.2 | (If a plan exists) Plan row shows pill: "Awaiting Acknowledgement" gray pill | This is the new D2/Task-1 design — was previously hidden | — |
| 4.3 | Click "Acknowledge Plan" CTA | Toast confirms; pill flips to "Acknowledged" blue; CTA disappears | If the button errors with 501, the V1.30 deploy didn't pick up — V1.30 migration must have run |
| 4.4 | (If a plan is in IN_PROGRESS / SUBMITTED / VALIDATED) Plan shows amber pill "Re-inspection in progress" | — | — |
| 4.5 | (If a plan reaches APPROVED) Plan shows green pill "Resolved" | — | — |

## 5. OPERATOR (mobile-only really, but the web inbox exists too) — `KIA_DEMO_AUDITOR_001`

Mobile flow — run from the mobile app or capacitor preview:

| # | Step | Expected | Watch out for |
|---|---|---|---|
| 5.1 | Mobile login `deviceType: APP` | Lands on dashboard | If 422 about device type, V1.29 migration didn't run |
| 5.2 | Tap "My Audits" | 2 assignments shown (Panikoili, Pollachi) | If empty, `myAssignments` SQL is filtering wrong |
| 5.3 | Tap one → start audit | Question pages load, first 30 questions of the checksheet template | If "checksheet not found", `getUserChecksheetWithAnswers` shape changed |
| 5.4 | Answer a question OK + add photo | Save proceeds; photo uploads | — |
| 5.5 | Mark another question Not-OK + add photo | Same path; AI assessment kicks in (if Gemini key set) | — |
| 5.6 | Navigate back to dashboard | Assignment shows progress (X / Y questions answered) | progressPct calc |
| 5.7 | Tap "Submit for validation" | Status flips, audit drops from operator's queue | UC moves to SUBMITTED |
| 5.8 | (Re-inspection scenario, only if intervention plans exist for this operator) | A kind=INTERVENTION assignment appears in the list with location label | Backend D5 routing kicks in here |

---

## Result tracking

```
[ ] 1.1   [ ] 1.2   [ ] 1.3   [ ] 1.4   [ ] 1.5   [ ] 1.6   [ ] 1.7   [ ] 1.8   [ ] 1.9
[ ] 2.1   [ ] 2.2   [ ] 2.3   [ ] 2.4
[ ] 3.1   [ ] 3.2   [ ] 3.3   [ ] 3.4
[ ] 4.1   [ ] 4.2   [ ] 4.3   [ ] 4.4   [ ] 4.5
[ ] 5.1   [ ] 5.2   [ ] 5.3   [ ] 5.4   [ ] 5.5   [ ] 5.6   [ ] 5.7   [ ] 5.8
```

If anything fails: capture the network tab response (status + body) + the
JS console error if any. File against the relevant endpoint in the
overnight confidence report's decision table.

## Pre-flight (run before starting clicks)

```bash
# Verify backend is up + V1.30 applied + API shapes still match expectations
python3 dev/role-walkthrough.py
```

22/22 checks must pass before the manual UI walkthrough — if the script
fails, the UI almost certainly will too on the same endpoint.
