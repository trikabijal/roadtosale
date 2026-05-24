# Audit Pro -- End-to-End Code Flow Walkthroughs

This document traces every major user action through the codebase, from HTTP request to database operation and back, including side effects such as emails, notifications, and audit logging.

**Stack:** Spring Boot 3.1.4, Java 17, PostgreSQL, JWT (jjwt), AWS S3, Apache POI, Firebase Cloud Messaging, JavaMail.

---

## Table of Contents

**Base flows — browser to backend** (V1.27+ canonical flows)

- [B1. Login](#b1-login)
- [B2. List my audits (mobile auditor home)](#b2-list-my-audits-mobile-auditor-home)
- [B3. Start or resume an audit](#b3-start-or-resume-an-audit)
- [B4. Answer a question and attach a photo](#b4-answer-a-question-and-attach-a-photo)
- [B5. Submit an audit](#b5-submit-an-audit)
- [B6. Validate an audit (data validator)](#b6-validate-an-audit-data-validator)
- [B7. Approve an audit (data approver)](#b7-approve-an-audit-data-approver)
- [B8. Create an intervention (admin)](#b8-create-an-intervention-admin)
- [B9. Dealer Principal acknowledges plan](#b9-dealer-principal-acknowledges-plan)
- [B10. Re-inspection wave (operator)](#b10-re-inspection-wave-operator)
- [B11. View audit-report (any authorised role)](#b11-view-audit-report-any-authorised-role)

**UI-side companion references**

- [Workflow state machines](#workflow-state-machines) — template + user-checksheet lifecycles (legacy enums; V1.28 inspection enum is documented inline in B3–B7).
- [UI rendering reference](#ui-rendering-reference) — answer-type form controls, objective-type display, judgement values, checksheet structure, dashboard chart mappings.
- [Permission-based UI rules](#permission-based-ui-rules) — menu/page visibility per permission, role-based permission bundles, action buttons per status, SUPER_ADMIN override.

**Legacy flows** (pre-V1.24 generic checksheet pipeline, kept for reference)

1. [User Registration](#1-user-registration)
2. [User Login](#2-user-login)
3. [Token Refresh](#3-token-refresh)
4. [Checksheet Creation](#4-checksheet-creation)
5. [Checksheet Validation Flow](#5-checksheet-validation-flow)
6. [Checksheet Approval Flow](#6-checksheet-approval-flow)
7. [User Checksheet Fill](#7-user-checksheet-fill)
8. [Data Validation / Approval](#8-data-validation--approval)
9. [Dashboard Data Retrieval](#9-dashboard-data-retrieval)
10. [File Upload to S3 / Local Storage](#10-file-upload-to-s3--local-storage)
11. [Email / Notification Flow](#11-email--notification-flow)
12. [Permission Check Flow](#12-permission-check-flow)
13. [Excel Export](#13-excel-export)

---

# Base flows — browser to backend

This section is the canonical V1.27+ walk-through of how each major user
action travels from the browser (or mobile app) into the backend, what
gets touched in the database, and what comes back. Each flow includes
the actor, the trigger, the HTTP calls in order, the server-side work
(service method, tables touched, events published), the response shape,
and the failure paths.

See also:
- [`api.md`](./api.md) for the per-endpoint reference (request/response
  schemas, permission gates) and §"Mobile API flow (audit lifecycle)"
  for the mobile contract.
- [`system-overview.md`](./system-overview.md) §3 (audit lifecycle) and
  §4 (intervention lifecycle) for the conceptual model.

Vocabulary used here:
- **Operator** — the auditor walking the dealership floor; mobile app.
- **Data Validator / Data Approver** — second/third level review of an
  inspection's answers; web app.
- **Admin** — OEM HQ user who creates audits and interventions;
  permission gates `AUDIT_VIEW` + `INTERVENTION_MANAGE`; web app.
- **Dealer Principal (DP)** — the dealership owner who owns the
  improvement plan for their location; permission gate
  `INTERVENTION_ASSIGNMENT_ACKNOWLEDGE`; web app.
- **Inspection** — the V1.28 unified runtime row. `kind='AUDIT'` is the
  original audit fill; `kind='INTERVENTION'` is the per-dealer re-inspection
  plan/fill. Same table, same status enum.

All endpoints (except the `WHITE_LIST_URL` set in
`SecurityConfiguration`) require `Authorization: Bearer <jwt>` and
return the standard `ResponseDTO<T>` envelope (`{ status, message, data
}`).

---

## B1. Login

**Actor:** any (operator, validator, approver, admin, dealer principal).

**Trigger:** user enters username + password on the login screen
(`/login` for web, native auth screen for mobile).

### HTTP sequence

```
Browser / Mobile                        Backend
       │                                    │
       │  POST /api/user/login              │
       │  { username, password, deviceType }│
       │ ──────────────────────────────────▶│
       │                                    │  UserController.authenticate
       │                                    │  └─▶ UserServiceImpl.login
       │                                    │      ├─ handleLdapLogin if LDAP env + WEB
       │                                    │      └─ handleRegularLogin otherwise
       │                                    │        ├─ findByUsernameIgnoreCase
       │                                    │        ├─ passwordEncoder.matches
       │                                    │        ├─ JwtService.generateToken (HS256)
       │                                    │        ├─ RefreshTokenServiceImpl.createOrUpdate
       │                                    │        └─ permissions + menus resolved
       │                                    │
       │  200 { data: { accessToken,        │
       │            refreshToken, userId,   │
       │            permissions[],          │
       │            sideMenus[], ... } }    │
       │ ◀──────────────────────────────────│
```

### Endpoint

`POST /api/user/login` — whitelisted (no JWT required).

### Request body

```json
{ "username": "KIA_DEMO_AUDITOR_001", "password": "12345678", "deviceType": "APP" }
```

`deviceType` is `APP` for mobile, `WEB` for the Angular dashboard. The
backend uses it to (a) decide between LDAP and regular login when an
LDAP env is configured, and (b) key the refresh token row so a user can
be logged in from both APP and WEB simultaneously
(`refresh_token UNIQUE(user_id, device_type)` — see migration V1.29).

### Server-side

| Step | Code | DB |
|---|---|---|
| Resolve user | `UserRepository.findByUsernameIgnoreCase` | SELECT `users` |
| Rate-limit | `failLoginCount` + `resendOTPTime` on the User row | UPDATE `users.failLoginCount` |
| Verify password | `BCryptPasswordEncoder.matches` | — |
| Mint JWT | `JwtService.generateToken(user)` (HS256, embedded claims) | — |
| Store refresh | `RefreshTokenServiceImpl` upserts on `(user_id, device_type)` | INSERT/UPDATE `refresh_token` |
| Resolve permissions | `PermissionServiceImpl.getUserPermissions` | SELECT `user_role`, `role_permission`, `permissions` |
| Resolve menus | `UserServiceImpl.getSideMenus` | SELECT `side_menus` (filtered by perms) |

### Response

```json
{
  "status": true,
  "data": {
    "accessToken": "eyJhbGc…",
    "refreshToken": "…",
    "userId": 42,
    "username": "KIA_DEMO_AUDITOR_001",
    "permissions": ["CHECKSHEET_FILL_ANSWER", ...],
    "sideMenus": [{ "name": "My Audits", "path": "/my-audits", ... }],
    "deviceType": "APP"
  }
}
```

### What the UI does

- Mobile: stashes `accessToken` in secure storage, routes to **My
  Audits** which fires B2.
- Web: stashes in `sessionStorage`, builds the side-nav from
  `sideMenus`, routes to the role-default page (admin → audits list,
  DP → my-plans, operator-on-web → /my-audits).

### Failure paths

| Condition | HTTP | Body |
|---|---|---|
| Username unknown | 422 | `Invalid username or password` |
| Password wrong | 422 | same — by design, identical message |
| Account locked (3 failures within `resendOTPTime`) | 422 | `Account temporarily locked` |
| `deviceType` not `APP`/`WEB` | 422 | `Invalid deviceType` |
| LDAP unreachable when LDAP-required | 502 | `LDAP service unavailable` |

---

## B2. List my audits (mobile auditor home)

**Actor:** operator. Web operators see the same endpoint surfaced as a
list view; the mobile app is the primary consumer.

**Trigger:** auditor opens the mobile app **My Audits** tab, or pulls
to refresh.

### HTTP sequence

```
Mobile                                Backend
   │                                    │
   │  GET /api/audit/myAssignments      │
   │  Authorization: Bearer <jwt>       │
   │ ──────────────────────────────────▶│
   │                                    │  AuditController.myAssignments
   │                                    │  └─▶ AuditServiceImpl.getMyAssignments
   │                                    │      ├─ resolve current operator from SecurityContext
   │                                    │      └─ native SQL on `inspections`
   │                                    │         WHERE operator_user_id = :me
   │                                    │           AND deleted_at IS NULL
   │                                    │           AND audits.status = 'ACTIVE'
   │                                    │           AND inspections.status NOT IN ('APPROVED','VALIDATED')
   │                                    │         ORDER BY (IN_PROGRESS → ASSIGNED → others),
   │                                    │                  audit.end_date,
   │                                    │                  inspection.id
   │                                    │
   │  200 { data: [{ assignmentKind,    │
   │      assignmentId, auditId, …,     │
   │      ucId, ucStatus,               │
   │      answeredQuestions,            │
   │      totalQuestions }] }           │
   │ ◀──────────────────────────────────│
```

### Endpoint

`GET /api/audit/myAssignments`

### Server-side

One UNION-shaped native query against `inspections` that returns both
audit-kind (`assignmentKind='audit'`) and intervention-kind
(`assignmentKind='intervention'`) rows the operator owns. The
discriminator is `inspections.kind`; the audit context resolves directly
on AUDIT-kind rows and via `interventions.audit_id` on
INTERVENTION-kind rows.

`answered_questions` is computed inline from
`user_checksheet_answers WHERE inspection_id = ins.id AND judgement IS NOT NULL`,
so the mobile home screen can render a per-row progress badge without a
second call.

### Response shape (per row)

```
{
  "assignmentKind":  "audit" | "intervention",
  "assignmentId":    <inspection_id>,
  "auditId":         123,
  "auditName":       "FY26 H1 Kia Showroom Audit",
  "checksheetId":    7,
  "checksheetName":  "Kia Showroom Compliance v3",
  "locationId":      450,
  "locationAddress": "12 MG Road, Bangalore",
  "cityName":        "Bangalore",
  "auditeeId":       88,
  "auditeeName":     "Modi Kia",
  "ucId":            <inspection_id or null if status=ASSIGNED>,
  "ucStatus":        "IN_PROGRESS" | "ASSIGNED" | "DECLINED" | "SUBMITTED",
  "ucStartedAt":     "...",
  "totalQuestions":  55,
  "answeredQuestions": 12
}
```

### What the UI does

Mobile groups rows by status; IN_PROGRESS rows show "Resume", ASSIGNED
rows show "Start", DECLINED rows show "Resume — declined by validator".
Tapping a row fires B3.

### Failure paths

| Condition | HTTP |
|---|---|
| Not authenticated | 401 |
| User row deleted between JWT issue and call | 404 |

---

## B3. Start or resume an audit

**Actor:** operator.

**Trigger:** operator taps an assignment card in **My Audits**.

### HTTP sequence

```
Mobile                                Backend
   │                                    │
   │  POST /api/userChecksheet/         │
   │       createOrUpdate               │
   │  [ { auditAssignmentId: 42,        │
   │      status: "IN_PROGRESS",        │
   │      shift: "First",               │
   │      startedAt: "2026-05-12T...",  │
   │      submissionVersion: 0 } ]      │
   │ ──────────────────────────────────▶│
   │                                    │  UserChecksheetController.createOrUpdateUserChecksheets
   │                                    │  └─▶ UserChecksheetServiceImpl.createOrUpdateUserChecksheet
   │                                    │      ├─ resolve current user
   │                                    │      ├─ if DTO.id present → UPDATE path
   │                                    │      ├─ else if interventionAssignmentId → re-inspection (B10)
   │                                    │      └─ else CREATE-from-audit path:
   │                                    │         ├─ find Inspection by auditAssignmentId
   │                                    │         ├─ verify operator owns it
   │                                    │         ├─ check audit's checksheet template is APPROVED
   │                                    │         ├─ check attempt count vs template max
   │                                    │         ├─ require current status='ASSIGNED'
   │                                    │         ├─ assertLegalOperatorTransition(ASSIGNED → IN_PROGRESS)
   │                                    │         ├─ flip status, stamp operator_user_id + started_at
   │                                    │         └─ SAVE the SAME inspection row (no new INSERT)
   │                                    │
   │  200 { data: [{ id, auditId,       │
   │     auditName, checksheetId,       │
   │     checksheetName,                │
   │     auditeeLocationId, … }] }      │
   │ ◀──────────────────────────────────│
```

### Endpoint

`POST /api/userChecksheet/createOrUpdate` — the body is a list even for
one inspection.

### Key V1.28 detail

Post-V1.28 there is no separate `audit_assignments` table — the
`Inspection` row IS the assignment. "Starting" the audit means
transitioning that row's status from `ASSIGNED → IN_PROGRESS` in place;
no new row is inserted. The mobile API still says `auditAssignmentId`
for backward compat, but server-side it resolves to an inspection id.

A compat shim (line 442 in `UserChecksheetServiceImpl`) handles the case
where the mobile app sends `auditAssignmentId` for what is actually an
intervention-kind inspection — it swaps the field to
`interventionAssignmentId` so the re-inspection branch (B10) runs.

### Server-side

| Step | Tables touched |
|---|---|
| Load inspection by id | SELECT `inspections` |
| Verify ownership (`operator_user_id`) | — |
| Validate checksheet template status | SELECT `checksheets` |
| Validate attempt count | — |
| Legal transition check (ASSIGNED → IN_PROGRESS) | — |
| Status flip + `startedAt` stamp | UPDATE `inspections` |

### Response (decorated DTO)

```
{
  "id":                <inspection_id>,
  "inspectionId":      <inspection_id>,
  "auditAssignmentId": <inspection_id>,   // same — for FE compat
  "auditId":           123,
  "auditName":         "FY26 H1 Kia Showroom Audit",
  "checksheetId":      7,
  "checksheetName":    "Kia Showroom Compliance v3",
  "auditeeLocationId": 450,
  "startedAt":         "2026-05-12T10:00:00.000+05:30",
  "status":            "IN_PROGRESS"
}
```

Then the mobile app navigates to `/audit/:ucId/question/0` and starts
calling B4 per answer.

### Failure paths

| Condition | HTTP | Body |
|---|---|---|
| `auditAssignmentId` missing on create | 422 | `Please provide auditAssignmentId or interventionAssignmentId` |
| Audit-assignment id invalid / deleted | 422 | `Invalid auditAssignmentId` / `Audit assignment has been deleted` |
| Caller is not the assigned operator | 403 | `This audit assignment is not assigned to you` |
| Inspection already past ASSIGNED on create | 422 | `Inspection is already started (status=…); use update path` |
| Checksheet template not APPROVED | 422 | `Audit's checksheet template is not APPROVED` |
| Exceeds `frequencyOfFreqOfChk` ceiling | 422 | `You can not attempt more than defined maximum attempt.` |
| Illegal transition (e.g. SUBMITTED → IN_PROGRESS) | 403 | `Illegal status transition from … to …` |

---

## B4. Answer a question and attach a photo

**Actor:** operator.

**Trigger:** operator answers a question on the `/question/:idx`
screen — picks an option, types a number, or taps "Attach photo".

### HTTP sequence

```
Mobile                                Backend
   │                                    │
   │  POST /api/userChecksheet/         │
   │       createOrUpdateUserChksAns    │
   │  [ { userChecksheetId: 555,        │
   │      chksQuestionResultId: 12,     │
   │      judgement: 1,                 │
   │      chksQuestionRsltOptionId: 9,  │
   │      answeredAt: "…" } ]           │
   │ ──────────────────────────────────▶│
   │                                    │  UserChecksheetServiceImpl.createOrUpdateUserChksAns
   │                                    │  ├─ for each DTO:
   │                                    │  │  ├─ require inspectionId + chksQuestionResultId
   │                                    │  │  ├─ assertOperatorCanEditInspection(inspectionId)
   │                                    │  │  │   (owner + status ∈ {ASSIGNED,IN_PROGRESS,DECLINED})
   │                                    │  │  ├─ INSERT/UPDATE user_checksheet_answers
   │                                    │  │  └─ INSERT/UPDATE usr_chksheet_ans_judgements
   │                                    │
   │  200 { data: [{ id: 9001, … }] }   │
   │ ◀──────────────────────────────────│
   │                                    │
   │  POST /api/userChecksheet/         │
   │       createUserChksAnsFile        │
   │  (multipart)                       │
   │  userChecksheetAnswerId=9001       │
   │  file=<binary>                     │
   │ ──────────────────────────────────▶│
   │                                    │  UserChecksheetServiceImpl.createUserChksAnsFile
   │                                    │  ├─ require CHECKSHEET_FILL_ANSWER permission
   │                                    │  ├─ load answer, derive parent inspection
   │                                    │  ├─ assertOperatorCanEditInspection(parent.id)
   │                                    │  ├─ validateUpload(file)   ← 413/415 BEFORE persist
   │                                    │  ├─ hash SHA-256 + thumbnail
   │                                    │  └─ FileStorageUtil.storeFile + INSERT user_checksheet_answer_files
   │                                    │
   │  200 { data: { fileId, url, … } }  │
   │ ◀──────────────────────────────────│
```

### Endpoints

- `POST /api/userChecksheet/createOrUpdateUserChksAns` — body is a list.
- `POST /api/userChecksheet/createUserChksAnsFile` — multipart, one
  file per call.

### Ownership + state gates

Both endpoints run through `assertOperatorCanEditInspection(inspectionId)`,
which enforces:
1. Inspection exists.
2. `inspections.operator_user_id` equals the JWT subject.
3. `inspections.status` ∈ {`ASSIGNED`, `IN_PROGRESS`, `DECLINED`}.
   `SUBMITTED`/`VALIDATED`/`APPROVED` are rejected — operator must not
   silently mutate an inspection already in review.

These are the two checks called out in the audit-report PR review (M1).
Without them, operator A could `POST` `userChecksheetId=<B's UC>` and
mutate B's answers, or could keep writing answers after submission.

### Upload validation (issue #9)

`validateUpload` runs **before** the file is hashed, thumbnailed, or
written to disk:

| Check | Throws | Reason |
|---|---|---|
| `file.size > app.upload.max-size-mb` | 413 PAYLOAD_TOO_LARGE | descriptive |
| `Content-Type ∉ app.upload.allowed-content-types` | 415 UNSUPPORTED_MEDIA_TYPE | descriptive |

Defaults: 25 MB max, `image/jpeg,image/png,image/webp`. The multipart
parser layer (`spring.servlet.multipart.max-file-size`) is a hard
ceiling and may return a raw Spring error if exceeded — these
application-level checks just give a friendly message first.

### Tables touched (per answer)

- `user_checksheet_answers` — one row per `(inspection_id, chks_question_result_id)`.
- `usr_chksheet_ans_judgements` — judgement remarks / metadata.
- `user_checksheet_answer_files` — one row per uploaded photo.
- File on disk: `data/UserChecksheetAnswerFile/<id>_<filename>` and
  thumbnail (image-only) in the same dir.

### Failure paths

| Condition | HTTP |
|---|---|
| Missing `inspectionId` or `chksQuestionResultId` | 422 |
| Not the inspection's owner | 403 (`You are not authorized to modify this audit`) |
| Inspection in SUBMITTED/VALIDATED/APPROVED | 403 (`Inspection is not in an editable state`) |
| File > size limit | 413 |
| Disallowed content-type | 415 |
| Caller lacks `CHECKSHEET_FILL_ANSWER` | 403 |
| `userChecksheetAnswerId` invalid | 422 |

### Optional: AI photo assessment

Kia's mobile flow optionally calls `POST /api/ai/assess` (multipart,
`photo + userChecksheetId + chksQuestionResultId`) right after upload
to get an LLM verdict — written to `ai_assessments`, surfaced later in
the BI's AI agreement panel.

---

## B5. Submit an audit

**Actor:** operator.

**Trigger:** operator taps **Submit** on the audit summary screen after
all required questions are answered.

### HTTP sequence

```
Mobile                                Backend
   │                                    │
   │  POST /api/userChecksheet/         │
   │       createOrUpdate               │
   │  [ { id: 555,                      │
   │      status: "SUBMITTED",          │
   │      submittedAt: "..." } ]        │
   │ ──────────────────────────────────▶│
   │                                    │  UserChecksheetServiceImpl.createOrUpdateUserChecksheet (UPDATE path)
   │                                    │  ├─ load Inspection by id
   │                                    │  ├─ ownership check
   │                                    │  ├─ assertLegalOperatorTransition(IN_PROGRESS → SUBMITTED)
   │                                    │  ├─ UPDATE inspections.status='SUBMITTED', submitted_at
   │                                    │  ├─ set waiting_user_ids = checksheet.dataValidatorUserIds
   │                                    │  └─ utilityService.sendEmail(validators, USER_CHKS_SUBMITTED)
   │                                    │
   │  200 { data: [{ … status:          │
   │           "SUBMITTED" … }] }       │
   │ ◀──────────────────────────────────│
```

### Submit body

Only the changed fields. V1.24 stopped the pre-V1.24 behaviour of
overwriting `startedAt`, `shift`, `submissionVersion` etc with nulls
when the client omitted them on update.

### Server-side

| Step | Tables / Side effects |
|---|---|
| Legal transition `IN_PROGRESS → SUBMITTED` | enforced in `assertLegalOperatorTransition` |
| Status flip | UPDATE `inspections` |
| Waiting list | UPDATE `inspections.waiting_user_ids` |
| Email | async — `EmailService.sendAsync` to validators using `USER_CHKS_SUBMITTED` template |

### Failure paths

| Condition | HTTP |
|---|---|
| Inspection not owned by caller | 403 |
| Inspection already SUBMITTED/VALIDATED/APPROVED | 403 (illegal transition) |
| `id` not found | 422 |

After this, B6 unblocks for the listed validators.

---

## B6. Validate an audit (data validator)

**Actor:** Data Validator (user listed in
`checksheets.data_validator_user_ids`).

**Trigger:** validator clicks **Validate** on an inspection's review
page on the web.

### HTTP sequence

```
Browser                               Backend
   │                                    │
   │  POST /api/userChecksheetValidation│
   │       /addUserChecksheetValidation │
   │  { inspectionId: 555,              │
   │    status: "VALIDATED",            │
   │    remarks: "All photos clear" }   │
   │ ──────────────────────────────────▶│
   │                                    │  UserChecksheetValidationServiceImpl.addUserChecksheetValidation
   │                                    │  ├─ require status='SUBMITTED' on the inspection
   │                                    │  ├─ check this validator hasn't already validated (history)
   │                                    │  ├─ INSERT/UPDATE user_checksheet_validations
   │                                    │  │     keyed by (inspection_id, validator_user_id)   ← V1.28 + V1.31 C1
   │                                    │  ├─ INSERT user_checksheet_validations_history (audit trail)
   │                                    │  └─ tally:
   │                                    │     ├─ if status='INVALIDATED' →
   │                                    │     │     inspections.status='DECLINED' (terminal-decline)
   │                                    │     └─ else if history.size >= dataValidatorUserIds.size →
   │                                    │           inspections.status='VALIDATED'
   │                                    │           waiting_user_ids = dataApproverUserIds
   │                                    │           sendEmail(approvers, USER_CHKS_VALIDATED)
   │                                    │
   │  200 { status: true }              │
   │ ◀──────────────────────────────────│
```

### Endpoint

`POST /api/userChecksheetValidation/addUserChecksheetValidation`

### Key V1.28 + V1.31 detail

Validation rows are keyed by `(inspection_id, validator_user_id)`, NOT
`(checksheet_id, validator_user_id)`. Pre-V1.28 the latter collided
when two inspections of the same template were reviewed by the same
validator — second validation silently overwrote the first. C1 fix
flipped the unique key. See `findByInspection_IdAndDataValidatorUserId_IdAndDeletedAtIsNull`.

### DECLINED state semantics

`INVALIDATED` is the validator's "decline" verdict; on the persisted
side it lands on `inspections.status='DECLINED'` (the V1.28 enum
collapses INVALIDATED+NOT_APPROVED into a single terminal-decline
state). Lineage is preserved on `user_checksheet_validations_history`.
The operator can reopen this row via `createOrUpdate` with
`status='IN_PROGRESS'` — `assertLegalOperatorTransition` allows
`DECLINED → IN_PROGRESS`.

### Failure paths

| Condition | HTTP |
|---|---|
| Inspection not in `SUBMITTED` | 422 |
| Caller already validated this inspection at this submission version | 422 |
| Missing remarks | 422 |
| Caller not authenticated | 401 |

---

## B7. Approve an audit (data approver)

**Actor:** Data Approver (user listed in
`checksheets.data_approver_user_ids`).

**Trigger:** approver clicks **Approve** on a validated inspection's
review page.

### HTTP sequence

```
Browser                               Backend
   │                                    │
   │  POST /api/userChecksheetApproval/ │
   │       addUserChecksheetApproval    │
   │  { inspectionId: 555,              │
   │    status: "APPROVED",             │
   │    remarks: "OK to publish" }      │
   │ ──────────────────────────────────▶│
   │                                    │  UserChecksheetApprovalServiceImpl.addUserChecksheetApproval
   │                                    │  ├─ require status='VALIDATED'
   │                                    │  ├─ check approver not already approved
   │                                    │  ├─ INSERT/UPDATE user_checksheet_approvals (keyed on inspection_id)
   │                                    │  ├─ INSERT user_checksheet_approvals_history
   │                                    │  └─ tally:
   │                                    │     ├─ NOT_APPROVED → inspections.status='DECLINED'
   │                                    │     └─ if history.size >= dataApproverUserIds.size →
   │                                    │           inspections.status='APPROVED'
   │                                    │           waiting_user_ids = []
   │                                    │           eventPublisher.publishEvent(
   │                                    │              UserChecksheetApprovedEvent(
   │                                    │                  inspectionId, auditId, locationId,
   │                                    │                  aaId (if kind=AUDIT),
   │                                    │                  iaId (if kind=INTERVENTION)))
   │                                    │           sendEmail alert / escalation per checksheet config
   │                                    │
   │  200 { status: true }              │
   │ ◀──────────────────────────────────│
   │                                    │
   │  (after-commit, async)             │
   │                                    │  InterventionInstantiationListener.onApproved
   │                                    │  ├─ load Inspection
   │                                    │  ├─ if aaId set → instantiateForApprovedAudit
   │                                    │  │     (creates one intervention plan per
   │                                    │  │      ACTIVE intervention whose question scope
   │                                    │  │      overlaps the failing answers; each plan
   │                                    │  │      runs in its OWN REQUIRES_NEW txn so a
   │                                    │  │      race-loss on the unique partial index
   │                                    │  │      doesn't roll back siblings)
   │                                    │  └─ if iaId set → evaluatePlanCompletion (B10 follow-up)
```

### Endpoint

`POST /api/userChecksheetApproval/addUserChecksheetApproval`

### Event hinge

When the last required approver approves, the service publishes a
`UserChecksheetApprovedEvent` on the Spring `ApplicationEventPublisher`.
`InterventionInstantiationListener` consumes it with `TransactionPhase.AFTER_COMMIT`
+ `@Async("interventionListenerExecutor")` — so:

- The DB transaction that wrote `APPROVED` must commit first; a rollback
  means no event delivery.
- Listener runs on its own thread pool so the approval response is not
  blocked.
- The listener catches `DataIntegrityViolationException` (from the
  `inspections` unique partial index on `(intervention_id, auditee_location_id)`
  WHERE `kind='INTERVENTION'`) and logs it as an idempotent skip — two
  threads racing to create the same plan is benign.

### What downstream sees

After commit + listener completion:
- BI's `audit_signal` CTE now includes this inspection (`status='APPROVED'`
  is the filter on the CTE).
- One `inspections` row of `kind='INTERVENTION'` exists per ACTIVE
  intervention whose questions overlap the failures (B9 picks up from
  here).

### Failure paths

| Condition | HTTP |
|---|---|
| Inspection not in `VALIDATED` | 422 (`User checksheet is not submitted for approval`) |
| Caller already approved this submission version | 422 |
| Missing remarks | 422 |
| Listener fails after commit | logged, swallowed — original 200 stands |

---

## B8. Create an intervention (admin)

**Actor:** admin with `INTERVENTION_MANAGE` and `AUDIT_VIEW`.

**Trigger:** admin clicks **+ New Intervention** on `/interventions`.

### HTTP sequence

```
Browser                               Backend
   │                                    │
   │  (1) on audit-select in the form   │
   │  GET /api/intervention/audit/      │
   │      {auditId}/claimedQuestions    │
   │ ──────────────────────────────────▶│
   │                                    │  InterventionServiceImpl.claimedQuestions
   │                                    │  └─▶ native SQL — every chks_question_id already
   │                                    │       targeted by an ACTIVE or DRAFT intervention
   │                                    │       on this audit, with the campaign that claims it
   │  200 { data: [{ chksQuestionId,    │
   │           interventionId,          │
   │           campaignName,            │
   │           status }] }              │
   │ ◀──────────────────────────────────│
   │                                    │
   │  (2) admin fills name / theme /    │
   │      priority / target_date /      │
   │      question-picker / targeting   │
   │  POST /api/intervention/createDraft│
   │  { name, theme, priority,          │
   │    auditId, targetDate,            │
   │    questionIds[], targetingMode,   │
   │    targetRegionId / targetIds[] }  │
   │ ──────────────────────────────────▶│
   │                                    │  InterventionServiceImpl.createDraft
   │                                    │  ├─ requireManage()
   │                                    │  ├─ validate name/priority/targetingMode
   │                                    │  ├─ load audit (must be ACTIVE)
   │                                    │  ├─ INSERT interventions (status='DRAFT')
   │                                    │  └─ INSERT intervention_questions[]
   │
   │  200 { data: { id, status:"DRAFT"} }
   │ ◀──────────────────────────────────│
   │                                    │
   │  (3) admin reviews + clicks        │
   │      Activate                      │
   │  POST /api/intervention/{id}/      │
   │       activate                     │
   │  { targetingMode, … }              │
   │ ──────────────────────────────────▶│
   │                                    │  InterventionController.activate
   │                                    │  ├─ previewActivationConflicts(id)
   │                                    │  │     if any → 409 with QuestionConflictDTO[]
   │                                    │  └─ activateWithTargeting
   │                                    │     ├─ require questions non-empty
   │                                    │     ├─ resolveTargetAssignments(audit, targeting)
   │                                    │     │     ALL / BY_REGION / MANUAL
   │                                    │     ├─ INSERT intervention_assignment_targets[]
   │                                    │     ├─ UPDATE interventions.status='ACTIVE',
   │                                    │     │   activated_at = now()
   │                                    │     └─ backfillPlansForExistingApprovals
   │                                    │           (runs instantiation logic for every
   │                                    │            already-APPROVED audit UC on a target —
   │                                    │            otherwise activating AFTER an audit
   │                                    │            wave finished would never create plans)
   │
   │  200 { data: { id, status:"ACTIVE"}}
   │ ◀──────────────────────────────────│
```

### Endpoints

- `GET /api/intervention/audit/{auditId}/claimedQuestions` — feeds the
  question-picker filter so the user only sees still-targetable
  questions.
- `POST /api/intervention/createDraft`
- `POST /api/intervention/{id}/activate`
- `GET /api/intervention/{id}/activationConflicts` — preview the 409
  body without committing.

### State machine

```
            createDraft           setQuestions / addQuestions /
                │                  update / DELETE (soft)
                ▼                       ▲
            DRAFT ─────activate─────▶ ACTIVE ─────close─────▶ CLOSED
                                    (immutable — questions
                                     locked in by activation)
```

### Failure paths

| Condition | HTTP | Body |
|---|---|---|
| Caller lacks `INTERVENTION_MANAGE` | 403 | permission denied |
| `auditId` missing or audit is CLOSED/deleted | 422 | descriptive |
| Activate with no questions | 422 | `Cannot activate without any questions` |
| Activate conflicts on questions | 409 | `{ status:false, data: QuestionConflictDTO[] }` |

### Backfill subtlety (worth flagging)

`activateWithTargeting` runs `backfillPlansForExistingApprovals` at the
end. If the audit wave was already complete when the admin clicks
Activate, the per-approval listener would never fire — so backfill
sweeps every APPROVED UC on every target and re-runs
`instantiateForApprovedAudit` (idempotent). Without this, late-arriving
campaigns would be inert.

---

## B9. Dealer Principal acknowledges plan

**Actor:** Dealer Principal (user with `DEALER_PRINCIPAL` role +
`INTERVENTION_ASSIGNMENT_ACKNOWLEDGE` permission, also stamped on the
plan inspection as `dealer_principal_user`).

**Trigger:** DP opens **My Plans** on the web after receiving the
notification.

### HTTP sequence

```
Browser                               Backend
   │                                    │
   │  GET /api/intervention-assignment/ │
   │      myPlans                       │
   │ ──────────────────────────────────▶│
   │                                    │  InterventionAssignmentServiceImpl.myPlans
   │                                    │  └─▶ findByDealerPrincipalUserAndDeletedAtIsNull(me)
   │                                    │       returns Inspection where kind='INTERVENTION'
   │                                    │       and dealer_principal_user_id = me
   │  200 { data: [{ id, interventionId,│
   │     interventionName, priority,    │
   │     locationLabel, targetDate,     │
   │     status:"PENDING" |             │
   │     "IN_PROGRESS" | "COMPLETED" }] }│
   │ ◀──────────────────────────────────│
   │                                    │
   │  DP clicks Acknowledge on a card   │
   │  POST /api/intervention-assignment/│
   │       {id}/acknowledge             │
   │ ──────────────────────────────────▶│
   │                                    │  InterventionAssignmentServiceImpl.acknowledge
   │                                    │  ├─ requireAck() (permission gate)
   │                                    │  ├─ load Inspection, require kind='INTERVENTION'
   │                                    │  ├─ verify dealer_principal_user_id == caller
   │                                    │  ├─ stamp acknowledged_at / acknowledged_by
   │                                    │  └─ SAVE (idempotent — re-ack just refreshes timestamps)
   │
   │  200 { message: "Plan acknowledged"}│
   │ ◀──────────────────────────────────│
```

### Endpoints

- `GET /api/intervention-assignment/myPlans`
- `POST /api/intervention-assignment/{id}/acknowledge`

### Important nuance (post-V1.28)

Acknowledge is a **soft signal** — it stamps `acknowledged_at` and
`acknowledged_by` but does NOT advance `inspections.status`. The status
stays `ASSIGNED` until the operator starts the re-inspection (B10), at
which point B3's transition guard flips it to `IN_PROGRESS`. The
pre-V1.28 docs said acknowledge flipped status — that was when the plan
was its own table. The current model decouples DP commitment from the
operator's work-in-progress state.

### Failure paths

| Condition | HTTP |
|---|---|
| Caller lacks `INTERVENTION_ASSIGNMENT_ACKNOWLEDGE` | 403 |
| Plan not found / soft-deleted | 404 |
| Inspection isn't kind=INTERVENTION | 422 |
| Caller is not the plan's stamped dealer principal | 403 (`Only the dealer principal of this dealership can acknowledge this plan`) |

---

## B10. Re-inspection wave (operator)

**Actor:** operator (often the same auditor as the original audit).

**Trigger:** operator's **My Audits** list now includes an
intervention-kind assignment alongside their regular audits (B2 returns
both). They tap it, fill the same `/question/:idx` screens, attach
photos, submit, validator + approver review — and on final APPROVE, the
plan is evaluated for completion.

### HTTP sequence

The mobile API surface is identical to the audit flow, with one field
swap:

| Audit flow | Re-inspection flow |
|---|---|
| `createOrUpdate` with `auditAssignmentId` | `createOrUpdate` with `interventionAssignmentId` |
| `createOrUpdateUserChksAns` — `inspectionId` | same, unchanged |
| `createUserChksAnsFile` — `userChecksheetAnswerId` | same, unchanged |
| `createOrUpdate` SUBMITTED | same, unchanged |
| `addUserChecksheetValidation` | same, unchanged |
| `addUserChecksheetApproval` | same, unchanged |

Mobile-app compat shim: if the app sends `auditAssignmentId` for what
turns out to be a `kind=INTERVENTION` inspection,
`UserChecksheetServiceImpl` swaps it to `interventionAssignmentId`
transparently (line 442-451).

### Final approval branch (B7 reprise)

```
UserChecksheetApprovalServiceImpl  →  publishEvent(UserChecksheetApprovedEvent {
                                          inspectionId: <re-inspection uc>,
                                          aaId: null,
                                          iaId: <re-inspection inspection id>
                                       })
       ┌──────────────────────────┘
       ▼
InterventionInstantiationListener.onApproved  (after commit, async)
       │
       ├─ ev.aaId != null  → instantiateForApprovedAudit (audit-side B7)
       └─ ev.iaId != null  → evaluatePlanCompletion       ← THIS BRANCH
                                ├─ load intervention_assignment_questions[]
                                ├─ for each tracked question:
                                │     find LATEST answer on this inspection
                                │     check judgement == OK (=1)
                                ├─ if ALL tracked-OK:
                                │     inspections.status='COMPLETED'
                                │     completed_at = now()
                                └─ else:
                                     inspections.status stays IN_PROGRESS
                                     (operator may run another wave)
```

### BI rollup

The moment the re-inspection's APPROVED row exists, the BI's
`audit_signal` CTE's `LATERAL pct_ok` subquery picks up its answers.
Per `(auditee_location_id, chks_question_result_id)`, the latest
APPROVED inspection wins (ordered `submitted_at DESC NULLS LAST, uca_id DESC`).
A dealer who was 67% on the original audit + closed five questions
through a P1 intervention shows 81% across `/stats/national`,
`/stats/dealer/{id}`, `/stats/location/{id}`, and
`/userChecksheet/{id}/improvement-overlay` — by construction (see
[`system-overview.md`](./system-overview.md) §5).

### Failure paths

Same as B3–B7, with one additional 422 from B3:
- `Re-inspection is already started or closed (status=…)` — when the
  intervention inspection isn't in `ASSIGNED`.

---

## B11. View audit-report (any authorised role)

**Actor:** anyone with read access to the inspection — admin (via
`AUDIT_VIEW`), the operator who filled it, or the Dealer Principal of
the dealership.

**Trigger:** user opens `/audit-report/:ucId` on the web (linked from
the BI dashboard, the operator's history, or the DP's "View past
audit" card).

### HTTP sequence

```
Browser                                       Backend
   │                                              │
   │  (Angular AuditReportComponent fires 3       │
   │   parallel HTTP calls on init)               │
   │                                              │
   │  (1) POST /api/userChecksheet/               │
   │           getUserChecksheetWithAnswers       │
   │  { inspectionId: 555 }                       │
   │ ────────────────────────────────────────────▶│  UserChecksheetServiceImpl.getUserChecksheetWithAnswers
   │                                              │  ├─ assertUserChecksheetVisible(uc)
   │                                              │  │     gate: AUDIT_VIEW perm
   │                                              │  │        OR operator_user_id == me
   │                                              │  │        OR dealer_principal_user_id == me
   │                                              │  ├─ loads checksheet + headers/questions/results
   │                                              │  ├─ loads user_checksheet_answers
   │                                              │  ├─ mergeAnswersWithContent (photo URLs for ANY answer type,
   │                                              │  │     not just FILE_UPLOAD — V1.24+)
   │                                              │  └─ returns nested checksheet tree
   │                                              │
   │  (2) GET /api/audit/userChecksheetMeta?      │
   │           userChecksheetId=555               │
   │ ────────────────────────────────────────────▶│  AuditServiceImpl.getUserChecksheetMeta
   │                                              │  ├─ same scope gate (inline)
   │                                              │  └─ native SQL:
   │                                              │     - inspection → location → city → state → region
   │                                              │     - LATERAL pct_ok using LATEST-effective answer
   │                                              │       per chks_question_id across this AUDIT-kind UC
   │                                              │       + every APPROVED INTERVENTION-kind UC at the
   │                                              │       same location for the same audit
   │                                              │     returns { locationLabel, dealerId, dealerName,
   │                                              │                regionId, regionName, pctOk,
   │                                              │                okCount, notOkCount, startedAt,
   │                                              │                submittedAt }
   │                                              │
   │  (3) GET /api/audit/userChecksheet/          │
   │           {ucId}/improvement-overlay         │
   │ ────────────────────────────────────────────▶│  AuditServiceImpl.getImprovementOverlay
   │                                              │  ├─ same scope gate (inline)
   │                                              │  ├─ originalScore = pct OK on UC's own answers only
   │                                              │  ├─ currentScore  = LATEST-effective pct OK rollup
   │                                              │  │   across audit + intervention inspections at the
   │                                              │  │   same location
   │                                              │  ├─ scoreDelta    = currentScore − originalScore
   │                                              │  ├─ questionFlags = per-question intervention chain
   │                                              │  │   { chksQuestionId → [{ interventionId,
   │                                              │  │       campaignName, priority,
   │                                              │  │       interventionStatus }] }
   │                                              │  ├─ reAuditAnswers = per-question re-inspection answers
   │                                              │  │   with photoUrls resolved
   │                                              │  └─ headerSnapshots = one per intervention that has at
   │                                              │      least one APPROVED re-inspection here
   │                                              │
   │  (Angular renders the report,                │
   │   layering the overlay onto each             │
   │   question card.)                            │
```

### Endpoints

- `POST /api/userChecksheet/getUserChecksheetWithAnswers`
- `GET /api/audit/userChecksheetMeta?userChecksheetId=…`
- `GET /api/audit/userChecksheet/{id}/improvement-overlay`

### Shared authorization gate

All three endpoints enforce the same gate (lives in
`UserChecksheetServiceImpl.assertUserChecksheetVisible` and is inlined
into the two AuditServiceImpl methods):

```
hasPermission(caller, AUDIT_VIEW)
   OR  inspection.operator_user_id == caller.id
   OR  inspection.dealer_principal_user_id == caller.id
```

A senior reviewer (Department Head, Section Head, Super Admin) gets
`AUDIT_VIEW` and sees every audit. An operator can read their own. A
DP can read their dealership's audits.  Everyone else is 403.

The xlsx + PDF download endpoints (`downloadUserChecksheetWithAnswers`,
`downloadUserChecksheetWithAnswersPdf`) inherit the gate because they
internally delegate to `getUserChecksheetWithAnswers`.

### What the UI does

The Angular `AuditReportComponent` renders three layers:
1. **Header** — `userChecksheetMeta` (location, dealer, region, pctOk).
2. **Body** — `getUserChecksheetWithAnswers` (checkpoint cards per
   question, photos, judgements).
3. **Overlay** — `improvement-overlay` (per-question intervention
   chain, original→current delta in the header).

If the user clicks a re-inspection photo or an intervention chip, the
UI uses `reAuditAnswers` / `questionFlags` to deep-link without firing
a fourth call.

### Failure paths

| Condition | HTTP | Body |
|---|---|---|
| Caller fails the visibility gate on any of the 3 | 403 | `You do not have access to this user_checksheet` |
| `userChecksheetId` not found | 404 | `user_checksheet not found: <id>` |
| Caller is on a kind=INTERVENTION inspection (overlay only) | 400 | `Inspection is a re-inspection wave, no overlay available` |

---

# Workflow state machines

These are the canonical state machines that gate every UI action. The
**checksheet template** lifecycle is unchanged from pre-V1.24 and still
drives the BI/admin template-management screens. The **user-checksheet
data-entry** lifecycle below is the **legacy** form (the 6-state
`UserChecksheetStatusType` enum) — it remains the contract for the
Angular checksheet-fill listing pages and the legacy operator routes
documented in §7 below. The V1.28 unified `inspections.status` enum
(`ASSIGNED → IN_PROGRESS → SUBMITTED → VALIDATED → APPROVED`, with
`DECLINED` as the bounce state) is the runtime backing for both audit
and intervention inspections — see B3–B7 for its transitions. The two
enums map as follows:

| Legacy `UserChecksheetStatusType` | V1.28 `inspections.status` |
|-----------------------------------|---------------------------|
| *(no row before operator opens it)* | `ASSIGNED` |
| `IN_PROGRESS` | `IN_PROGRESS` |
| `SUBMITTED` | `SUBMITTED` |
| `INVALIDATED` | `DECLINED` (bounces back to `IN_PROGRESS` on operator resume — see B6 "DECLINED state semantics") |
| `VALIDATED` | `VALIDATED` |
| `NOT_APPROVED` | `DECLINED` (same bounce semantics) |
| `APPROVED` | `APPROVED` |

## Checksheet template lifecycle

The lifecycle of a checksheet **template** (its definition, headers,
questions, and result types). This is the template the operator fills
against — separate from the user-checksheet runtime row.

```
NEW
  |
  v
CREATE_TEMPLATE  (Preparer adds headers, general fields)
  |
  v
CREATE_CONTENT   (Preparer adds header data, questions, result definitions)
  |
  v
SUBMITTED_FOR_VALIDATE  (Preparer submits for review)
  |
  +--> INVALIDATED  (Validator rejects with remarks)
  |       |
  |       v
  |     CREATE_CONTENT  (Preparer fixes issues, resubmits)
  |       |
  |       v
  |     SUBMITTED_FOR_VALIDATE  (back into validation)
  |
  +--> VALIDATED  (Validator approves the template)
         |
         +--> NOT_APPROVED  (Approver rejects with remarks)
         |       |
         |       v
         |     CREATE_CONTENT  (Preparer fixes issues, resubmits)
         |
         +--> APPROVED  (Approver approves -- template is active)
```

**Who can do what:**

| Status | Who acts | Available actions |
|--------|----------|-------------------|
| `NEW` | Preparer | Edit details, set up template structure |
| `CREATE_TEMPLATE` | Preparer | Add/edit headers, general fields |
| `CREATE_CONTENT` | Preparer | Add/edit header data, questions, results; submit for validation |
| `SUBMITTED_FOR_VALIDATE` | Validator | Validate or invalidate (with remarks) |
| `INVALIDATED` | Preparer | Fix issues, resubmit |
| `VALIDATED` | Approver | Approve or reject (with remarks, implementation date) |
| `NOT_APPROVED` | Preparer | Fix issues, resubmit |
| `APPROVED` | -- | Template is active; new version can be created |

Audit campaigns (V1.24+) only run against templates whose status is
`APPROVED` — see B3 "checksheet template is APPROVED" guard.

## User-checksheet (data entry / fill) lifecycle — legacy enum

The lifecycle of a filled-in checksheet instance by an operator,
expressed as the legacy `UserChecksheetStatusType` enum. The Angular
checksheet-fill listing screens and the legacy generic checksheet
endpoints in §7 use this enum; the V1.28 audit/intervention inspections
use the mapping in the table above.

```
IN_PROGRESS  (Operator filling out answers)
  |
  v
SUBMITTED  (Operator submits completed checksheet)
  |
  +--> INVALIDATED  (Data Validator rejects with remarks)
  |       |
  |       v
  |     IN_PROGRESS  (Operator corrects and resubmits)
  |
  +--> VALIDATED  (Data Validator approves the data)
         |
         +--> NOT_APPROVED  (Data Approver rejects with remarks)
         |       |
         |       v
         |     IN_PROGRESS  (Operator corrects and resubmits)
         |
         +--> APPROVED  (Data Approver approves -- final)
```

**Who can do what:**

| Status | Who acts | Available actions |
|--------|----------|-------------------|
| `IN_PROGRESS` | Operator | Fill answers, upload files, save general field values, submit |
| `SUBMITTED` | Data Validator | Validate or invalidate (with remarks) |
| `INVALIDATED` | Operator | Correct answers, resubmit |
| `VALIDATED` | Data Approver | Approve or reject (with remarks) |
| `NOT_APPROVED` | Operator | Correct answers, resubmit |
| `APPROVED` | -- | Final state, read-only |

---

# UI rendering reference

This section is the FE counterpart to the backend flows above: given a
DTO payload returned by an endpoint, which form control / chart / value
formatter does the UI use to render it? Use this alongside `api.md` for
the DTO field reference.

## Answer type to form control mapping

| `answerType` | Form control | Details |
|--------------|--------------|---------|
| `OBJECTIVE` | Numeric input(s) | Show `noOfResults` input fields. Display `unit` label next to inputs. Show `upperLimit`/`lowerLimit` as reference based on `chksQuestionResultObjectiveType`. Auto-judge OK/NotOK based on limits. |
| `SUBJECTIVE` | Text input / textarea | Free-form text. No auto-judgement. |
| `SUBJECTIVE_CONDITION` | Dropdown / select | Options from `chksQuestionResultOptions` array. Each option has a pre-assigned `judgement` value ("OK"/"NOT_OK"). Selecting an option auto-sets the judgement. |
| `MATRIX` | Data grid / table | Rows from `chksMatrixRowNm`, columns from `chksMatrixColNm`. Each cell is an input. Matrix data comes from `chksQuestionResultMatrices`. |
| `NA` | None / disabled | Display as "Not Applicable". No input required. |

Photo evidence is allowed on **any** answer type (V1.27+) — not just
`FILE_UPLOAD`. The read-side merge in
`UserChecksheetServiceImpl.mergeAnswersWithContent` loads files for
every answer that has an id, regardless of its `answerType`. See B4 for
the upload flow.

## Objective type display

When rendering an OBJECTIVE question, show the validation criteria to
the operator before they enter a value:

| `objectiveType` | Display hint |
|-----------------|--------------|
| `RANGE` | "Acceptable: {lowerLimit} - {upperLimit} {unit}" |
| `EQUAL_TO` | "Must equal: {upperLimit} {unit}" |
| `LESS_THAN` | "Must be < {upperLimit} {unit}" |
| `LESS_THAN_OR_EQUAL_TO` | "Must be <= {upperLimit} {unit}" |
| `GREATER_THAN` | "Must be > {lowerLimit} {unit}" |
| `GREATER_THAN_OR_EQUAL_TO` | "Must be >= {lowerLimit} {unit}" |

## Judgement values

- `1` or `"OK"` = OK / Pass (typically shown in green).
- `0` or `"NOT_OK"` = Not OK / Fail (typically shown in red). The
  integer column is **authoritative** (`uca.judgement = 2` is the
  on-disk "NOT OK" — be careful: legacy seeded rows store `2` not `0`;
  the string label in `usr_chksheet_ans_judgements` is informational and
  may be missing — fall back to the integer). See CLAUDE.md key
  conventions.
- For `SUBJECTIVE_CONDITION`: judgement is derived from the selected
  option's pre-configured `judgement` field.
- For `OBJECTIVE`: judgement is calculated client-side from the numeric
  answer vs. the limits/comparison type (see the objective-type table
  above).
- For `MATRIX`: each cell can have its own judgement.

## Checksheet structure hierarchy

A checksheet is structured as:

```
Checksheet
  +-- General Fields (metadata inputs like Machine No., Batch)
  +-- Headers (columns defining the structure)
       +-- Header Data (rows, can be hierarchical with parent-child)
            +-- Questions (inspection checkpoints)
                 +-- Question Results (answer type definitions)
                      +-- Result Options (for SUBJECTIVE_CONDITION)
                      +-- Result Matrices (for MATRIX type)
```

When rendering the checksheet-fill form:

1. Show general fields at the top for the operator to fill.
2. Render headers as column labels.
3. Render header data as rows (support nesting via `chksHeaderDataId`
   parent reference). Kia uses a 4-level hierarchy
   (Zone → Category → Element → Question); the mobile question screen
   shows all four levels on the breadcrumb (see CLAUDE.md
   "Customer terminology overrides").
4. For each question under a header data row, render the appropriate
   form control based on `answerType` (table above).
5. If a header is marked `isTraceable`, show a trace value input for
   each header data row.

## Dashboard chart mappings

The Angular BI dashboard maps endpoint payloads to chart components as
follows. Every BI metric query downstream of `auditId` shares the same
`audit_signal` CTE — see CLAUDE.md "BI dashboards — single shared scope
CTE" for the SQL.

| Endpoint | Chart type | Data points |
|----------|-----------|-------------|
| `getChecksheetSummaryData` | Bar / pie chart | `okCount`, `notOkCount` per question |
| `getPlanVsActualData` | Stacked bar / calendar | `planned`, `completed`, `missed`, `npd` per checksheet with daily breakdown |
| `trendChart` | Line chart | Answer values over time with limit lines (`upperLimit`, `lowerLimit`) |
| `getCompletionFunnelData` | Funnel chart | `planned` -> `inProgress` -> `submitted` -> `validated` -> `approved` |
| `getComplianceHeatmapData` | Heatmap grid | `complianceScore` by department x period |
| `getRecentSubmissions` | Data table | Recent submissions with status badges |
| `getTopNonConformingQuestions` | Ranked bar chart | `notOkPercentage` with `trend` indicator |

The V1.24+ audit BI panels (`/api/audit/{auditId}/stats/...`) drive
their own dashboard surface (band counts, per-region/per-dealer/per-
location tables, red list, top-failing checkpoints, recency panel).
See B11 "View audit-report" for the layered audit-report rendering
(meta → answers → improvement overlay).

---

# Permission-based UI rules

The UI gates navigation, page access, and action buttons against the
`permissions` array returned by the login response (also decodable from
the JWT custom claim `permissions[]`). This is the FE companion to the
backend's `@PreAuthorize` / permission-filter logic — see B1 "Login"
for how the array is populated and `api.md` for the per-endpoint
permission gates.

## Menu / page visibility

| Page / section | Required permission(s) | Notes |
|----------------|------------------------|-------|
| **Department Management** | `DEPARTMENT_LIST` or `SUBDEPARTMENT_LIST` | Show department listing |
| **Department Create** | `DEPARTMENT_CREATE` | Show "Add Department" button |
| **Section Management** | `SUBDEPARTMENT_LIST` | Show section listing |
| **Section Create** | `SUBDEPARTMENT_CREATE` | Show "Add Section" button |
| **User Management** | `USER_LIST` | Show user listing within departments |
| **User Create/Edit** | `USER_CREATE` / `USER_EDIT` | Show create/edit user form |
| **User Delete** | `USER_DELETE` | Show delete button |
| **Role Management** | `ROLE_LIST` | SUPER_ADMIN only; show roles page |
| **Permission Management** | `PERMISSION_LIST` | SUPER_ADMIN only; show permissions page |
| **Checksheet Management (list)** | `CHECKSHEET_MANAGEMENT_LIST` | Show template management listing |
| **Checksheet Create** | `CHECKSHEET_MANAGEMENT_DETAIL_CREATE` | Show "Create Checksheet" button |
| **Checksheet Template Edit** | `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT` | Enable editing of template structure |
| **Checksheet Template View** | `CHECKSHEET_MANAGEMENT_TEMPLATE_VIEW` | View-only template access |
| **Checksheet Content View** | `CHECKSHEET_MANAGEMENT_CONTENT_VIEW` | View header data and questions |
| **Checksheet Fill (list)** | `CHECKSHEET_FILL_LISTING` | Show operator fill listing |
| **Checksheet Fill (detail)** | `CHECKSHEET_FILL_CHECKSHEET_DETAIL` | Access fill detail page |
| **Checksheet Fill (answer)** | `CHECKSHEET_FILL_ANSWER` | Enable answer inputs |
| **Checksheet Listing** | `CHECKSHEET_LISTING` | General checksheet listing |
| **Validator Comments (template)** | `CHECKSHEET_MANAGEMENT_VALIDATOR_COMMENT_VIEW` | View validator remarks |
| **Validator Comments (create)** | `CHECKSHEET_MANAGEMENT_VALIDATOR_COMMENT_CREATE` | Write validator remarks |
| **Approver Comments (template)** | `CHECKSHEET_MANAGEMENT_APPROVER_COMMENT_VIEW` | View approver remarks |
| **Approver Comments (create)** | `CHECKSHEET_MANAGEMENT_APPROVER_COMMENT_CREATE` | Write approver remarks |
| **Data Validator Comments** | `CHECKSHEET_DATA_VALIDATOR_COMMENT_VIEW` | View data validator remarks |
| **Data Validator Comments (create)** | `CHECKSHEET_DATA_VALIDATOR_COMMENT_CREATE` | Write data validator remarks |
| **Data Approver Comments** | `CHECKSHEET_DATA_APPROVER_COMMENT_VIEW` | View data approver remarks |
| **Data Approver Comments (create)** | `CHECKSHEET_DATA_APPROVER_COMMENT_CREATE` | Write data approver remarks |
| **NPD Management** | `NPD_CREATE` | Show NPD creation UI |
| **Dashboard** | *(any authenticated user)* | Dashboard is accessible to all logged-in users |
| **Download buttons** | `DEPARTMENT_DOWNLOAD`, `SUBDEPARTMENT_DOWNLOAD`, `USER_DOWNLOAD` | Show download Excel buttons |
| **Audits list / Audit detail** | `AUDIT_VIEW` | V1.24+ audit campaigns — admin view |
| **Interventions (Improvement Campaigns)** | `INTERVENTION_MANAGE` | V1.27+ admin route `/interventions` |
| **My Plans (Dealer Principal)** | `INTERVENTION_ASSIGNMENT_ACKNOWLEDGE` | DP-scoped route `/my-plans` |
| **Audit-report page** | `AUDIT_VIEW` OR operator-of-UC OR Dealer-Principal-of-UC's-dealership | See B11 for the canonical 3-call authorization rule |

## Role-based permission bundles

Typical permission bundles per workflow role. Use these as a guide for
which UI sections each user sees:

| Role | Sees |
|------|------|
| **Checksheet Preparer** | template management, template create/edit, content view, validator/approver comments (read-only), checksheet listing |
| **Checksheet Validator** | template management (view-only), content view, validator comments (read/write), approver comments (read-only), checksheet listing |
| **Checksheet Approver** | template management (view-only), content view, validator comments (read-only), approver comments (read/write), checksheet listing |
| **Data Validator** | checksheet listing, data validator comments (read/write), data approver comments (read-only), NPD creation |
| **Data Approver** | checksheet listing, data validator comments (read-only), data approver comments (read/write) |
| **Operator** | checksheet fill listing, fill detail, fill answer (mobile app primary) |
| **Admin (V1.24+)** | Audits list, Audit detail, Create intervention, BI dashboards |
| **Dealer Principal (V1.27+)** | My Plans, plan detail, acknowledge / close-as-non-compliant actions |
| **SUPER_ADMIN** | everything, including role and permission management |

## Action buttons by checksheet status and role

Template management (drives the legacy template lifecycle in §
"Workflow state machines" above):

| Current status | Button | Visible to |
|----------------|--------|------------|
| `CREATE_CONTENT` | "Submit for Validation" | Preparer |
| `SUBMITTED_FOR_VALIDATE` | "Validate" / "Invalidate" | Validator |
| `INVALIDATED` | "Resubmit" | Preparer |
| `VALIDATED` | "Approve" / "Reject" | Approver |
| `NOT_APPROVED` | "Resubmit" | Preparer |
| `APPROVED` | "Create New Version" | Preparer (with version permissions) |

User checksheet (fill) — legacy enum:

| Current status | Button | Visible to |
|----------------|--------|------------|
| `IN_PROGRESS` | "Submit" | Operator |
| `SUBMITTED` | "Validate" / "Invalidate" | Data Validator |
| `INVALIDATED` | "Resubmit" | Operator |
| `VALIDATED` | "Approve" / "Reject" | Data Approver |
| `NOT_APPROVED` | "Resubmit" | Operator |
| `APPROVED` | *(none -- read-only)* | -- |

V1.28 inspections (audit + intervention runtime — see B3–B7 for the
canonical transitions):

| Current `inspections.status` | Button | Visible to |
|------------------------------|--------|------------|
| `ASSIGNED` | "Start" | Operator (B3) |
| `IN_PROGRESS` | "Resume" / "Submit" | Operator (B3, B5) |
| `SUBMITTED` | "Validate" / "Invalidate" | Data Validator (B6) |
| `DECLINED` | "Resume — declined by validator" | Operator (bounce to `IN_PROGRESS`) |
| `VALIDATED` | "Approve" / "Reject" | Data Approver (B7) |
| `APPROVED` | *(none -- read-only)* | -- |

## SUPER_ADMIN override

If the logged-in user has the `SUPER_ADMIN` role (check the `allRoles`
array from the login response, or the `roles[]` claim on the JWT),
**bypass all permission checks in the UI**. SUPER_ADMIN has access to
every page, every action, and every button. The backend also bypasses
permission checks for SUPER_ADMIN — see B1 and the legacy
"Permission Check Flow" section below for the server-side gate.

---

## 1. User Registration

**Entry point:** `POST /api/user/register`
**Whitelisted:** Yes (in `SecurityConfiguration.WHITE_LIST_URL`)

### Sequence

```
UserController.register(UserDTO)
  -> UserServiceImpl.register(UserDTO)
       1. UserRepository.findByEmail(userDTO.getEmail())
          - If present: throw CustomException("Email already exists", 422)
       2. Create new User entity
          - user.setFirstName(dto.firstName)
          - user.setLastName(dto.lastName)
          - user.setEmail(dto.email)
          - user.setPassword(passwordEncoder.encode(dto.password))
       3. UserRepository.save(user)                          -- INSERT into users
       4. JwtService.generateToken(user)                     -- build JWT with HS256
       5. user.setJwt_token(jwtToken)
       6. UserRepository.save(user)                          -- UPDATE users.jwt_token
       7. Return ResponseDTO(true, "User registered successfully",
              UserDTO { accessToken = jwtToken })
```

### Data transformations

| Direction | From | To |
|-----------|------|----|
| Request   | `UserDTO` (firstName, lastName, email, password) | `User` entity |
| Response  | JWT string | `UserDTO` (accessToken only) |

### Database operations

- **Read:** `users` table by email (uniqueness check)
- **Write:** Two saves to `users` table (create, then update with JWT)

### Side effects

- None (no email, no notification)

### Error paths

- Duplicate email: `422 Unprocessable Entity`

---

## 2. User Login

**Entry point:** `POST /api/user/login`
**Whitelisted:** Yes

### Sequence

```
UserController.authenticate(UserDTO)
  -> UserServiceImpl.login(UserDTO)
       1. Check if LDAP is required:
          - ldapEnvironment is set AND deviceType == "WEB"
          => handleLdapLogin(userDTO)
          - Otherwise
          => handleRegularLogin(userDTO)
```

### Regular Login Path (handleRegularLogin)

```
handleRegularLogin(UserDTO)
  1. UserRepository.findByUsernameIgnoreCase(username)
     - If empty: throw CustomException("Invalid username or password", 422)
  2. Validate deviceType is "APP" or "WEB"
  3. Rate-limiting logic:
     - Track failLoginCount on User entity
     - If count >= totalLoginCount (default 3) and within resendOTPTime window:
       throw CustomException("You can relogin after N minutes", 422)
     - Increment failLoginCount, save user
  4. AuthenticationManager.authenticate(
       UsernamePasswordAuthenticationToken(username, password))
     - Spring Security validates credentials via ApplicationConfig UserDetailsService
  5. UserRoleDepartmentDAO.getUserByEmailAndUserId(user.id)
     - Native query: returns list of role codes for the user
  6. setUserResponseDTO(user, allRoles, deviceType)
```

### setUserResponseDTO (shared by both login paths)

```
setUserResponseDTO(User, allRoles, deviceType)
  1. PermissionServiceImpl.getEffectivePermissionsForUser(userId)
     - UserRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(userId)
     - For each UserRoleDepartment:
       - resolvePermissionsWithHierarchy(role)
         - RolePermissionRepository.findPermissionCodesByRoleId(roleId)
     - Merge all permission codes into Set<String>
  2. If allPermissions is empty: throw 403 "No permissions assigned"
  3. Build extraClaims map { "permissions": [...], "roles": [...] }
  4. JwtService.generateToken(extraClaims, user)
     - Jwts.builder().setClaims(extraClaims).setSubject(username)
       .setExpiration(now + jwtExpiration * 1000).signWith(HS256)
  5. Update user:
     - user.jwt_token = jwtToken
     - user.failLoginCount = 0
     - user.resendOtpTime = null
     - UserRepository.save(user)
  6. Build UserDTO response (accessToken, firstName, lastName, email, roles, permissions, menus)
  7. Derive menus from permissions:
     - SUPER_ADMIN: getSuperAdminMenus() (all menus)
     - Others: getMenusFromPermissions(permissions) using PERMISSION_MENU_MAP
  8. RefreshTokenServiceImpl.generateRefreshToken(username, true, deviceType)
     - Find existing RefreshToken for user+deviceType, or create new
     - Set token = UUID, expiryDate = now + jwtRefreshTokenExpirationInMs
     - RefreshTokenRepository.save(refreshToken)
  9. Return ResponseDTO("You have successfully logged in!!!", userDTO)
```

### Database operations

- **Read:** `users` (by username), `user_role_department` (roles), `role_permission` (permissions), `refresh_token`
- **Write:** `users` (update jwt_token, reset fail count), `refresh_token` (create/update)

### Side effects

- None

### Error paths

- User not found: 422
- Invalid deviceType: 401
- Rate-limited: 422 with retry time
- Bad credentials: 400 (from AuthenticationManager)
- No permissions: 403

---

## 3. Token Refresh

**Entry point:** `POST /api/user/refreshToken`
**Whitelisted:** Yes

### Sequence

```
UserController.refreshToken(UserDTO)
  -> UserServiceImpl.refreshToken(UserDTO)
       1. Validate inputs: refreshToken, deviceType must not be null
       2. RefreshTokenRepository.findByTokenAndDeviceType(token, deviceType)
          - If not found: throw "Session expired!" 401
       3. Check expiryDate > now
          - If expired: throw "Session expired!" 401
       4. UserRepository.findById(refreshToken.user.id)
       5. JwtService.generateToken(user)            -- new access token
       6. If deviceType == "APP":
          - user.jwt_token = newJwtToken
          - UserRepository.save(user)
       7. RefreshTokenServiceImpl.generateRefreshToken(username, false, deviceType)
          - Reuses existing RefreshToken record
          - Sets new UUID token (does NOT reset expiryDate since isNewLogin=false)
          - RefreshTokenRepository.save(refreshToken)
       8. Return UserDTO { accessToken, refreshToken }
```

### Database operations

- **Read:** `refresh_token` (by token+deviceType), `users` (by id)
- **Write:** `users` (update jwt_token for APP), `refresh_token` (update token UUID)

### Error paths

- Missing params: 401
- Invalid/expired token: 401

---

## 4. Checksheet Creation

**Entry point:** `POST /api/checksheet/createChecksheet`
**Auth required:** Yes (JWT)

### Sequence

```
ChecksheetController.createChecksheet(ChecksheetDTO)
  -> ChecksheetServiceImpl.createChecksheet(ChecksheetDTO)
       1. Get current user via SecurityContextHolder
       2. If dto.id != null (edit mode):
          - ChecksheetRepository.findById(dto.id)
          - Check status != APPROVED (if APPROVED, not editable)
       3. Permission check:
          - PermissionService.hasPermission(userId, "CHECKSHEET_MANAGEMENT_DETAIL_CREATE")
          - PermissionService.hasPermission(userId, "CHECKSHEET_MANAGEMENT_DETAIL_EDIT")
       4. Input validation: departmentId, preparerUserUsername, validatorUserUsernames,
          approverUserUsernames, escalateToUserUsernames, alertToUserUsernames required
       5. DepartmentRepository.findById(departmentId) -> section
       6. Generate UID for new checksheet:
          - ChecksheetRepository.countChksInDepartment(department.id)
          - UID = "IMS-{deptName}-F{count+1}"
       7. Set checksheet fields:
          - name, modelNo, description, department, assetCode, checksheetType
          - status = ChecksheetStatusType.NEW (for new)
          - frequencyOfCheck, frequencyOfFreqOfChk
          - version = 0, implementationDate
       8. Process preparer user:
          - UserRepository.findByUsernameIgnoreCase(preparerUsername)
          - Ensure UserRoleDepartment exists for SUBDEPT_ADMIN role in section
       9. Process validator users (loop):
          - UserRepository.findByUsernameIgnoreCase(each username)
          - Ensure UserRoleDepartment for SUBDEPT_ADMIN role
          - Collect validatorUserIds
      10. Process approver users (same pattern as validators)
      11. Process operator users:
          - UserRepository.findById(each operatorId)
          - Ensure UserRoleDepartment for OPERATOR role
      12. Process data validator users:
          - Ensure UserRoleDepartment for DEPT_ADMIN role
      13. Process data approver users:
          - Ensure UserRoleDepartment for DEPT_ADMIN role
      14. handleEscalateUsers(dto, checksheet)
          - Resolve usernames to IDs, set escalateToUserIds
      15. handleAlertUsers(dto, checksheet)
          - Resolve usernames to IDs, set alertToUserIds
      16. ChecksheetRepository.save(checksheet)
      17. If new: send email to preparer
          - UtilityService.sendEmail([preparerUserId], EmailTemplate.NEW, checksheet, [loginUserId])
```

### Database operations

- **Read:** `users`, `departments`, `roles`, `user_role_department`, `checksheets`
- **Write:** `checksheets` (insert/update), `user_role_department` (ensure role assignments exist)

### Side effects

- **Email:** Sent to preparer user via `UtilityService.sendEmail()` (async via `EmailService.sendTextMail()`)

---

## 5. Checksheet Validation Flow

This flow has two phases: (A) preparer submits for validation, (B) validators review.

### Phase A: Submit for Validation

**Entry point:** `POST /api/checksheet/updateChecksheetStatus`

```
ChecksheetController.updateChecksheetStatus(ChecksheetDTO)
  -> ChecksheetServiceImpl.updateChecksheetStatus(ChecksheetDTO)
       1. Validate: id, status required
       2. ChecksheetRepository.findById(dto.id)
       3. Verify all questions have result types:
          - chksHeaderDAO.getChksHeaderByChecksheetId(id, true) -> result column headers
          - chksQuestionRepository.countByChecksheetId(id)
          - chksQuestionResultRepository.countByChecksheetId(id)
          - If (headers * questions) != totalResults: throw 422
       4. Verify current user is the preparer
       5. Check valid status transition:
          - From: CREATE_CONTENT / INVALIDATED / NOT_APPROVED
          - To: SUBMITTED_FOR_VALIDATE
       6. Update checksheet:
          - status = SUBMITTED_FOR_VALIDATE
          - validateOrApproveVersion += 1
          - submittedAt = now
          - waitingUserIds = validatorUserIds
       7. ChecksheetRepository.save(checksheet)
       8. ChecksheetValidationRepository.deleteAllByChecksheet_Id(id)  -- clear old validations
       9. Email validators:
          UtilityService.sendEmail(validatorUserIds, EmailTemplate.SUBMITTED, checksheet, [currentUserId])
```

### Phase B: Validator Reviews

**Entry point:** `POST /api/checksheetValidation/createValidation`

```
ChecksheetValidationController.validateChecksheet(ChecksheetValidationDTO)
  -> ChecksheetValidationServiceImpl.addChecksheetValidation(ChecksheetValidationDTO)
       1. Validate: checksheetId, status, remarks required
       2. Get current user
       3. ChecksheetRepository.findById(checksheetId)
       4. Authorization: current user must be in checksheet.validatorUserIds
       5. Status check: checksheet.status must be SUBMITTED_FOR_VALIDATE
       6. Duplicate check:
          ChecksheetValidationRepository.findByChecksheet_IdAndValidatorUserId_Id(...)
       7. Create ChecksheetValidation entity:
          - checksheet, status, remarks, validatorUserId, validatedAt = now
          - ChecksheetValidationRepository.save(validation)
       8. Create ChecksheetValidationHistory entity:
          - Same fields + version = checksheet.validateOrApproveVersion
          - ChecksheetValidationHistoryRepository.save(history)
       9. If status == INVALIDATED:
          - checksheet.status = INVALIDATED
          - checksheet.waitingUserIds = [preparerUserId]
          - Email preparer: EmailTemplate.INVALIDATED
      10. Check if all validators have responded:
          - allValidations = ChecksheetValidationRepository.findByChecksheet_Id(checksheetId)
          - If allValidations.size() == checksheet.validatorUserIds.size():
            - Any INVALIDATED? -> status = INVALIDATED
            - All validated? -> status = VALIDATED
            - Clear old approvals
            - waitingUserIds = approverUserIds
            - Email approvers: EmailTemplate.VALIDATED
          - Else: remove current user from waitingUserIds
      11. ChecksheetRepository.save(checksheet)
```

### Database operations

- **Write:** `checksheet_validation`, `checksheet_validation_history`, `checksheets` (status update)
- **Delete:** Old validation records on resubmission

### Side effects

- **Email on INVALIDATED:** To preparer
- **Email on all validated:** To all approver users

---

## 6. Checksheet Approval Flow

**Entry point:** `POST /api/checksheetApproval/createApproval`

```
ChecksheetApprovalController.addChecksheetApproval(ChecksheetApprovalDTO)
  -> ChecksheetApprovalServiceImpl.addChecksheetApproval(ChecksheetApprovalDTO)
       1. Validate: checksheetId, status, remarks required
       2. Get current user
       3. ChecksheetRepository.findById(checksheetId)
       4. Authorization: current user must be in checksheet.approverUserIds
       5. Status check: checksheet.status must be VALIDATED
       6. Duplicate check:
          ChecksheetApprovalRepository.findByChecksheet_IdAndApproverUserId_Id(...)
       7. Create ChecksheetApproval entity:
          - checksheet, status, remarks, approverUserId, approvedAt = now
          - ChecksheetApprovalRepository.save(approval)
       8. Create ChecksheetApprovalHistory entity:
          - Same fields + version
          - ChecksheetApprovalHistoryRepository.save(history)
       9. If status == NOT_APPROVED:
          - checksheet.status = NOT_APPROVED
          - waitingUserIds = [preparerUserId]
          - Email preparer: EmailTemplate.NOT_APPROVED
      10. If status == APPROVED and implementationDate provided:
          - Update checksheet.implementationDate
      11. Check if all approvers have responded:
          - allApprovals = ChecksheetApprovalRepository.findByChecksheet_Id(checksheetId)
          - If allApprovals.size() == checksheet.approverUserIds.size():
            - Any NOT_APPROVED? -> status = NOT_APPROVED
            - All approved? -> status = APPROVED
            - waitingUserIds = []
            - If parent checksheet exists (version revision):
              parentChks.expiryDate = implementationDate
            - Email validators + section head + preparer: EmailTemplate.APPROVED
          - Else: remove current user from waitingUserIds
      12. ChecksheetRepository.save(checksheet)
```

### Status machine for checksheet lifecycle

```
NEW -> CREATE_CONTENT -> SUBMITTED_FOR_VALIDATE -> VALIDATED -> APPROVED
                              |                         |
                              v                         v
                         INVALIDATED              NOT_APPROVED
                              |                         |
                              +--- back to CREATE_CONTENT/SUBMITTED_FOR_VALIDATE ---+
```

### Side effects

- **Email on NOT_APPROVED:** To preparer
- **Email on APPROVED:** To validators, section head (createdBy), and preparer

---

## 7. User Checksheet Fill

Operators fill out approved checksheets. This involves multiple endpoints called in sequence.

### 7a. Get Available Checksheets

**Entry point:** `POST /api/userChecksheet/getUserChecksheets`

```
UserChecksheetController.getUserChecksheets(ChecksheetDTO)
  -> UserChecksheetServiceImpl.getUserChecksheets(ChecksheetDTO)
       1. Get current logged-in user
       2. ChecksheetDAO.getUserChecksheets(userId, "operator_user_ids", checksheetDTO)
          - Native query: finds checksheets where userId is in operator_user_ids JSON array
       3. UserChecksheetDAO.getDeclinedUserChecksheets(userId)
       4. For each checksheet:
          - UserChecksheetRepository.findByUserIdChecksheetId(userId, checksheetId)
          - Determine status based on frequency:
            DAILY/SHIFT: check today
            WEEKLY: check current week
            MONTHLY: check current month
            UNPLANNED: always "START"
          - Build UserChecksheetDTO list with existing fill records
```

### 7b. Create or Update User Checksheet (Start/Submit Fill)

**Entry point:** `POST /api/userChecksheet/createOrUpdate`

```
UserChecksheetController.createOrUpdateUserChecksheets(List<UserChecksheetDTO>)
  -> UserChecksheetServiceImpl.createOrUpdateUserChecksheets(List<UserChecksheetDTO>)
       -> For each DTO: createOrUpdateUserChecksheet(dto)
            1. Validate checksheetId, verify checksheet is APPROVED
            2. Check frequency limit: frequencyOfFreqOfChk >= frequencyOfFreqOfChkCnt
            3. If dto.id != null: find existing UserChecksheet
               If dto.id == null: create new, check no IN_PROGRESS exists
            4. Set fields:
               - operatorUserId = currentUser
               - checksheet, status, shift, startedAt, submittedAt
               - submissionVersion, frequencyOfFreqOfChkCnt
            5. UserChecksheetRepository.save(userChecksheet)
            6. If status == "SUBMITTED":
               - userChecksheet.waitingUserIds = checksheet.dataValidatorUserIds
               - Email data validators: EmailTemplate.USER_CHKS_SUBMITTED
```

### 7c. Save Answers

**Entry point:** `POST /api/userChecksheet/createOrUpdateUserChksAns`

```
UserChecksheetController.createOrUpdateUserChksAns(List<UserChecksheetAnswerDTO>)
  -> UserChecksheetServiceImpl.createOrUpdateUserChksAns(List<UserChecksheetAnswerDTO>)
       For each answer:
       1. Validate userChecksheetId, chksQuestionResultId
       2. Find or create UserChecksheetAnswer:
          - If id provided: findById
          - Else: findByUserChecksheetIdAndChksQuestionResultId (upsert pattern)
       3. Resolve references:
          - UserChecksheetRepository.findById(userChecksheetId) -> userChecksheet
          - ChksQuestionResultRepository.findById(chksQuestionResultId) -> questionResult
       4. Handle answer types:
          - SUBJECTIVE_CONDITION: set chksQuestionRsltOption via ChksQuestionResultOptionRepository
          - OBJECTIVE/SUBJECTIVE: set answer string
          - MATRIX: handled separately
          - NA: clear answer
          - If isNotApplicable: clear answer and option
       5. Set judgement, answeredAt
       6. UserChecksheetAnswerRepository.save(usrChksAns)
```

### 7d. Save Matrix Answers

**Entry point:** `POST /api/userChecksheet/createOrUpdateUserChksMtrxAns`

```
-> UserChecksheetServiceImpl.createOrUpdateUserChksMtrxAns(List<UserChecksheetAnswerDTO>)
     For each matrix answer:
     1. Find/create UserChecksheetMatrixAnswers
     2. Set chksQuestionResult, chksQuestionResultMatrix, mcResult, judgement, orderNo
     3. UserChecksheetMatrixAnswersRepository.save(usrChksMtrxAns)
```

### 7e. Save Judgements

**Entry point:** `POST /api/userChecksheet/createOrUpdateUserChksJudgements`

```
-> UserChecksheetServiceImpl.createOrUpdateUserChksJudgements(List<UsrChecksheetAnsJudgementDTO>)
     For each judgement:
     1. Find/create UsrChksheetAnsJudgement
     2. Set userChecksheet, chksQuestion, judgement ("OK"/"NOT OK"), remarks
     3. UsrChksheetAnsJudgementRepository.save(judgement)
```

### 7f. Save General Field Values

**Entry point:** `POST /api/userChecksheet/createOrUpdateUserChksGnrlFieldVals`

```
-> UserChecksheetServiceImpl.createOrUpdateUserChksGnrlFieldVals(List<ChksGeneralFieldValueDTO>)
     For each field value:
     1. Find/create ChksGeneralFieldValue
     2. Set userChecksheet, chksGeneralField, value
     3. ChksGeneralFieldValueRepository.save(fieldValue)
```

### 7g. Save Trace Values

**Entry point:** `POST /api/userChecksheet/createOrUpdateUserChksTraceValues`

```
-> UserChecksheetServiceImpl.createOrUpdateUserChksTraceValues(List<UserChecksheetTraceValueDTO>)
     For each trace value:
     1. Find/create UserChecksheetTraceValue
     2. Set userChecksheet, chksHeader, traceValue
     3. UserChecksheetTraceValueRepository.save(traceValue)
```

### 7h. Upload Judgement File

**Entry point:** `POST /api/userChecksheet/createOrUpdateUserChksJudgementFile`
**Content-Type:** `multipart/form-data` (uses `@ModelAttribute`)

```
-> UserChecksheetServiceImpl.createOrUpdateUserChksJudgementFile(UsrChksheetAnsJudgementFileDTO)
     1. Create UsrChksheetAnsJudgementFile entity
     2. Upload file:
        - FileStorageUtil.storeFile(file, "UsrChksheetAnsJudgementFile/{id}_{filename}")
     3. Set path on entity
     4. UsrChksheetAnsJudgementFileRepository.save(file)
```

### Database tables involved

- `user_checksheet` -- the fill record
- `user_checksheet_answer` -- individual answers per question result
- `user_checksheet_matrix_answers` -- matrix-type answers
- `usr_chksheet_ans_judgement` -- per-question judgement (OK/NOT OK)
- `usr_chksheet_ans_judgement_file` -- file attachments for judgements
- `chks_general_field_value` -- general field values per fill
- `user_checksheet_trace_value` -- traceability values

---

## 8. Data Validation / Approval

After an operator submits a filled checksheet (status = "SUBMITTED"), data validators and data approvers review it.

### 8a. Data Validation

**Entry point:** `POST /api/userChecksheetValidation/addUserChecksheetValidation`

```
UserChecksheetValidationController.validateChecksheet(UserChecksheetValidationDTO)
  -> UserChecksheetValidationServiceImpl.addUserChecksheetValidation(dto)
       1. Validate: userChecksheetId, status, remarks required
       2. Get current user
       3. UserChecksheetRepository.findById(userChecksheetId)
       4. Status check: userChecksheet.status must be "SUBMITTED"
       5. Duplicate check per version:
          - UserChecksheetValidationHistoryRepository
            .findByUserChecksheetId_IdAndVersion(id, submissionVersion)
          - Check if current user already commented
       6. Create/update UserChecksheetValidation entity:
          - UserChecksheetValidationRepository.save(validation)
       7. Create UserChecksheetValidationHistory:
          - UserChecksheetValidationHistoryRepository.save(history)
       8. Status transition:
          - If INVALIDATED:
            userChecksheet.status = "INVALIDATED"
            waitingUserIds = []
          - If all data validators responded:
            userChecksheet.status = "VALIDATED"
            waitingUserIds = checksheet.dataApproverUserIds
            Email data approvers: EmailTemplate.USER_CHKS_VALIDATED
          - Else: remove current user from waitingUserIds
       9. UserChecksheetRepository.save(userChecksheet)
```

### 8b. Data Approval

**Entry point:** `POST /api/userChecksheetApproval/addUserChecksheetApproval`

```
UserChecksheetApprovalController.addUserChecksheetApproval(UserChecksheetApprovalDTO)
  -> UserChecksheetApprovalServiceImpl.addUserChecksheetApproval(dto)
       1. Validate: userChecksheetId, status, remarks required
       2. Get current user
       3. UserChecksheetRepository.findById(userChecksheetId)
       4. Status check: userChecksheet.status must be "VALIDATED"
       5. Duplicate check per version
       6. Create/update UserChecksheetApproval entity:
          - UserChecksheetApprovalRepository.save(approval)
       7. Create UserChecksheetApprovalHistory:
          - UserChecksheetApprovalHistoryRepository.save(history)
       8. Status transition:
          - If NOT_APPROVED:
            userChecksheet.status = "NOT_APPROVED"
            waitingUserIds = []
          - If all data approvers responded (all approved):
            userChecksheet.status = "APPROVED"
            waitingUserIds = []
            -> Alert email flow (if NOT OK judgements exist):
               a. UsrChksheetAnsJudgementRepository
                  .findByUserChecksheet_IdAndJudgement(id, "NOT OK")
               b. Build HTML table with question details, specs, results
               c. For each alertToUser:
                  EmailService.sendEmail(email, subject, htmlContent, null, null, null)
            -> Escalation email flow:
               a. UserChecksheetDAO.getEscalateToUserCheckList(chksId, escalationDays)
               b. Check for consecutive NOT OK judgements across escalationDays
               c. Build HTML table
               d. For each escalateToUser: send escalation email
          - Else: remove current user from waitingUserIds
       9. UserChecksheetRepository.save(userChecksheet)
```

### User Checksheet Status Machine

```
IN_PROGRESS -> SUBMITTED -> VALIDATED -> APPROVED
                   |             |
                   v             v
              INVALIDATED   NOT_APPROVED
```

### Side effects

- **Email on SUBMITTED:** To data validators
- **Email on VALIDATED:** To data approvers
- **Email on APPROVED with NOT OK:** Alert email to alert users with HTML table of non-conforming items
- **Email on consecutive NOT OK (escalation):** Escalation email to escalation users

---

## 9. Dashboard Data Retrieval

The dashboard provides multiple data views. All endpoints require authentication and scope data by the user's assigned sections/departments.

### 9a. Get Checksheets for Dashboard

**Entry point:** `POST /api/dashboard/getRespectedChecksheet`

```
DashboardController.getRespectedChecksheet(DashboardDTO)
  -> DashboardServiceImpl.getRespectedChecksheet(DashboardDTO)
       1. Get current user
       2. UserRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(userId)
       3. PermissionService.getAllowedSectionIds(userId)
          - Returns null for global access (SUPER_ADMIN)
          - Returns section IDs for scoped users
       4. DashboardDAO.getRespectedChecksheet(dto, sectionIds, loginUser)
          - Native SQL query: joins checksheets with departments
          - Filters by section IDs (or no filter for SUPER_ADMIN)
```

### 9b. Plan vs Actual

**Entry point:** `POST /api/dashboard/getPlanVsActualData`

```
DashboardController.getPlanVsActualData(DashboardDTO)
  -> DashboardServiceImpl.getPlanVsActualData(DashboardDTO)
       1. Validate: startDate, endDate, frequencyOfCheck, checksheetIds required
       2. DashboardDAO.getPlanVsActualData(checksheetIds, startDate, endDate)
          - Native query returning: checksheetId, name, checkDate, status,
            implementationDate, frequencyOfFreqOfChk, frequencyOfCheck, shift
       3. NpdMasterDAO.findByChecksheetIdAndDateRange(checksheetId, start, end)
          - Load non-production days for the date range
       4. generateChartData():
          - Iterate day-by-day from startDate to endDate
          - For each day, determine status:
            - Before implementationDate: null (not applicable)
            - Has data with APPROVED status: "Completed" or "Incomplete" (count vs frequency)
            - Has data with IN_PROGRESS/SUBMITTED/etc.: "In Progress"
            - NPD day: "NPD" (with remarks)
            - Future date: "Planned"
            - Past date, no data: "Missed"
          - For SHIFT frequency: breakdown per shift from LOV "shifts"
       5. Calculate summary counts: planned, completed, inProgress, missed, incomplete, npd
       6. Return List<PlanVsActualDTO>
```

### 9c. Trend Chart

**Entry point:** `POST /api/dashboard/trendChart`

```
DashboardController.getTrendChartData(TrendChartDTO)
  -> DashboardServiceImpl.getTrendChartData(TrendChartDTO)
       1. Validate: startDate, endDate, checksheetIds, chksQuestionIds
       2. ChksQuestionResultDAO.getChksQuestionResultByChecksheetId(checksheetId)
       3. DashboardDAO.getTrendChartData(trendChartDTO)
          - Native query: joins user_checksheet_answer, user_checksheet, usr_chksheet_ans_judgement
       4. Group data by questionId
       5. For OBJECTIVE answer type:
          - Parse numeric answers
          - Calculate statistics: min, max, avg, sigma
          - Calculate process capability: CP, CPL, CPU, CPK
       6. Return List<TrendChartGroupDTO>
```

### 9d. Completion Funnel

**Entry point:** `POST /api/dashboard/getCompletionFunnelData`

```
-> DashboardServiceImpl.getCompletionFunnelData(CompletionFunnelDTO)
     1. Get user, roles, allowed section IDs
     2. DashboardDAO.getCompletionFunnelData(dto, sectionIds, loginUser)
        - Counts user_checksheets by status: planned, in_progress, submitted,
          validated, approved, rejected
     3. Calculate percentages for each status
```

### 9e. Compliance Heatmap, Recent Submissions, Top Non-Conforming Questions

Similar pattern: get user scope -> call DashboardDAO with native query -> return DTOs.

---

## 10. File Upload to S3 / Local Storage

The system supports both AWS S3 and local file storage. The current active path uses `FileStorageUtil` (local storage), while `AWSS3Service` is the S3 implementation.

### S3 Upload Path

```
AWSS3ServiceImpl.uploadMultipartFile(MultipartFile file, String filename)
  1. Create ObjectMetadata with content length
  2. PutObjectRequest(bucketName, filename, inputStream, metadata)
  3. amazonS3.putObject(request)

AWSS3ServiceImpl.uploadFile(File file, String filename)     -- @Async
  1. PutObjectRequest(bucketName, filename, file)
  2. amazonS3.putObject(request)
```

### Local File Storage Path

```
FileStorageUtil.storeFile(MultipartFile file, String filename)
  - Stores to configured local directory
  - Returns relative path

FileStorageUtil.getFileURL(String path)
  - Returns URL for accessing the stored file

FileStorageUtil.copyFile(String sourcePath, String destPath)
  - Copies file locally (used in checksheet versioning)
```

### S3 Download / URL Generation

```
AWSS3ServiceImpl.getDocs(String imagePath)
  1. Create GeneratePresignedUrlRequest with 50-minute expiration
  2. amazonS3.generatePresignedUrl(request)
  - Returns pre-signed URL

AWSS3ServiceImpl.downloadFileFromS3(String objectKey)
  1. amazonS3.getObject(bucketName, objectKey)
  2. Stream content to ByteArrayOutputStream
  3. Return byte[]
```

### File Association with Entities

Files are associated with entities through dedicated tables:
- `chks_header_data_file` -- files attached to header data items
- `chks_question_file` -- files attached to questions
- `usr_chksheet_ans_judgement_file` -- files attached to judgement records

Each file record stores a `path` field (the S3 key or local path).

---

## 11. Email / Notification Flow

### Email System

**Service:** `EmailService` (in `com.checkSheet.service.Email`)
**Async execution:** All email methods use `@Async` with dedicated thread pools:
- `threadPoolTaskExecutorForEmail` -- for simple emails
- `threadPoolTaskExecutorForEmailWithAttachment` -- for emails with attachments

### Email Flow

```
1. Trigger point (e.g., ChecksheetServiceImpl, UserChecksheetApprovalServiceImpl)
   -> UtilityService.sendEmail(userIds, EmailTemplate, checksheet, senderUserIds)

2. UtilityServiceImpl.sendEmail():
   a. UserRepository.findByIdIn(userIds)       -- resolve recipients
   b. UserRepository.findByIdIn(senderUserIds) -- resolve sender names
   c. For each user with email:
      - Replace template placeholders:
        [ENVIRONMENT_DOMAIN], [NAME], [SENDER_NAMES], [CHKS_NAME]
      - EmailService.sendTextMail(email, subject, content, cc, bcc)

3. EmailService.sendEmail(to, subject, text, ...):   -- @Async
   a. Create MimeMessage via JavaMailSender
   b. Set to, subject, HTML text, cc, bcc, from
   c. emailSender.send(message)
   d. Retry logic: MAX_RETRIES=3, exponential backoff (3s, 6s, 12s)
   e. Controlled by is_email_send property (disabled in non-prod)
```

### Email Templates (EmailTemplate enum)

Key templates used across flows:
- `NEW` -- New checksheet created, sent to preparer
- `SUBMITTED` -- Submitted for validation, sent to validators
- `INVALIDATED` -- Validator rejected, sent to preparer
- `VALIDATED` -- All validators approved, sent to approvers
- `NOT_APPROVED` -- Approver rejected, sent to preparer
- `APPROVED` -- All approvers approved, sent to validators + section head + preparer
- `USER_CHKS_SUBMITTED` -- Operator submitted fill, sent to data validators
- `USER_CHKS_VALIDATED` -- Data validators approved, sent to data approvers

### Alert Emails (NOT OK Judgements)

Sent from `UserChecksheetApprovalServiceImpl.addUserChecksheetApproval()` when:
1. User checksheet is APPROVED
2. There are NOT OK judgements in `usr_chksheet_ans_judgement`
3. Checksheet has `alertToUserIds` configured

Content: HTML table with question hierarchy, specifications, results, judgement, date, remarks, operator name.

### Escalation Emails

Sent when consecutive NOT OK judgements exceed `escalationGuidelinesDays`:
1. `UserChecksheetDAO.getEscalateToUserCheckList(chksId, escalationDays)` -- finds user checksheets with consecutive NOT OK
2. HTML email with detailed table sent to `escalateToUserIds`

### Push Notifications (Firebase Cloud Messaging)

**Service:** `FCMInitializerServiceImpl`
**Status:** Currently disabled (returns "Notification send currently stop")

When enabled:
```
FCMInitializerServiceImpl.sendPushNotification(token, title, message, data, user, ...)
  1. Initialize Firebase if not already done
  2. Build Notification with title, body, optional image
  3. Build FCM Message with token, notification, data payload
  4. FirebaseMessaging.getInstance().send(message)
```

---

## 12. Permission Check Flow

Every authenticated request passes through a multi-layered authorization system.

### Layer 1: Security Filter Chain

**File:** `SecurityConfiguration.java`

```
SecurityFilterChain:
  1. CSRF disabled
  2. Whitelist endpoints (permitAll): /api/user/register, /api/user/login,
     /api/user/refreshToken, /api/checksheet/doc/**, swagger paths
  3. OPTIONS requests: permitAll
  4. All other requests: authenticated
  5. Stateless session management
  6. JwtAuthenticationFilter added before UsernamePasswordAuthenticationFilter
```

### Layer 2: JWT Authentication Filter

**File:** `JwtAuthenticationFilter.java`

```
JwtAuthenticationFilter.doFilterInternal(request, response, filterChain):
  1. Extract Authorization header
  2. If no "Bearer " prefix: pass through (unauthenticated)
  3. processAuthentication():
     a. Extract JWT from header (substring after "Bearer ")
     b. JwtService.extractUsername(jwt)  -- decode Claims.subject
     c. UserRepository.findByUsernameIgnoreCase(username)
        - If not found: 401 "User not found"
     d. Extract permissions from JWT:
        JwtService.extractPermissions(jwt) -- claims.get("permissions")
     e. Extract roles from JWT:
        claims.get("roles")
     f. Fallback: if permissions/roles empty in token, load from database:
        - PermissionService.getEffectivePermissionsForUser(userId)
        - UserRoleDepartmentDAO.getUserByEmailAndUserId(userId)
     g. Create CustomUserDetails(user, permissions, roles)
        - Authorities include "perm:PERMISSION_CODE" and "ROLE_ROLE_CODE"
     h. isAuthorizedForEndpoint(requestURI, userPermissions):
        - Look up requestURI in endpointPermissionMap (from ApiEndpointConfig)
        - If not in map: allow (no specific permission required)
        - If in map: user must have at least one required permission (OR logic)
        - If unauthorized: 403 with JSON error
     i. JwtService.isTokenValid(jwt, userDetails):
        - Check username matches
        - Check token not expired
     j. Set SecurityContextHolder authentication:
        UsernamePasswordAuthenticationToken(userDetails, null, authorities)
  4. filterChain.doFilter(request, response)
```

### Layer 3: Endpoint Permission Map

**File:** `ApiEndpointConfig.java`

Defines which permissions are required for specific endpoints:

```
/api/department/createDepartment -> [DEPARTMENT_CREATE, SUBDEPARTMENT_CREATE]
/api/user/createOrEditOperator   -> [USER_CREATE, USER_EDIT]
/api/user/deleteUser             -> [USER_DELETE]
/api/checksheet/createChecksheetVersion -> [CHECKSHEET_MANAGEMENT_DETAIL_CREATE, ...]
/api/chksGeneralField/createChksGeneralField -> [CHECKSHEET_MANAGEMENT_TEMPLATE_CREATE, ...]
/api/role/createRole             -> [ROLE_CREATE]
/api/permission/createPermission -> [PERMISSION_CREATE]
... (full map in ApiEndpointConfig.java)
```

Endpoints NOT in the map require only authentication (no specific permission).

### Layer 4: Service-Level Permission Checks

Many services perform additional permission checks:

```
ChecksheetServiceImpl.createChecksheet():
  PermissionService.hasPermission(userId, "CHECKSHEET_MANAGEMENT_DETAIL_CREATE")
  PermissionService.hasPermission(userId, "CHECKSHEET_MANAGEMENT_DETAIL_EDIT")

UserServiceImpl.updatePassword():
  PermissionService.hasPermission(userId, "CHECKSHEET_FILL_ANSWER")

UserServiceImpl.resetPassword():
  PermissionService.hasPermission(userId, "USER_PASSWORD_RESET")

DashboardServiceImpl (all methods):
  PermissionService.getAllowedSectionIds(userId) -- scope data by user's sections
```

### Permission Resolution

**File:** `PermissionServiceImpl.java`

```
getEffectivePermissionsForUser(userId):
  1. UserRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(userId)
  2. For each UserRoleDepartment:
     - resolvePermissionsWithHierarchy(role)
       - RolePermissionRepository.findPermissionCodesByRoleId(roleId)
       - (Parent role hierarchy traversal disabled in current code)
  3. Merge all permission codes into Set<String>

hasPermission(userId, permissionCode):
  - getEffectivePermissionsForUser(userId).contains(permissionCode)

getAllowedDepartmentIds(userId):
  - Returns null for global access (user has null department assignment)
  - Returns Set of department IDs + parent department IDs
  - Returns empty list for no access

getAllowedSectionIds(userId):
  - Similar, but only returns sections (departments with parent departments)
```

---

## 13. Excel Export

Multiple endpoints generate Excel exports using Apache POI (SXSSFWorkbook for streaming).

### 13a. User Checksheet with Answers

**Entry point:** `POST /api/userChecksheet/downloadUserChecksheetWithAnswers`

```
UserChecksheetController.downloadUserChecksheetWithAnswers(UserChecksheetDTO, HttpServletResponse)
  1. Set response headers:
     - Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
     - Content-Disposition: attachment; filename=UserChecksheet.xlsx
  2. UserChecksheetServiceImpl.downloadUserChecksheetWithAnswers(dto, response)
     a. Load checksheet data:
        - Checksheet headers, questions, results, options, matrices
     b. Load user answers, judgements, trace values
     c. Create SXSSFWorkbook (streaming workbook, 1000 row window)
     d. Create sheet with headers
     e. For each question:
        - Write question name, specifications, answers
        - For MATRIX type: create linked sub-sheet with matrix data
     f. Auto-size columns
     g. Write to response OutputStream
     h. Close workbook and stream
```

### 13b. Checksheet Summary Data Export

**Entry point:** `POST /api/dashboard/downloadChecksheetSummaryData`

```
DashboardController.downloadChecksheetSummaryData(DashboardDTO, HttpServletResponse)
  -> DashboardServiceImpl.downloadChecksheetSummaryData(DashboardDTO, response)
       1. Validate dates, question IDs
       2. Load questions, headers (result columns), question results, options
       3. Load user answers and judgements via DashboardDAO queries
       4. Create SXSSFWorkbook
       5. Write header rows: Date Range, parameters
       6. Write column headers: Sr. No., Question Header, Spec columns, Answer columns,
          Traceability, Submission Date/Time, Judgement, Operator
       7. For each judgement record:
          - Write question name
          - For each result header:
            - SUBJECTIVE_CONDITION: write specification and selected option
            - OBJECTIVE: write spec (type, range, unit, count) and numeric answer
            - SUBJECTIVE: write answer text
            - MATRIX: create hyperlinked sub-sheet via createMatrixSheet()
            - NA: write "NA"
          - Write traceability, date, time, judgement, operator
       8. Auto-size, write to output, close
```

### 13c. Trend Chart Data Export

**Entry point:** `POST /api/dashboard/downloadTrendChartData`

```
DashboardController.downloadTrendChartData(TrendChartDTO, HttpServletResponse)
  -> DashboardServiceImpl.downloadTrendChartData(TrendChartDTO, response)
       1. Get trend chart data (reuses getTrendChartData())
       2. Create workbook with:
          - Date range, parameters, question/header info, spec details
          - For OBJECTIVE: MIN, MAX, AVERAGE, SIGMA, Cp, Cpl, Cpu, Cpk
          - Data rows: Sr. No., Submission Date/Time, Traceability,
            Answer(s) (split by separator for multiple results), Judgement, Operator
```

### 13d. Plan vs Actual Data Export

**Entry point:** `POST /api/dashboard/downloadPlanVsActualData`

```
DashboardController.downloadPlanVsActualData(DashboardDTO, HttpServletResponse)
  -> DashboardServiceImpl.downloadPlanVsActualData(DashboardDTO, response)
       1. Get plan vs actual data (reuses getPlanVsActualData())
       2. Create workbook with:
          - Date range, frequency
          - Columns: Checksheet Name, Planned, Missed, In-progress, Incomplete,
            Completed, NPD, then one column per date
          - Per date: status text; for SHIFT: "Shift1: Status\nShift2: Status"
          - NPD dates include remarks in parentheses
```

### Matrix Sub-Sheet (createMatrixSheet)

Used by summary and trend exports for MATRIX-type question results:

```
createMatrixSheet(workbook, sheetName, questionResult, matrices, userAnswers)
  1. Create new sheet in workbook
  2. Write matrix header: name spanning rows 0-1, cols 0-1
  3. Write column header name, individual column names
  4. Write row data: row name, cell data from ChksQuestionResultMatrix
  5. Write "Number of Results"
  6. Write answer data table: #, Row, Col, Result, M/C Result, Judgement
```

### Common POI Patterns

- `SXSSFWorkbook(1000)` -- streaming workbook with 1000-row memory window
- `sheet.trackAllColumnsForAutoSizing()` -- enable auto-size
- `setCellStyle()` -- creates font (size 11, bold for headers)
- `createCell()` -- handles Integer, Long, Double, String, Date, Boolean, null
- Hyperlinks: `HyperlinkType.DOCUMENT` to link matrix cells to sub-sheets

---

## File Reference Index

### Controllers
- `controller/UserController.java` -- User registration, login, refresh, CRUD
- `controller/ChecksheetController.java` -- Checksheet CRUD, file access, status updates
- `controller/ChecksheetValidationController.java` -- Checksheet validation
- `controller/ChecksheetApprovalController.java` -- Checksheet approval
- `controller/UserChecksheetController.java` -- User checksheet fill, answers, judgements
- `controller/UserChecksheetValidationController.java` -- Data validation
- `controller/UserChecksheetApprovalController.java` -- Data approval
- `controller/DashboardController.java` -- Dashboard metrics and exports

### Services (Implementations)
- `service/UserServiceImpl.java` -- Auth, user management, permission/menu resolution
- `service/ChecksheetServiceImpl.java` -- Checksheet lifecycle, versioning, cloning
- `service/ChecksheetValidationServiceImpl.java` -- Checksheet validation logic
- `service/ChecksheetApprovalServiceImpl.java` -- Checksheet approval logic
- `service/UserChecksheetServiceImpl.java` -- Fill flow, answers, judgements, exports
- `service/UserChecksheetValidationServiceImpl.java` -- Data validation logic
- `service/UserChecksheetApprovalServiceImpl.java` -- Data approval + alert/escalation emails
- `service/DashboardServiceImpl.java` -- Dashboard queries, chart data, Excel exports
- `service/RefreshTokenServiceImpl.java` -- Refresh token generation
- `service/PermissionServiceImpl.java` -- Permission resolution, RBAC
- `service/AWSS3ServiceImpl.java` -- S3 file operations
- `service/UtilityServiceImpl.java` -- Current user, email dispatch, helpers
- `service/Email/EmailService.java` -- Async email sending with retry
- `service/Notification/FCMInitializerServiceImpl.java` -- FCM push notifications (disabled)

### Config
- `config/SecurityConfiguration.java` -- Spring Security filter chain, whitelist
- `config/JwtAuthenticationFilter.java` -- JWT extraction, permission check, auth context
- `config/JwtService.java` -- JWT creation, validation, claims extraction
- `config/ApiEndpointConfig.java` -- Endpoint-to-permission mapping
- `config/EndpointRegistry.java` -- Runtime endpoint discovery
- `config/CustomUserDetails.java` -- UserDetails with permissions and roles
- `config/LogoutService.java` -- Clears SecurityContext on logout
- `config/ApplicationConfig.java` -- AuthenticationProvider, PasswordEncoder beans

### DAOs (Native SQL Queries)
- `DAO/ChecksheetDAO.java` -- Checksheet queries with pagination
- `DAO/DashboardDAO.java` -- Dashboard data aggregation queries
- `DAO/UserChecksheetDAO.java` -- User checksheet queries
- `DAO/UserRoleDepartmentDAO.java` -- Role/permission queries
- `DAO/UserDAO.java` -- User search queries
- Plus domain-specific DAOs for headers, questions, results, etc.


---

# Intervention lifecycle flow (V1.27+)

## Audit-side approval triggers plan creation

```
1. Operator submits an audit UC (status SUBMITTED → VALIDATED → APPROVED).
2. UserChecksheetApprovalServiceImpl, on final APPROVE:
     publishes UserChecksheetApprovedEvent (auditId, locId, aaId, iaId=null).
3. InterventionInstantiationListener.onApproved (AFTER_COMMIT, async):
     - finds InterventionAssignmentTargets where audit_assignment_id = aaId
     - for each ACTIVE intervention with question-scope ∩ failing-answers ≠ ∅:
         creates intervention_assignment (status=PENDING)
         creates intervention_assignment_questions for the overlap
4. (Optional) InterventionAssignmentNotificationService stub logs the event.
```

## Dealer Principal acknowledges

```
1. POST /api/intervention-assignment/{id}/acknowledge
   - guarded: caller must be the dealer_principal_user on the underlying aa
   - flips status PENDING → IN_PROGRESS, stamps acknowledged_at + by
```

## Re-inspection wave

```
1. Operator opens their assignment list — re-inspection plans appear there
   alongside original audits.
2. Operator fills a UC pointing at the intervention_assignment (instead of
   audit_assignment). Same /question screens; same submit flow.
3. UserChecksheetApprovalServiceImpl approves it →
     publishes UserChecksheetApprovedEvent (iaId set, aaId=null).
4. InterventionInstantiationListener.onApproved:
     - calls evaluatePlanCompletion(uc)
     - if every tracked question on the plan is OK on this UC: status=COMPLETED.
```

## Daily overdue sweep

`InterventionOverdueScheduler` runs at 02:30 server-local. Plans past
target_date in PENDING/IN_PROGRESS flip to NON_COMPLIANT with
`closure_reason = "Auto-closed: target_date passed without completion"`.

## BI rollup

Every read endpoint goes through `audit_signal`. Per
`(audit_assignment, chks_question_result_id)` the latest APPROVED answer wins
across audit UC + every intervention UC on the assignment. So a dealer
who originally scored 67% but later closed a P1 with 5 question flips
shows 81% across:
- `/api/audit/{id}/stats/national|region|dealer|location`
- `/api/audit/{id}/intervention-summary/...`
- `/api/audit/userChecksheet/{ucId}/improvement-overlay` (originalScore + currentScore)
