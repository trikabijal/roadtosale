# Spec 1 — Self-evaluated audit

**Status:** Proposed
**Date:** 2026-05-13

---

## 1. Functional requirement

An audit campaign can be marked as **self-evaluated**. When this flag is on:

- The dealer principal assigned to a location for that audit can fill in the audit themselves — answer questions, upload photos, submit.
- The audit creation/edit screen exposes a "Self-evaluated audit" toggle. The audit list shows a self-evaluated badge on those rows.
- The mobile app surfaces self-evaluated audit assignments in the dealer principal's queue, alongside the auditor's queue.
- The auditor and the dealer principal can both write to the same inspection — mixed authorship is allowed. Who did what is recorded by the per-artifact audit trail (see Spec 2).
- The downstream lifecycle is unchanged: the validator validates, the approver approves. The dealer principal cannot validate or approve their own audit.

---

## 2. Schema changes

| Table | Change |
|---|---|
| `audits` | Add `is_self_evaluated BOOLEAN NOT NULL DEFAULT FALSE` |

Existing audits remain `is_self_evaluated = false`. No data migration needed.

---

## 3. High-level API

- The audit-create / audit-edit endpoints carry the new flag.
- The write endpoints on the inspection (answers, photos, status) extend their permission check: an action is allowed when the actor is the assigned operator **OR** (the audit is self-evaluated **AND** the actor is the assigned dealer principal).
- The "my assignments" endpoint returns assignments where the user is the dealer principal of a self-evaluated audit, in addition to the existing operator path.

---

## 4. Changes from existing API

| Endpoint | Change |
|---|---|
| `POST /api/audit/createAudit` | Accept `isSelfEvaluated` in the request body. Default `false` if missing. |
| `GET /api/audit/list` | Include `isSelfEvaluated` in each row. |
| `GET /api/audit/{auditId}` | Include `isSelfEvaluated` in the response. |
| `POST /api/userChecksheet/createOrUpdate` | Permission: `userId == inspection.operatorId` OR (`audit.isSelfEvaluated && userId == inspection.dealerPrincipalId`). |
| `POST /api/userChecksheet/createOrUpdateUserChksAns` | Same permission rule as above. |
| `POST /api/userChecksheet/createUserChksAnsFile` | Same permission rule as above. |
| `GET /api/audit/myAssignments` | Add a second branch: also return assignments where `currentUserId == dealerPrincipalId` AND `audit.isSelfEvaluated`. |

---

## 5. Other changes

- **Frontend (smartcomply-angular):**
  - Add a "Self-evaluated audit" toggle to the audit create/edit form.
  - Show a small badge on self-evaluated rows in the audit list and audit-detail page.
- **Mobile (auditpro-mobile-app):** no new screens. The existing "my assignments" queue and audit-fill flow work as-is — the server returns more assignments to dealer principal users, and the existing permission errors are gone for them on self-evaluated audits.
