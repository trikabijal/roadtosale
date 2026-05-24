# Audit Pro -- Facade API Reference

Backend REST API for the Audit Pro (formerly SmartComply) digital checksheet and audit management system.

**Stack:** Spring Boot 3.1.4, Java 17, PostgreSQL, JWT Authentication, AWS S3, Firebase Notifications, Apache POI

**Base URL:** `/api`

---

## Audit + BI endpoints (added in V1.24+)

These were added with the Audit-entity model and are not yet folded into the
section listing further down. See `architecture.md` for the data model.

### Audit management — `/api/audit/*`

| Method | Path | Body / Params | Returns |
|---|---|---|---|
| `POST` | `/createAudit` | `{ name, checksheetId, startDate, endDate }` | created `AuditDTO` (with id) |
| `POST` | `/addAuditAssignments` | `{ auditId, assignments: [{ auditeeLocationId, operatorUserId, dealerPrincipalUserId? }] }` — `dealerPrincipalUserId` is optional (V1.30+). When null, the resulting Inspection has no DP stamped, which cascades to interventions: the dealer-principal acknowledge flow on those plans then 403s. | count added (idempotent on `(audit_id, location_id)` for active rows) |
| `GET`  | `/list` | — | `AuditDTO[]` with stats (totalLocations, done, inProgress, notStarted) |
| `GET`  | `/{auditId}` | — | full `AuditDTO` including assignment list |

#### `AuditAssignmentCreateDTO` (entry shape inside `assignments[]`)

```
{
  auditeeLocationId: Long,         // required
  operatorUserId: Long,            // required — the field auditor for this location
  dealerPrincipalUserId: Long      // optional, V1.30+ — stamped onto Inspection.dealer_principal_user;
                                   //   required for the DP to later acknowledge interventions
                                   //   derived from this Inspection. Omit if not yet known.
}
```

### BI stats — `/api/audit/{auditId}/stats/*`

All four return the same `AuditStatsDTO` shape (`scope`, `scopeLabel`, totals, `bands`, `whatsFailing`, `topFailingCheckpoints`, `table`, `redDealers`, `aiInsights`). The scope filter is the only thing that varies; backend uses one shared CTE under the hood (see `AuditServiceImpl.scopeCte`).

| Method | Path |
|---|---|
| `GET` | `/{auditId}/stats/national` |
| `GET` | `/{auditId}/stats/region/{regionId}` |
| `GET` | `/{auditId}/stats/dealer/{auditeeId}` |
| `GET` | `/{auditId}/stats/location/{locationId}` |
| `GET` | `/userChecksheetMeta?userChecksheetId=N` — header context (location/dealer/region names + score) for the BI audit-report page. Gated by `assertUserChecksheetVisible` — see "Audit-report authorization model". |
| `GET` | `/{auditId}/stats/category-drill?category={categoryName}` — for one category (e.g. "EV & Sustainability"), per-region + per-dealer failure rates so the "What's Failing" panel can drill into worst performers |

### Intervention BI summary — `/api/audit/{auditId}/intervention-summary/*` (V1.27+)

Same scope ladder as `stats/*`. Reads the same `audit_signal` CTE so totals stay consistent with the rest of the BI.

| Method | Path |
|---|---|
| `GET` | `/{auditId}/intervention-summary/national` |
| `GET` | `/{auditId}/intervention-summary/region/{regionId}` |
| `GET` | `/{auditId}/intervention-summary/dealer/{auditeeId}` |
| `GET` | `/{auditId}/intervention-summary/location/{locationId}` |

Returns: `{ activeCampaigns, totalAssignments, p1CompletionRate, dealersWithCompletedP1, dealersNonCompliantP1, networkScoreDelta, networkScoreDeltaDealerCount, topCampaigns:[{id,name,priority,planCount,planCompletedCount}], redDealersWithPlan, redDealersWithoutPlan }`.

### Mobile auditor — `/api/audit/myAssignments`

| Method | Path | Returns |
|---|---|---|
| `GET` | `/myAssignments` | `Assignment[]` for the current operator. Each row carries `assignmentId`, `auditId`, `auditName`, `checksheetId`, `checksheetName`, `locationLabel`, `userChecksheetId` (nullable), `status`, `answeredQuestions`, `totalQuestions`. Ordered: IN_PROGRESS → SCHEDULED → others. |

### UserChecksheet (refactored for audit campaigns)

`POST /userChecksheet/createOrUpdate` now accepts `auditAssignmentId` (server resolves audit + location). The endpoint takes a **List<DTO>** even for one row; required fields: `auditAssignmentId` (or `interventionAssignmentId` for re-inspection waves), `status`, `shift`, `submissionVersion`, `frequencyOfFreqOfChkCnt`, `startedAt`. Returns the saved DTOs in the standard response envelope's `data[]`.

**V1.28+ — legal status transitions enforced server-side.** The operator path no longer trusts the caller-supplied `status`. The service compares the existing inspection's status (`from`) to the DTO's `status` (`to`) and rejects anything outside this table with **HTTP 403 `Illegal status transition from <from> to <to>`**:

| From | To | Meaning |
|---|---|---|
| `ASSIGNED` | `IN_PROGRESS` | start the inspection |
| `ASSIGNED` | `ASSIGNED` | no-op (idempotent save) |
| `IN_PROGRESS` | `IN_PROGRESS` | save partial progress |
| `IN_PROGRESS` | `SUBMITTED` | submit for validation |
| `DECLINED` | `IN_PROGRESS` | reopen after a decline |

Any other transition (e.g. `IN_PROGRESS → APPROVED`, `SUBMITTED → IN_PROGRESS`) is treated as privilege escalation. Legacy `INVALIDATED` / `NOT_APPROVED` are not accepted from this path (they were collapsed into `IN_PROGRESS` by V1.28). Missing/blank `status` returns **422**.

**Ownership + editability guard on write endpoints.** All 7 write endpoints that mutate an existing inspection (`createOrUpdate` UPDATE path, `createOrUpdateUserChksAns`, `createOrUpdateUserChksMtrxAns`, `createOrUpdateUserChksTraceValues`, `createOrUpdateUserChksJudgements`, `createOrUpdateUserChksJudgementFile` / `deleteUserChksJudgementFile`, `createUserChksAnsFile` / `deleteUserChksAnsFile`, `createOrUpdateUserChksGnrlFieldVals`) call `assertOperatorCanEditInspection(inspectionId)`, which requires both:

- caller is the `operator_user_id` on the parent inspection — otherwise **403 `You are not authorized to modify this audit`**, AND
- parent inspection's status is NOT in `{SUBMITTED, VALIDATED, APPROVED}` — otherwise **403 `Inspection is not in an editable state (status=<status>)`**.

This closes the window in which an operator could silently edit answers/photos on a UC already moving through the validator/approver pipeline.

`POST /userChecksheet/createUserChksAnsFile` (multipart) attaches photo evidence to **any** answer type, not just `FILE_UPLOAD`. The read-side
`getUserChecksheetWithAnswers` surfaces files on `chksQuestionResults[].userChecksheetAnswerFiles[]` for SUBJECTIVE_CONDITION and OBJECTIVE answers too. **Gated by `assertUserChecksheetVisible`** — see "Audit-report authorization model".

**V1.27 — file upload validation on `createUserChksAnsFile`.** Two layered checks:

| Status | When | Configurable via |
|---|---|---|
| `413 PAYLOAD_TOO_LARGE` | File size exceeds `app.upload.max-size-mb` (default 10MB). Caught either by `MultipartUploadExceptionHandler` (parser layer; `spring.servlet.multipart.max-file-size`, default 11MB) or by the service-layer `validateUpload` check before hashing/thumbnailing. | `app.upload.max-size-mb`, `spring.servlet.multipart.max-file-size`, `spring.servlet.multipart.max-request-size` |
| `415 UNSUPPORTED_MEDIA_TYPE` | Content type is not on the whitelist (default `image/jpeg,image/png,image/webp`). Body: `Unsupported content type: <type>. Allowed: <list>`. | `app.upload.allowed-content-types` |

Same handler covers `createOrUpdateUserChksJudgementFile`.

### Intervention management — `/api/intervention/*` (V1.27+)

| Method | Path | Body / Params | Returns |
|---|---|---|---|
| `POST` | `/createDraft` | `InterventionCreateDTO` (name, theme, priority, auditId, targetDate, questionIds[], targetingMode, target*) | created `InterventionDTO` in DRAFT |
| `PUT`  | `/{id}` | `InterventionUpdateDTO` | updated draft |
| `POST` | `/{id}/setQuestions` | `Long[]` chksQuestionIds | replaces the question set |
| `POST` | `/{id}/addQuestions` | `Long[]` chksQuestionIds | union with existing |
| `POST` | `/{id}/activate` | optional targeting DTO | activates the campaign; returns 409 + conflict list if questions overlap an active rival |
| `GET`  | `/{id}/activationConflicts` | — | preview the conflict list without activating |
| `POST` | `/{id}/close` | — | closes ACTIVE → CLOSED |
| `DELETE` | `/{id}` | — | soft-deletes a draft |
| `GET`  | `/list` | — | every active intervention with progress fields |
| `GET`  | `/{id}` | — | single intervention with full target + question lists |
| `GET`  | `/audit/{auditId}/claimedQuestions` | — | **new** — questions already covered by ACTIVE/DRAFT interventions in this audit; one row per `(chksQuestionId × interventionId)`. Drives the "New Intervention" form's question-picker filter so the user only sees still-targetable questions. |

### Audit-report overlay — `/api/audit/userChecksheet/{id}/improvement-overlay`

Per-question intervention chain + score deltas for the audit-report page. The Angular `AuditReportComponent` calls this alongside `getUserChecksheetWithAnswers` and `userChecksheetMeta`.

Response (`data` shape):
```
{
  userChecksheetId,
  originalScore,          // % when only the original audit's answers count
  currentScore,           // % rolled up across this UC + any intervention-kind inspections at the same location
  scoreDelta,             // currentScore − originalScore
  questionFlags: {        // keyed by chksQuestionId (stringified)
    "197": [{ interventionId, campaignName, priority, interventionStatus }, ...]
  },
  reAuditAnswers: {       // keyed by chksQuestionId
    "197": [{
      reAuditAnswerId,    // user_checksheet_answers.id of the re-inspection row
      reAuditId,          // inspection id (kind=INTERVENTION) the re-inspection belongs to
      interventionId,
      interventionName,
      auditorName,
      judgement,          // 1 (OK) | 2 (NOT OK)
      comment,            // remarks from usr_chksheet_ans_judgements
      answeredAt,
      photoUrls           // resolved absolute URLs to /api/checksheet/doc/...
    }, ...]
  },
  headerSnapshots: [      // one per intervention with at least one APPROVED re-inspection here
    { interventionId, interventionName, completedAt, wavesCount }
  ]
}
```

### Audit-report authorization model

All three endpoints behind the audit-report page (`getUserChecksheetWithAnswers`, `userChecksheetMeta`, `improvement-overlay`) share the same scope gate. A caller can read a single user_checksheet by id only if one of these holds:

- caller has the `AUDIT_VIEW` permission (Department Head, Section Head, Super Admin), **OR**
- caller is the operator who filled this user_checksheet (`operator_user_id` matches), **OR**
- caller is the Dealer Principal of this UC's dealership (`dealer_principal_user_id` matches).

Everyone else gets HTTP 403 `You do not have access to this user_checksheet`. The check lives in `UserChecksheetServiceImpl.assertUserChecksheetVisible` and is also enforced inline by `AuditServiceImpl.getUserChecksheetMeta` and `getImprovementOverlay`. The xlsx + PDF download endpoints inherit the gate because they internally delegate to `getUserChecksheetWithAnswers`.

---

## Table of Contents

- [Authentication and Security](#authentication-and-security)
- [Standard Response Envelope](#standard-response-envelope)
- [1. Auth and Users](#1-auth-and-users)
- [2. Departments](#2-departments)
- [3. Master Departments](#3-master-departments)
- [4. Roles](#4-roles)
- [5. Permissions](#5-permissions)
- [6. Checksheets](#6-checksheets)
- [7. Checksheet Validation](#7-checksheet-validation)
- [8. Checksheet Approval](#8-checksheet-approval)
- [9. Checksheet General Fields](#9-checksheet-general-fields)
- [10. Checksheet Headers](#10-checksheet-headers)
- [11. Checksheet Header Data](#11-checksheet-header-data)
- [12. Checksheet Questions](#12-checksheet-questions)
- [13. Checksheet Question Results](#13-checksheet-question-results)
- [14. User Checksheets (Data Entry)](#14-user-checksheets-data-entry)
- [15. User Checksheet Validation](#15-user-checksheet-validation)
- [16. User Checksheet Approval](#16-user-checksheet-approval)
- [17. Surprise Checksheets](#17-surprise-checksheets)
- [18. NPD Master](#18-npd-master)
- [19. Dashboard](#19-dashboard)
- [20. API History (Internal)](#20-api-history-internal)
- [Enums and Constants](#enums-and-constants)
- [Mobile API flow (audit lifecycle)](#mobile-api-flow-audit-lifecycle)
- [DTO Schemas](#dto-schemas)
- [Appendix: Date Format Reference](#appendix-date-format-reference)
- [Appendix: File Upload Endpoints](#appendix-file-upload-endpoints)
- [Appendix: Permission Endpoint Mapping](#appendix-permission-endpoint-mapping)

> For workflow state machines (template lifecycle, user-checksheet lifecycle, intervention lifecycle) and UI rendering / permission-to-UI rules, see [`flows.md`](./flows.md). The state-machine summary table for the operator write path is inlined in [§14 User Checksheets](#14-user-checksheets-data-entry) and the "UserChecksheet (refactored for audit campaigns)" block above.

---

## Authentication and Security

Authentication uses JWT bearer tokens. Tokens are obtained via the login endpoint and refreshed via the refresh token endpoint.

### Login

`POST /api/user/login` (public, no auth required).

Request:
```json
{
  "username": "string",
  "password": "string",
  "deviceType": "WEB"
}
```

`deviceType` is `WEB` for the BI / management console, `APP` for the field-auditor mobile app. Both can be active for the same user concurrently — `refresh_token` is keyed on `(user_id, device_type)` since V1.29.

Response (inside the standard envelope's `data`):
```json
{
  "id": 1,
  "username": "john.doe",
  "firstName": "John",
  "lastName": "Doe",
  "email": "john@example.com",
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
  "allRoles": ["OPERATOR", "DATA_VALIDATOR"],
  "roleNames": { "OPERATOR": "Operator", "DATA_VALIDATOR": "Data Validator" },
  "menus": ["CHECKSHEET_FILL_LISTING", "CHECKSHEET_LISTING"],
  "permissions": ["CHECKSHEET_FILL_LISTING", "CHECKSHEET_FILL_ANSWER"]
}
```

### JWT Token Structure

- **Algorithm:** HS256
- **Subject (`sub`):** username
- **Custom claims:**
  - `permissions` — array of permission code strings (e.g. `["CHECKSHEET_FILL_LISTING", "USER_CREATE"]`)
  - `roles` — array of role code strings (e.g. `["OPERATOR", "SUPER_ADMIN"]`)
- **Issued At (`iat`):** token creation timestamp
- **Expiration:** configurable server-side (varies by environment)

The frontend may decode the JWT (without verifying signature) to read `permissions` / `roles` for UI gating. The backend verifies the signature on every request.

### Sending the Token

All authenticated requests must include:
```
Authorization: Bearer <accessToken>
```

### Token Refresh

`POST /api/user/refreshToken` (public, no auth required).

Request:
```json
{ "refreshToken": "eyJhbGciOiJIUzI1NiJ9..." }
```

Response: same shape as login — returns a new `accessToken` and `refreshToken`.

**When to refresh.** When a request returns HTTP 401, or proactively when the JWT's `exp` claim is about to lapse. The recommended client pattern is a token interceptor that:

1. Catches 401 responses.
2. Calls `refreshToken`.
3. Retries the original request with the new access token.
4. If the refresh call itself fails, redirects to the login screen.

### Logout

Clear the stored tokens client-side. The backend uses stateless JWT — there is no server-side session to invalidate.

### Public Endpoints (No Auth Required)

These endpoints are whitelisted in `SecurityConfiguration.WHITE_LIST_URL`:

| Pattern | Purpose |
|---------|---------|
| `/api/v1/auth/**` | Auth flows |
| `/api/user/register` | User registration |
| `/api/user/login` | User login |
| `/api/user/refreshToken` | Token refresh |
| `/api/user/hello` | Health check |
| `/api/checksheet/doc/**` | Document file serving |
| `/swagger-ui/**`, `/v3/api-docs/**` | Swagger / OpenAPI |

All other endpoints require a valid JWT in the `Authorization: Bearer <token>` header.

### Permission-Based Access Control

Beyond authentication, many endpoints require specific permissions. Permission checks are enforced at the filter level via `ApiEndpointConfig.endpointPermissionMap()`. Key permission codes include:

- **Department:** `DEPARTMENT_CREATE`, `DEPARTMENT_LIST`, `DEPARTMENT_DOWNLOAD`, `SUBDEPARTMENT_CREATE`, `SUBDEPARTMENT_LIST`, `SUBDEPARTMENT_DOWNLOAD`
- **User:** `USER_LIST`, `USER_CREATE`, `USER_EDIT`, `USER_DELETE`, `USER_DOWNLOAD`
- **Role (SUPER_ADMIN):** `ROLE_LIST`, `ROLE_VIEW`, `ROLE_CREATE`, `ROLE_UPDATE`, `ROLE_DELETE`, `ROLE_EDIT`, `ROLE_ASSIGN_PERMISSION`
- **Permission (SUPER_ADMIN):** `PERMISSION_LIST`, `PERMISSION_VIEW`, `PERMISSION_CREATE`, `PERMISSION_UPDATE`, `PERMISSION_DELETE`
- **Checksheet:** `CHECKSHEET_MANAGEMENT_DETAIL_CREATE`, `CHECKSHEET_MANAGEMENT_DETAIL_EDIT`, `CHECKSHEET_MANAGEMENT_TEMPLATE_CREATE`, `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT`, `CHECKSHEET_FILL_LISTING`

A `SUPER_ADMIN` role bypasses all permission filters (server-side). Frontend UI gating should respect the same override — check `allRoles` from the login response for `SUPER_ADMIN` and skip permission checks if present.

For the full endpoint → permission map, see [Appendix: Permission Endpoint Mapping](#appendix-permission-endpoint-mapping).

---

## Standard Response Envelope

All endpoints return a `ResponseDTO<T>` wrapper (null fields are omitted from JSON output):

```json
{
  "status": true,
  "message": "Success message",
  "data": { ... },
  "currentPage": 0,
  "totalPages": 5,
  "pageSize": 10,
  "totalRecords": 50,
  "lastUpdatedTime": "2026-04-23T10:00:00.000+00:00",
  "httpStatus": "OK"
}
```

Error responses:

```json
{
  "status": false,
  "message": "Error description"
}
```

Paginated list responses also use `PagedResponse<T>`:

```json
{
  "content": [ ... ],
  "totalElements": 100,
  "totalPages": 10,
  "page": 0,
  "size": 10
}
```

---

## 1. Auth and Users

**Base path:** `/api/user`

### Endpoints

| Method | Path | Auth | Permissions | Request | Description |
|--------|------|------|-------------|---------|-------------|
| GET | `/hello` | Public | -- | -- | Health check, returns "Hello world" |
| POST | `/register` | Public | -- | `UserDTO` (body) | Register a new user account |
| POST | `/login` | Public | -- | `UserDTO` (body) | Authenticate user, returns JWT tokens |
| POST | `/refreshToken` | Public | -- | `UserDTO` (body) | Refresh an expired access token |
| GET | `/` | Authenticated | -- | `ipn` (query), `pwd` (query) | Legacy login by IPN and password |
| POST | `/createOrEditOperator` | Authenticated | `USER_CREATE`, `USER_EDIT` | `UserDTO` (body) | Create or update an operator user |
| POST | `/deleteUser` | Authenticated | `USER_DELETE` | `UserDTO` (body) | Delete a user |
| POST | `/getOperator` | Authenticated | -- | `UserDTO` (body) | Get a single operator's details |
| POST | `/getAllOperators` | Authenticated | -- | `UserDTO` (body) | Get all operators (filtered) |
| POST | `/getChecksheetPreparers` | Authenticated | -- | `UserDTO` (body) | List users with checksheet preparer role |
| POST | `/getChecksheetValidators` | Authenticated | -- | `UserDTO` (body) | List users with checksheet validator role |
| POST | `/getChecksheetApprovers` | Authenticated | -- | `UserDTO` (body) | List users with checksheet approver role |
| POST | `/getDataValidators` | Authenticated | -- | `UserDTO` (body) | List users with data validator role |
| POST | `/getDataApprovers` | Authenticated | -- | `UserDTO` (body) | List users with data approver role |
| POST | `/getChecksheetOperators` | Authenticated | -- | `UserDTO` (body) | List users with checksheet operator role |
| POST | `/getAlertUsers` | Authenticated | -- | `UserDTO` (body) | List users eligible for alert assignment |
| POST | `/getEscalationUsers` | Authenticated | -- | `UserDTO` (body) | List users eligible for escalation assignment |
| POST | `/validateAndSaveUsername` | Authenticated | -- | `UserDTO` (body) | Validate username uniqueness and save |
| POST | `/updatePassword` | Authenticated | -- | `UserDTO` (body) | Update the current user's password |
| POST | `/resetPassword` | Authenticated | -- | `UserDTO` (body) | Reset a user's password (admin action) |

---

## 2. Departments

**Base path:** `/api/department`

### Endpoints

| Method | Path | Auth | Permissions | Request | Description |
|--------|------|------|-------------|---------|-------------|
| POST | `/createDepartmentAdmin` | Authenticated | `DEPARTMENT_CREATE` | `DepartmentDTO` (body) | Create a department with admin assignment |
| POST | `/deleteDepartmentAdmin` | Authenticated | -- | `DepartmentDTO` (body) | Delete a department admin assignment |
| POST | `/editDepartmentAdmin` | Authenticated | -- | `DepartmentDTO` (body) | Edit a department admin assignment |
| POST | `/createOrEditSectionAndAdmin` | Authenticated | `SUBDEPARTMENT_CREATE` | `DepartmentDTO` (body) | Create or edit a section (sub-department) with admin |
| POST | `/getDepartments` | Authenticated | `DEPARTMENT_LIST` | `DepartmentDTO` (body) | Get paginated departments (filtered) |
| POST | `/getSectionHead` | Authenticated | -- | `DepartmentDTO` (body) | Get the section head for a department |
| POST | `/searchDepartments` | Authenticated | `DEPARTMENT_LIST` | `DepartmentDTO` (body) | Search departments with pagination |
| POST | `/downloadDepartments` | Authenticated | `DEPARTMENT_DOWNLOAD` | `DepartmentDTO` (body) | Download departments as Excel (.xlsx) |
| POST | `/searchSections` | Authenticated | `SUBDEPARTMENT_LIST` | `DepartmentDTO` (body) | Search sections with pagination |
| POST | `/downloadSections` | Authenticated | `SUBDEPARTMENT_DOWNLOAD` | `DepartmentDTO` (body) | Download sections as Excel (.xlsx) |
| POST | `/searchUsers` | Authenticated | `USER_LIST` | `DepartmentDTO` (body) | Search users within departments |
| POST | `/downloadUsers` | Authenticated | `USER_DOWNLOAD` | `DepartmentDTO` (body) | Download user list as Excel (.xlsx) |
| POST | `/getSections` | Authenticated | -- | `DepartmentDTO` (body) | Get sections for a department |
| POST | `/getUserSections` | Authenticated | -- | `DepartmentDTO` (body) | Get sections accessible to current user |
| GET | `/getDepartments` | Authenticated | -- | -- | Get all departments (simple list) |
| GET | `/getUserDepartmentsOrSections` | Authenticated | -- | -- | Get departments/sections for current user |
| GET | `/getUserDepartmentsForList` | Authenticated | -- | -- | Get user departments for list display |
| GET | `/getUserDepartments` | Authenticated | -- | -- | Get departments assigned to current user |
| GET | `/getDeptAdminDepartments` | Authenticated | -- | -- | Get departments where current user is admin |
| GET | `/getUserRoleDepartment/{userRoleDepartmentId}` | Authenticated | -- | Path: `userRoleDepartmentId` (Long) | Get user-role-department mapping by ID |
| GET | `/user-details/{userId}` | Authenticated | -- | Path: `userId` (Long) | Get user role and department details |

---

## 3. Master Departments

**Base path:** `/api/master-department`

Pure CRUD operations for master department entities (separate from department admin assignment).

### Endpoints

| Method | Path | Auth | Permissions | Request | Description |
|--------|------|------|-------------|---------|-------------|
| POST | `/search` | Authenticated | -- | `MasterDepartmentDTO` (body) | Search master departments with pagination |
| POST | `/get` | Authenticated | -- | `MasterDepartmentDTO` (body) | Get single master department with sections |
| POST | `/create` | Authenticated | -- | `MasterDepartmentDTO` (body) | Create a new master department |
| POST | `/update` | Authenticated | -- | `MasterDepartmentDTO` (body) | Update master department name |
| POST | `/delete` | Authenticated | -- | `MasterDepartmentDTO` (body) | Delete master department (blocked if sections exist) |
| GET | `/dropdown` | Authenticated | -- | -- | Get all master departments for dropdown (simple list) |
| POST | `/{masterDepartmentId}/section/create` | Authenticated | -- | Path: `masterDepartmentId` (Long), Body: `MasterDepartmentSectionDTO` | Create section under a master department |
| POST | `/section/update` | Authenticated | -- | `MasterDepartmentSectionDTO` (body) | Update section name |
| POST | `/section/delete` | Authenticated | -- | `{ "id": Long }` (body) | Delete section (blocked if users/checksheets assigned) |

---

## 4. Roles

**Base path:** `/api/role`

### Endpoints

| Method | Path | Auth | Permissions | Request | Description |
|--------|------|------|-------------|---------|-------------|
| GET | `/getRoles` | Authenticated | `USER_LIST`, `USER_CREATE`, `USER_EDIT` | -- | Get all roles |
| POST | `/getRolesByDepartments` | Authenticated | -- | `DepartmentDTO` (body) | Get roles filtered by departments |
| POST | `/searchRoles` | Authenticated | `ROLE_LIST` | `RoleDTO` (body) | Search roles with pagination |
| POST | `/getRole` | Authenticated | `ROLE_VIEW`, `ROLE_EDIT` | `RoleDTO` (body) | Get single role details |
| POST | `/createRole` | Authenticated | `ROLE_CREATE` | `RoleDTO` (body) | Create a new role with permissions |
| POST | `/updateRole` | Authenticated | `ROLE_UPDATE` | `RoleDTO` (body) | Update an existing role |
| POST | `/deleteRole` | Authenticated | `ROLE_DELETE` | `RoleDTO` (body) | Soft-delete a role |
| GET | `/getAllPermissions` | Authenticated | `ROLE_CREATE`, `ROLE_UPDATE`, `ROLE_ASSIGN_PERMISSION` | -- | Get all permissions for role assignment |
| POST | `/getPermissionsByRole` | Authenticated | `ROLE_VIEW`, `ROLE_EDIT`, `ROLE_ASSIGN_PERMISSION` | `RoleDTO` (body) | Get permissions assigned to a role |

---

## 5. Permissions

**Base path:** `/api/permission`

All endpoints require SUPER_ADMIN-level permission codes.

### Endpoints

| Method | Path | Auth | Permissions | Request | Description |
|--------|------|------|-------------|---------|-------------|
| POST | `/searchPermissions` | Authenticated | `PERMISSION_LIST` | `PermissionDTO` (body) | Search permissions with pagination |
| POST | `/getPermission` | Authenticated | `PERMISSION_VIEW`, `PERMISSION_UPDATE` | `PermissionDTO` (body) | Get single permission details |
| POST | `/createPermission` | Authenticated | `PERMISSION_CREATE` | `PermissionDTO` (body) | Create a new permission |
| POST | `/updatePermission` | Authenticated | `PERMISSION_UPDATE` | `PermissionDTO` (body) | Update permission (code cannot be changed) |
| POST | `/deletePermission` | Authenticated | `PERMISSION_DELETE` | `PermissionDTO` (body) | Delete permission (only if not associated with any role) |
| POST | `/removeRoleFromPermission` | Authenticated | `PERMISSION_UPDATE` | `{ "permissionId": Long, "roleId": Long }` (body) | De-associate a role from a permission |
| POST | `/addRolesToPermission` | Authenticated | `PERMISSION_UPDATE` | `PermissionDTO` (body, uses `id` + `roleIds`) | Associate roles with a permission |

---

## 6. Checksheets

**Base path:** `/api/checksheet`

### Endpoints

| Method | Path | Auth | Permissions | Request | Description |
|--------|------|------|-------------|---------|-------------|
| POST | `/createChecksheet` | Authenticated | -- | `ChecksheetDTO` (body) | Create a new checksheet |
| DELETE | `/deleteChks/{chksId}` | Authenticated | -- | Path: `chksId` (Long) | Delete a checksheet by ID |
| POST | `/getRespectedChecksheet` | Authenticated | -- | `ChecksheetDTO` (body) | Get checksheets relevant to the current user |
| GET | `/getPublicChecksheet` | Authenticated | -- | -- | Get all public checksheets |
| POST | `/getDepartmentChecksheets` | Authenticated | -- | `DepartmentDTO` (body) | Get checksheets for a department |
| POST | `/getChecksheetDetail` | Authenticated | -- | `ChecksheetDTO` (body) | Get full checksheet details |
| POST | `/createChecksheetVersion` | Authenticated | `CHECKSHEET_MANAGEMENT_DETAIL_*`, `CHECKSHEET_MANAGEMENT_TEMPLATE_*` | `ChecksheetDTO` (body) | Create a new version of a checksheet |
| POST | `/updateChecksheetStatus` | Authenticated | -- | `ChecksheetDTO` (body) | Update checksheet status |
| GET | `/getWaitingCount` | Authenticated | -- | -- | Get count of checksheets waiting for action |
| GET | `/getLovData` | Authenticated | -- | -- | Get list-of-values data (dropdowns) |
| POST | `/downloadFile` | Authenticated | -- | `ChecksheetDTO` (body) | Download a file from S3 bucket |
| POST | `/getS3FileURL` | Authenticated | -- | `ChecksheetDTO` (body) | Get a signed URL for a single S3 file |
| POST | `/getS3FilesURL` | Authenticated | -- | `ChecksheetDTO` (body) | Get signed URLs for multiple S3 files |
| GET | `/doc/{directoryPath}/{fileName}` | Public | -- | Path: `directoryPath`, `fileName` | Serve a static document file |
| POST | `/getVersionUpdates` | Authenticated | -- | `AppVersionDTO` (body) | Check for app version updates by OS |

---

## 7. Checksheet Validation

**Base path:** `/api/checksheetValidation`

### Endpoints

| Method | Path | Auth | Permissions | Request | Description |
|--------|------|------|-------------|---------|-------------|
| POST | `/createValidation` | Authenticated | -- | `ChecksheetValidationDTO` (body) | Submit a checksheet validation decision |
| POST | `/getChecksheetValidation` | Authenticated | -- | `ChecksheetValidationDTO` (body) | Get validation status/history for a checksheet |

---

## 8. Checksheet Approval

**Base path:** `/api/checksheetApproval`

### Endpoints

| Method | Path | Auth | Permissions | Request | Description |
|--------|------|------|-------------|---------|-------------|
| POST | `/createApproval` | Authenticated | -- | `ChecksheetApprovalDTO` (body) | Submit a checksheet approval decision |
| POST | `/getChecksheetApproval` | Authenticated | -- | `ChecksheetApprovalDTO` (body) | Get approval status/history for a checksheet |

---

## 9. Checksheet General Fields

**Base path:** `/api/chksGeneralField`

General fields are custom metadata fields attached to a checksheet template.

### Endpoints

| Method | Path | Auth | Permissions | Request | Description |
|--------|------|------|-------------|---------|-------------|
| POST | `/createChksGeneralField` | Authenticated | `CHECKSHEET_MANAGEMENT_TEMPLATE_CREATE`, `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT` | `ChksGeneralFieldDTO` (body) | Create a general field on a checksheet |
| POST | `/getChksGeneralFieldData` | Authenticated | -- | `ChksGeneralFieldDTO` (body) | Get general fields for a checksheet |
| POST | `/deleteChksGeneralFieldData` | Authenticated | `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT` | `ChksGeneralFieldDTO` (body) | Delete a general field |

---

## 10. Checksheet Headers

**Base path:** `/api/chksHeader`

Headers define the column structure of a checksheet.

### Endpoints

| Method | Path | Auth | Permissions | Request | Description |
|--------|------|------|-------------|---------|-------------|
| POST | `/createChksHeader` | Authenticated | `CHECKSHEET_MANAGEMENT_TEMPLATE_CREATE`, `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT` | `ChksHeaderDTO` (body) | Create a checksheet header column |
| POST | `/getChksHeaderData` | Authenticated | -- | `ChksHeaderDTO` (body) | Get headers for a checksheet |
| POST | `/deleteChksHeaderData` | Authenticated | `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT` | `ChksHeaderDTO` (body) | Delete a checksheet header |
| POST | `/createChksHdrSummaryReportLevel` | Authenticated | -- | `ChksHdrSummaryReportLevelDTO` (body) | Set summary report levels for headers |
| POST | `/getChksHeaderSummaryReportLevel` | Authenticated | -- | `ChksHeaderDTO` (body) | Get summary report level config |

---

## 11. Checksheet Header Data

**Base path:** `/api/chksHeaderData`

Header data represents the rows/entries under each checksheet header, supporting hierarchical structures.

### Endpoints

| Method | Path | Auth | Permissions | Request | Description |
|--------|------|------|-------------|---------|-------------|
| POST | `/uploadExcel` | Authenticated | `CHECKSHEET_MANAGEMENT_TEMPLATE_CREATE`, `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT` | Multipart: `file` (Excel), `checksheetId` (Long), `isAppend` (Boolean) | Upload Excel file to populate checksheet data |
| GET | `/{checksheetId}` | Authenticated | -- | Path: `checksheetId` (Long) | Get all header data for a checksheet |
| PUT | `/updateDescription` | Authenticated | `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT` | `ChksHeaderDataDTO` (body) | Update header data description |
| PUT | `/updateName` | Authenticated | -- | `ChksHeaderDataDTO` (body) | Update header data name |
| POST | `/resetChecksheetData` | Authenticated | -- | `ChksHeaderDataDTO` (body) | Reset all header data for a checksheet |
| POST | `/uploadFiles` | Authenticated | -- | `ChksHeaderDataDTO` (multipart/form-data) | Upload files attached to header data |
| POST | `/deleteFile` | Authenticated | -- | `ChksHeaderDataFileDTO` (body) | Delete a file from header data |
| POST | `/createHeaderDataHierarchical` | Authenticated | -- | `ChksHeaderDataDTO` (body) | Create hierarchical header data entries |
| POST | `/deleteChildHeaderData` | Authenticated | -- | `ChksHeaderDataDTO` (body) | Delete child entries in hierarchical header data |

---

## 12. Checksheet Questions

**Base path:** `/api/chksQuestion`

Questions are the checkpoints/inspection items within a checksheet.

### Endpoints

| Method | Path | Auth | Permissions | Request | Description |
|--------|------|------|-------------|---------|-------------|
| PUT | `/updateDescription` | Authenticated | `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT` | `ChksQuestionDTO` (body) | Update question description |
| PUT | `/updateName` | Authenticated | -- | `ChksQuestionDTO` (body) | Update question name |
| POST | `/uploadFiles` | Authenticated | `CHECKSHEET_MANAGEMENT_TEMPLATE_CREATE`, `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT` | `ChksQuestionDTO` (multipart/form-data) | Upload reference files for a question |
| POST | `/deleteFile` | Authenticated | `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT` | `ChksQuestionFileDTO` (body) | Delete a file from a question |
| POST | `/createBulkQuestions` | Authenticated | -- | `ChksQuestionBulkDTO` (body) | Create multiple questions at once |
| POST | `/deleteQuestions` | Authenticated | -- | `ChksQuestionDTO` (body) | Delete questions by IDs |

---

## 13. Checksheet Question Results

**Base path:** `/api/chksQuestionResult`

Question results define the expected answer types (numeric, objective, matrix, etc.) for each question.

### Endpoints

| Method | Path | Auth | Permissions | Request | Description |
|--------|------|------|-------------|---------|-------------|
| POST | `/setQuestionResult` | Authenticated | -- | `ChksQuestionResultDTO` (multipart/form-data) | Set/update the result definition for a question |
| POST | `/getResultData` | Authenticated | -- | `ChksQuestionResultDTO` (body) | Get result definitions for questions |
| POST | `/cloneQuestionResult` | Authenticated | -- | `ChksQuestionResultDTO` (body) | Clone result definition from one question to another |
| POST | `/downloadQuestionResultMatrixFile` | Authenticated | -- | `ChksQuestionResultDTO` (body) | Download the matrix template file |
| POST | `/bulkSetQuestionResult` | Authenticated | -- | `BulkChksQuestionResultDTO` (multipart/form-data) | Set result definitions for multiple questions at once |

---

## 14. User Checksheets (Data Entry)

**Base path:** `/api/userChecksheet`

User checksheets represent individual checksheet submissions (filled-in instances).

### Endpoints

| Method | Path | Auth | Permissions | Request | Description |
|--------|------|------|-------------|---------|-------------|
| POST | `/getUserChecksheets` | Authenticated | -- | `ChecksheetDTO` (body, optional) | Get checksheets assigned to current user |
| GET | `/getDeclinedUserChecksheets` | Authenticated | -- | -- | Get user checksheets that were declined |
| POST | `/createOrUpdate` | Authenticated | -- | `List<UserChecksheetDTO>` (body) | Create or update user checksheet entries |
| POST | `/getUserChksDetail` | Authenticated | -- | `UserChecksheetDTO` (body) | Get detailed user checksheet with all fields |
| POST | `/createOrUpdateUserChksGnrlFieldVals` | Authenticated | -- | `List<ChksGeneralFieldValueDTO>` (body) | Save general field values for a user checksheet |
| POST | `/createOrUpdateUserChksAns` | Authenticated | -- | `List<UserChecksheetAnswerDTO>` (body) | Save answers for checksheet questions |
| POST | `/createOrUpdateUserChksMtrxAns` | Authenticated | -- | `List<UserChecksheetAnswerDTO>` (body) | Save matrix-type answers |
| POST | `/createOrUpdateUserChksTraceValues` | Authenticated | -- | `List<UserChecksheetTraceValueDTO>` (body) | Save trace values for traceable headers |
| POST | `/createOrUpdateUserChksJudgements` | Authenticated | -- | `List<UsrChecksheetAnsJudgementDTO>` (body) | Save judgement values for answers |
| POST | `/createOrUpdateUserChksJudgementFile` | Authenticated | -- | `UsrChksheetAnsJudgementFileDTO` (multipart/form-data) | Upload a judgement file attachment |
| POST | `/deleteUserChksJudgementFile` | Authenticated | -- | `UsrChksheetAnsJudgementFileDTO` (body) | Delete a judgement file attachment |
| POST | `/getRespectedUserChecksheet` | Authenticated | -- | `ChecksheetDTO` (body, optional) | Get user checksheets relevant to current user's role |
| POST | `/getUserChecksheetWithAnswers` | Authenticated | Gated by `assertUserChecksheetVisible`: `AUDIT_VIEW` OR operator OR dealer principal — see audit-report authorization model | `UserChecksheetDTO` (body) | Get a user checksheet with all answers |
| POST | `/downloadUserChecksheetWithAnswers` | Authenticated | Inherits gate via internal call to `getUserChecksheetWithAnswers` | `UserChecksheetDTO` (body) | Download user checksheet as Excel (.xlsx) |
| POST | `/downloadUserChecksheetWithAnswersPdf` | Authenticated | Inherits gate via internal call to `getUserChecksheetWithAnswers` | `UserChecksheetDTO` (body) | Download user checksheet as PDF |

---

## 15. User Checksheet Validation

**Base path:** `/api/userChecksheetValidation`

### Endpoints

| Method | Path | Auth | Permissions | Request | Description |
|--------|------|------|-------------|---------|-------------|
| POST | `/addUserChecksheetValidation` | Authenticated | -- | `UserChecksheetValidationDTO` (body) | Submit a validation decision on a user checksheet |
| POST | `/getUserChecksheetValidation` | Authenticated | -- | `UserChecksheetValidationDTO` (body) | Get validation status/history for a user checksheet |

---

## 16. User Checksheet Approval

**Base path:** `/api/userChecksheetApproval`

### Endpoints

| Method | Path | Auth | Permissions | Request | Description |
|--------|------|------|-------------|---------|-------------|
| POST | `/addUserChecksheetApproval` | Authenticated | -- | `UserChecksheetApprovalDTO` (body) | Submit an approval decision on a user checksheet |
| POST | `/getUserChecksheetApproval` | Authenticated | -- | `UserChecksheetApprovalDTO` (body) | Get approval status/history for a user checksheet |

---

## 17. Surprise Checksheets

**Base path:** `/api/surpriseChecksheet`

Ad-hoc / unplanned checksheet inspections.

### Endpoints

| Method | Path | Auth | Permissions | Request | Description |
|--------|------|------|-------------|---------|-------------|
| POST | `/createSurpriseChecksheets` | Authenticated | -- | `List<SurprizeChecksheetDTO>` (body) | Create surprise checksheet entries |
| POST | `/createSurpriseChecksheetFields` | Authenticated | -- | `SurprizeChecksheetFieldDTO` (multipart/form-data) | Create surprise checksheet fields with file uploads |

---

## 18. NPD Master

**Base path:** `/api/npd`

NPD (No Production Day) management for scheduling and tracking non-production days.

### Endpoints

| Method | Path | Auth | Permissions | Request | Description |
|--------|------|------|-------------|---------|-------------|
| POST | `/create` | Authenticated | -- | `NpdMasterDTO` (body) | Create a single NPD entry |
| POST | `/update` | Authenticated | -- | `NpdMasterDTO` (body) | Update an NPD entry |
| POST | `/delete` | Authenticated | -- | `NpdMasterDTO` (body) | Delete NPD entries |
| GET | `/get/{id}` | Authenticated | -- | Path: `id` (Long) | Get NPD entry by ID |
| POST | `/search` | Authenticated | -- | `NpdMasterDTO` (body) | Search NPD entries with pagination |
| POST | `/detailByChecksheetId` | Authenticated | -- | `NpdMasterDTO` (body) | Get NPD records for a checksheet |
| POST | `/createBulk` | Authenticated | -- | `NpdMasterDTO` (body) | Bulk create NPD entries across multiple checksheets |
| POST | `/runDaily` | Authenticated | -- | -- | Manually trigger daily NPD processing |

---

## 19. Dashboard

**Base path:** `/api/dashboard`

Analytics and reporting endpoints for checksheet data visualization.

### Endpoints

| Method | Path | Auth | Permissions | Request | Description |
|--------|------|------|-------------|---------|-------------|
| POST | `/getRespectedChecksheet` | Authenticated | -- | `DashboardDTO` (body) | Get checksheets for dashboard view |
| POST | `/getRespectedQuestions` | Authenticated | -- | `DashboardDTO` (body) | Get questions for a checksheet in dashboard context |
| POST | `/getChecksheetSummaryData` | Authenticated | -- | `DashboardDTO` (body) | Get OK/NotOK summary data for checksheets |
| POST | `/downloadChecksheetSummaryData` | Authenticated | -- | `DashboardDTO` (body) | Download summary data as Excel (.xlsx) |
| POST | `/getPlanVsActualData` | Authenticated | -- | `DashboardDTO` (body) | Get plan vs actual completion data |
| POST | `/downloadPlanVsActualData` | Authenticated | -- | `DashboardDTO` (body) | Download plan vs actual as Excel (.xlsx) |
| POST | `/getRespectedChecksheetHeaderData` | Authenticated | -- | `DashboardDTO` (body) | Get header data for dashboard drilldown |
| POST | `/getRespectedChecksheetQuestionsData` | Authenticated | -- | `DashboardDTO` (body) | Get question-level data for dashboard |
| POST | `/trendChart` | Authenticated | -- | `TrendChartDTO` (body) | Get trend chart data over time |
| POST | `/downloadTrendChartData` | Authenticated | -- | `TrendChartDTO` (body) | Download trend chart data as Excel (.xlsx) |
| POST | `/getCompletionFunnelData` | Authenticated | -- | `CompletionFunnelDTO` (body) | Get completion funnel (planned -> approved) |
| POST | `/getComplianceHeatmapData` | Authenticated | -- | `ComplianceHeatmapDTO` (body) | Get compliance heatmap by department/period |
| POST | `/getRecentSubmissions` | Authenticated | -- | `RecentSubmissionDTO` (body) | Get recent checksheet submissions |
| POST | `/getTopNonConformingQuestions` | Authenticated | -- | `NonConformingQuestionDTO` (body) | Get top non-conforming (NotOK) questions |

---

## 20. API History (Internal)

**Base path:** `/api/apiHistory`

Internal maintenance controller. No public-facing endpoints -- contains only a scheduled task.

| Trigger | Schedule | Description |
|---------|----------|-------------|
| `@Scheduled` | `0 0 4 * * *` (daily at 04:00) | Purge old API history records |

---

## Enums and Constants

All enum values are sent on the wire as their Java `name()` string (UPPER_SNAKE_CASE). The "Display" column is the canonical label the frontend renders (subject to tenant overlay).

### Answer Types (`answerType`)

| Value | Display | Description |
|-------|---------|-------------|
| `OBJECTIVE` | Objective | Numeric measurement with limits / comparisons |
| `SUBJECTIVE` | Subjective | Free-text answer |
| `SUBJECTIVE_CONDITION` | Subjective condition | Pick one option from predefined options |
| `MATRIX` | Matrix | Grid of row / column answers |
| `SELECTIVE` | Selective | Multi-select from options |
| `FILE_UPLOAD` | File upload | The uploaded file IS the answer |
| `NA` | Not Applicable | Question does not require an answer |

`isNotApplicable: true` is a flag on any answer (not a separate type) — operators set it to mark a question as N/A without picking a value.

### Objective Types (`chksQuestionResultObjectiveType`)

Used only when `answerType` is `OBJECTIVE`. Defines how the numeric answer is judged OK / NotOK.

| Value | Display | Judgement Rule |
|-------|---------|----------------|
| `RANGE` | Range | OK if `lowerLimit <= answer <= upperLimit` |
| `EQUAL_TO` | Equal to | OK if `answer == upperLimit` |
| `LESS_THAN` | Less than | OK if `answer < upperLimit` |
| `LESS_THAN_OR_EQUAL_TO` | Less than or Equal to | OK if `answer <= upperLimit` |
| `GREATER_THAN` | Greater than | OK if `answer > lowerLimit` |
| `GREATER_THAN_OR_EQUAL_TO` | Greater than or Equal to | OK if `answer >= lowerLimit` |

### Checksheet Template Status (`ChecksheetStatusType`)

| Value | Display |
|-------|---------|
| `NEW` | New |
| `CREATE_TEMPLATE` | Create template |
| `CREATE_CONTENT` | Create content |
| `SUBMITTED_FOR_VALIDATE` | Submitted for validate |
| `INVALIDATED` | Invalidated |
| `VALIDATED` | Validated |
| `APPROVED` | Approved |
| `NOT_APPROVED` | Not approved |

For the lifecycle state machine, see [`flows.md`](./flows.md).

### Inspection / User Checksheet Status (`UserChecksheetStatusType`)

Post-V1.28 the canonical enum lives on the `inspections` table. Legacy `INVALIDATED` / `NOT_APPROVED` values were folded into `IN_PROGRESS` by the V1.28 migration — they are no longer accepted on operator write paths.

| Value | Display | Acted Upon By |
|-------|---------|---------------|
| `ASSIGNED` | Assigned | Operator (start) |
| `IN_PROGRESS` | In Progress | Operator |
| `SUBMITTED` | Submitted | Data Validator |
| `VALIDATED` | Validated | Data Approver |
| `APPROVED` | Approved | — (terminal) |
| `DECLINED` | Declined | Operator (reopen → `IN_PROGRESS`) |

Operator-driven state transitions are enforced server-side — see the legal-transition table under [§14 User Checksheets](#14-user-checksheets-data-entry) (the "UserChecksheet (refactored for audit campaigns)" block earlier in this doc) and the full state machine in [`flows.md`](./flows.md).

### Template Validation Status (`ChecksheetValidationStatusType`)

| Value | Display |
|-------|---------|
| `VALIDATED` | Validated |
| `INVALIDATED` | Invalidated |

### Template Approval Status (`ChecksheetApprovalStatusType`)

| Value | Display |
|-------|---------|
| `APPROVED` | Approved |
| `NOT_APPROVED` | Not approved |

### Data Validation Status (`ChecksheetDataValidationStatusType`)

| Value | Display |
|-------|---------|
| `VALIDATED` | Validated |
| `INVALIDATED` | Invalidated |

### Data Approval Status (`ChecksheetDataApprovalStatusType`)

| Value | Display |
|-------|---------|
| `APPROVED` | Approved |
| `NOT_APPROVED` | Not approved |

### Checksheet Frequency (`ChecksheetFrequencyType`)

| Value | Display |
|-------|---------|
| `SHIFT` | Shift |
| `DAILY` | Daily |
| `WEEKLY` | Weekly |
| `MONTHLY` | Monthly |
| `UNPLANNED` | No Planning |

### Checksheet Type (`ChecksheetType`)

| Value | Display |
|-------|---------|
| `PUBLIC` | Public |
| `PRIVATE` | Private |

### System Roles

Only `SUPER_ADMIN` is hardcoded. All other roles are dynamic from the `roles` table.

| Code | Description |
|------|-------------|
| `SUPER_ADMIN` | Full system access, bypasses permission checks (frontend + backend) |
| *(dynamic)* | All other roles are created via the Roles management UI |

### Permission Codes (grouped)

The full endpoint-to-permission mapping is in the [appendix at the end of this doc](#appendix-permission-endpoint-mapping). Codes are grouped here for reference:

**Department:** `DEPARTMENT_CREATE`, `DEPARTMENT_LIST`, `DEPARTMENT_DOWNLOAD`, `SUBDEPARTMENT_CREATE`, `SUBDEPARTMENT_LIST`, `SUBDEPARTMENT_DOWNLOAD`

**User:** `USER_LIST`, `USER_CREATE`, `USER_EDIT`, `USER_DELETE`, `USER_DOWNLOAD`

**Role management (SUPER_ADMIN):** `ROLE_LIST`, `ROLE_VIEW`, `ROLE_CREATE`, `ROLE_UPDATE`, `ROLE_DELETE`, `ROLE_EDIT`, `ROLE_ASSIGN_PERMISSION`

**Permission management (SUPER_ADMIN):** `PERMISSION_LIST`, `PERMISSION_VIEW`, `PERMISSION_CREATE`, `PERMISSION_UPDATE`, `PERMISSION_DELETE`

**Checksheet template management:**
`CHECKSHEET_PREPARE`, `CHECKSHEET_VALIDATE`, `CHECKSHEET_APPROVE`,
`CHECKSHEET_MANAGEMENT_LIST`, `CHECKSHEET_MANAGEMENT_DETAIL_VIEW`, `CHECKSHEET_MANAGEMENT_DETAIL_CREATE`, `CHECKSHEET_MANAGEMENT_DETAIL_EDIT`,
`CHECKSHEET_MANAGEMENT_TEMPLATE_CREATE`, `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT`, `CHECKSHEET_MANAGEMENT_TEMPLATE_VIEW`,
`CHECKSHEET_MANAGEMENT_CONTENT_VIEW`,
`CHECKSHEET_MANAGEMENT_VALIDATOR_COMMENT_CREATE`, `CHECKSHEET_MANAGEMENT_VALIDATOR_COMMENT_EDIT`, `CHECKSHEET_MANAGEMENT_VALIDATOR_COMMENT_VIEW`,
`CHECKSHEET_MANAGEMENT_APPROVER_COMMENT_CREATE`, `CHECKSHEET_MANAGEMENT_APPROVER_COMMENT_EDIT`, `CHECKSHEET_MANAGEMENT_APPROVER_COMMENT_VIEW`

**Checksheet fill (operator):** `CHECKSHEET_FILL_LISTING`, `CHECKSHEET_FILL_CHECKSHEET_DETAIL`, `CHECKSHEET_FILL_ANSWER`, `CHECKSHEET_LISTING`

**Data validation / approval:**
`CHECKSHEET_DATA_VALIDATE`, `CHECKSHEET_DATA_APPROVE`,
`CHECKSHEET_DATA_VALIDATOR_COMMENT_CREATE`, `CHECKSHEET_DATA_VALIDATOR_COMMENT_EDIT`, `CHECKSHEET_DATA_VALIDATOR_COMMENT_VIEW`,
`CHECKSHEET_DATA_APPROVER_COMMENT_CREATE`, `CHECKSHEET_DATA_APPROVER_COMMENT_EDIT`, `CHECKSHEET_DATA_APPROVER_COMMENT_VIEW`

**Audit / BI (V1.24+):** `AUDIT_VIEW`

**Intervention (V1.27+):** `INTERVENTION_MANAGE`, `INTERVENTION_ASSIGNMENT_VIEW`, `INTERVENTION_ASSIGNMENT_ACKNOWLEDGE`, `INTERVENTION_ASSIGNMENT_MANAGE`

**NPD:** `NPD_CREATE`

---

## Mobile API flow (audit lifecycle)

End-to-end contract for performing one audit, post the V1.24 Audit refactor and the V1.28 inspection collapse. The mobile app (`auditpro-mobile-app`) is the primary client; the web frontend is read-only on inspections.

### Conceptual model

```
Checksheet (template)         ─── reused across many audit campaigns
   ↑
   │ checksheet_id
   │
Audit (campaign)              ─── e.g. "FY26 H1 Kia Showroom Audit"
   ↑                              one campaign, many inspections
   │ audit_id
   │
Inspection (kind='AUDIT')     ─── one (audit, location) row, post-V1.28
   ↑                              carries operator_user_id + lifecycle status
   │ id  ≡  auditAssignmentId
   │      (legacy DTO name preserved on the wire)
   │
user_checksheet_answers       ─── filled-in answers + photos
```

Key shift: **the mobile app sends a single `auditAssignmentId`** (= the inspection id) to identify what's being audited. The server resolves audit, location, operator, and checksheet from that one id. There is no need to transmit `auditId`, `auditeeLocationId`, or `checksheetId` on the write path.

The name `AuditAssignment` survived V1.28 at the DTO layer because an assignment is fulfilled equally well by a human visit, a CCTV feed, voice interaction, or an automated road-to-sale check — the substrate may change, but the "unit of work for an auditor" concept survives. For V1.27+ re-inspection waves, the same endpoint takes `interventionAssignmentId` instead (an `Inspection` of `kind='INTERVENTION'`).

### 1. Login

```
POST /api/user/login
{ "username": "...", "password": "...", "deviceType": "APP" }
→ { data: { accessToken, refreshToken, permissions, allRoles, ... } }
```

Send `Authorization: Bearer <accessToken>` on every subsequent request. Mobile uses `deviceType: "APP"` so it gets its own refresh-token row independent of the web session (see V1.29).

### 2. List the operator's pending assignments

```
GET /api/audit/myAssignments
→ Assignment[] for the logged-in operator
```

Each row: `assignmentId`, `auditId`, `auditName`, `checksheetId`, `checksheetName`, `locationLabel`, `userChecksheetId` (nullable), `status`, `answeredQuestions`, `totalQuestions`. Ordered IN_PROGRESS → SCHEDULED → others.

The list includes both audit-kind and intervention-kind inspections in one combined view (V1.27+).

### 3. Start (or resume) an audit

```
POST /api/userChecksheet/createOrUpdate
[
  {
    "auditAssignmentId": 42,
    "status": "IN_PROGRESS",
    "shift": "First",
    "startedAt": "2026-05-07 10:00:00.000",
    "submissionVersion": 0,
    "frequencyOfFreqOfChkCnt": 1
  }
]
```

Server resolves audit + location + checksheet from `auditAssignmentId`. Returns the saved `UserChecksheetDTO` with `id` plus derived `auditId`, `auditeeLocationId`, `checksheetId`, `auditName`, `checksheetName` for display.

Constraints enforced:
- The audit's checksheet template must be `APPROVED`.
- Only one `IN_PROGRESS` inspection per assignment at a time.
- `frequencyOfFreqOfChkCnt` must not exceed the template's max attempts.
- `status` transition must be legal — see the legal-transition table under [§14 User Checksheets](#14-user-checksheets-data-entry).

### 4. Answer questions

Six answer types (per `ChksQuestionResultType`). "Not applicable" is a flag (`isNotApplicable: true`) on any answer, not a type — see [Enums and Constants → Answer Types](#answer-types-answertype).

| Type | What the operator does | Key field |
|---|---|---|
| `OBJECTIVE` | Numeric value (with optional unit + bounds) | `answer: "0"` |
| `SUBJECTIVE` | Free-text response | `answer: "..."` |
| `SUBJECTIVE_CONDITION` | Picks one option (OK / Not OK) from predefined options | `chksQuestionRsltOptionId: <id>` |
| `SELECTIVE` | Multi-select from options | `chksQuestionRsltOptionId` (one row per selection) |
| `MATRIX` | Grid of values (separate endpoint) | `createOrUpdateUserChksMtrxAns` |
| `FILE_UPLOAD` | The uploaded file IS the answer (no text / option) | upload via step 5 below |

```
POST /api/userChecksheet/createOrUpdateUserChksAns
[
  {
    "userChecksheetId": <uc_id>,
    "chksQuestionResultId": <qr_id>,
    "judgement": 1,           // 1=OK, 2=NOT_OK
    "isNotApplicable": false,
    "answeredAt": "2026-05-07 10:35:00.000",
    "chksQuestionRsltOptionId": <option_id>   // for SUBJECTIVE_CONDITION / SELECTIVE
    // OR "answer": "value"                    // for OBJECTIVE / SUBJECTIVE
  }
]
→ { status: true, data: [{ id: <answer_id>, ... }] }
```

The response carries the saved `id` for each answer row — needed for the photo-attach step.

### 5. Attach photo evidence (any answer type)

Per V1.24: photo evidence is allowed on **any** answer type, not just `FILE_UPLOAD`. The original FILE_UPLOAD-only restriction conflated "the file IS the answer" with "supporting evidence on top of an answer" — the latter is real and now supported.

```
POST /api/userChecksheet/createUserChksAnsFile   (multipart)
  userChecksheetAnswerId=<answer_id>
  file=<binary>
→ { status: true }
```

Permission required: `CHECKSHEET_FILL_ANSWER`. No restriction on the underlying answer type. V1.27 added 413 / 415 validation — see the file-upload validation table in [§14 User Checksheets](#14-user-checksheets-data-entry).

For Kia: optionally call the AI assessment endpoint right after upload to get an LLM verdict on the photo:

```
POST /api/ai/assess   (multipart)
  userChecksheetId=<uc_id>
  chksQuestionResultId=<qr_id>
  photo=<binary>
→ { status: true, data: { suggestedJudgement, explanation, confidence, ... } }
```

The result lands in `ai_assessments`; the BI dashboard surfaces it as the AI agreement / disagreement story.

### 6. Submit when done

```
POST /api/userChecksheet/createOrUpdate
[
  {
    "id": <uc_id>,
    "status": "SUBMITTED",
    "submittedAt": "2026-05-07 12:30:00.000"
  }
]
```

Only the fields that change need to be sent. `startedAt`, `auditAssignmentId`, `shift`, etc. are preserved from the existing row — the V1.24 service refactor stopped overwriting them with nulls.

### 7. Validation (different user — DATA_VALIDATOR)

The validator is determined by the checksheet's `data_validator_user_ids` array. Login as that user, then:

```
POST /api/userChecksheetValidation/addUserChecksheetValidation
{ "userChecksheetId": <uc_id>, "status": "VALIDATED", "remarks": "..." }
```

`status` enum is `ChecksheetDataValidationStatusType`: `VALIDATED` or `INVALIDATED`.

### 8. Approval (different user — DATA_APPROVER)

```
POST /api/userChecksheetApproval/addUserChecksheetApproval
{ "userChecksheetId": <uc_id>, "status": "APPROVED", "remarks": "..." }
```

`status` enum is `ChecksheetDataApprovalStatusType`: `APPROVED` or `NOT_APPROVED`.

### Re-inspection waves (V1.27+)

A re-inspection wave is just another `Inspection` row — same table, same answers, same approval flow as the original audit — except `kind='INTERVENTION'` and `intervention_id` is set instead of `audit_id`. Mobile passes `interventionAssignmentId` (= the intervention-kind inspection id) to the same `createOrUpdate` endpoint. `createOrUpdateUserChksAns` and `createUserChksAnsFile` are unchanged — they take a `userChecksheetId`, not the parent.

Why no new mobile pages: pre-V1.27 had a separate `/re-audit` flow with its own service + pages. V1.27 unified them onto the existing UC pipeline so auditors see one combined list, one submission state machine, one photo upload path, one AI-assessment path, and the `audit_signal` CTE rolls up audit + intervention UCs identically for BI scoring.

### Status state machine (mobile-visible)

```
ASSIGNED ──start──▶ IN_PROGRESS ──submit──▶ SUBMITTED ──validate──▶ VALIDATED ──approve──▶ APPROVED
                        ▲                       │                       │                      │
                        │                       │                       │                      └─ (terminal)
                        │                       │                       └─ INVALIDATED / NOT_APPROVED
                        │                       │                          (V1.28: folded back into IN_PROGRESS)
                        └─ DECLINED ←───────────┴────────────────────────┘  reopen → IN_PROGRESS
```

Legal operator-driven transitions are enforced server-side. See the transition table in [§14 User Checksheets](#14-user-checksheets-data-entry) for what the mobile path is allowed to send. The full multi-actor state machine lives in [`flows.md`](./flows.md).

### What changed from the pre-V1.24 contract (mobile-app rewrite checklist)

| Endpoint | Before | After |
|---|---|---|
| `createOrUpdate` (create) | sent `checksheetId` | send `auditAssignmentId` (or `interventionAssignmentId` for re-inspection waves) |
| `createOrUpdate` (update) | had to re-send `startedAt` or it got nulled | only send fields that change |
| `createUserChksAnsFile` | only worked for FILE_UPLOAD-typed answers | works on any answer type |
| `addAuditLocations` (admin) | endpoint name | renamed to `addAuditAssignments`, body uses `assignments` not `locations` |

### Worked example end-to-end

See `tenants/kia/bin/seed-kia-demo.py` for a full Python implementation of this flow against a live backend — it creates assignments, fills them as 30 demo operators, attaches photos, calls Gemini for AI verdicts, and walks each through SUBMIT → VALIDATE → APPROVE.

---

## DTO Schemas

### UserDTO

Used for authentication, user CRUD, and role-based user queries.

```
{
  id: Long
  username: String
  password: String
  firstName: String
  lastName: String
  status: String
  email: String
  mobile: String
  deviceType: String
  failLoginCount: Integer
  resendOtpTime: Date
  lockTime: Date
  createdAt: Date
  updatedAt: Date
  accessToken: String              // Returned on login/refresh
  refreshToken: String             // Returned on login/refresh
  allRoles: List<String>           // Role codes
  roleNames: Map<String, String>   // role_code -> role_name
  menus: List<String>              // Accessible menu items
  permissions: List<String>        // Permission codes
  roleCode: String                 // Filter by role
  roleId: Long
  roleIds: List<Long>
  departmentId: Long
  departmentIds: List<Long>
  sectionIds: List<Long>
  permissionCodes: List<String>
  page: Integer                    // Pagination
  size: Integer                    // Pagination
  search: String                   // Search filter
  confirmPassword: String          // For password update
}
```

### ChecksheetDTO

Core checksheet definition with all assignment and configuration fields.

```
{
  id: Long
  ids: List<Long>
  name: String
  modelNo: String
  description: String
  versionRemark: String
  status: String                         // e.g., DRAFT, ACTIVE, EXPIRED
  serialNumber: String
  uid: String
  revision: Long
  implementationDate: Date               // Format: dd/MM/yyyy
  expiryDate: Date                       // Format: yyyy-MM-dd
  frequencyOfCheck: ChecksheetFrequencyType  // DAILY, WEEKLY, MONTHLY, etc.
  frequencyOfFreqOfChk: Short
  departmentId: Long
  departmentIds: List<Long>
  checksheetType: ChecksheetType
  assetCode: String
  isFileUpload: Boolean

  // User assignments
  preparerUserId: Long
  validatorUserIds: List<Long>
  approverUserIds: List<Long>
  dataValidatorUserIds: List<Long>
  dataApproverUserIds: List<Long>
  operatorUserIds: List<Long>
  alertToUserIds: List<Long>
  escalateToUserIds: List<Long>
  escalationGuidelinesDays: Long

  // Pagination
  currentPage: Long
  perPageRecord: Long
  search: String

  // Date range filters
  startDate: Date                        // Format: yyyy-MM-dd
  endDate: Date                          // Format: yyyy-MM-dd

  // Nested structures (in responses)
  chksHeaders: List<ChksHeaderDTO>
  chksHeaderData: List<ChksHeaderDataDTO>
  chksGeneralFields: List<ChksGeneralFieldDTO>
  userChecksheets: List<UserChecksheetDTO>

  // S3 file access
  path: String
  paths: List<String>
}
```

### DepartmentDTO

Department and section management with user assignment.

```
{
  id: Long
  name: String
  departmentId: Long
  sectiontId: Long
  userId: Long
  username: String
  firstName: String
  lastName: String
  email: String
  mobile: String
  roleId: Long
  roleName: String
  roleCode: String
  isSubDepartment: Boolean
  isEditable: Boolean
  isDeletable: Boolean
  departmentName: String
  sectionName: String
  search: String
  currentPage: Long                 // Pagination
  perPageRecord: Long               // Pagination
  userRoleDepartmentId: Long
  departmentIds: List<Long>
  roleIds: List<Long>
  checksheetIds: List<Long>
  checksheets: List<String>
  roles: List<RoleDTO>
  departments: List<DepartmentDTO>
  sections: List<DepartmentDTO>
  createdAt: Date
  updatedAt: Date
}
```

### MasterDepartmentDTO

```
{
  id: Long
  name: String
  createdAt: Date
  updatedAt: Date
  createdById: Long
  createdByName: String
  currentPage: Integer              // Pagination
  perPageRecord: Integer            // Pagination
  search: String
  isEditable: Boolean
  isDeletable: Boolean
  sectionCount: Integer
  sections: List<MasterDepartmentSectionDTO>
}
```

**MasterDepartmentSectionDTO** (inner):

```
{
  id: Long
  name: String
  createdAt: Date
  updatedAt: Date
  isEditable: Boolean
  isDeletable: Boolean
}
```

### RoleDTO

```
{
  id: Long
  roleCode: String
  name: String
  parentRoleId: Long
  permissionIds: List<Long>         // For create/update
  permissions: List<PermissionDTO>  // In responses
  isEditable: Boolean
  isDeletable: Boolean
  currentPage: Integer              // Pagination
  perPageRecord: Integer            // Pagination
  search: String
  createdAt: Date
  updatedAt: Date
}
```

### PermissionDTO

```
{
  id: Long
  permissionCode: String
  name: String
  description: String
  createdAt: Date
  updatedAt: Date
  currentPage: Integer              // Pagination
  perPageRecord: Integer            // Pagination
  search: String
  isEditable: Boolean
  isDeletable: Boolean
  roleCount: Integer                // Number of roles using this
  roles: List<RoleDTO>              // Roles using this permission
  roleIds: List<Long>              // For create/update association
}
```

### DashboardDTO

```
{
  id: Long
  name: String
  checksheetIds: List<Long>
  checksheetId: Long
  checksheetHeaderId: Long
  checksheetQuestionId: Long
  checksheetQuestionResultId: Long
  questionIds: List<Long>
  parameters: List<ParamValDTO>
  startDate: Date                   // Format: yyyy-MM-dd
  endDate: Date                     // Format: yyyy-MM-dd
  frequencyOfCheck: String
  chksHeaderDataId: Long
  chksHeaderId: Long
  level: Long
  version: Long
  isDataValidator: Boolean
  loginUserId: Long
  okCount: Long                     // In responses
  notOkCount: Long                  // In responses
}
```

### TrendChartDTO

```
{
  id: Long
  userChecksheetAnswerId: Long
  chksQuestionId: Long
  judgement: Short
  status: String
  answer: String
  firstName: String
  lastName: String
  username: String
  unit: String
  upperLimit: Double
  lowerLimit: Double
  answerType: String
  objectiveType: String
  checkDate: Date                   // Format: yyyy-MM-dd
  answerDate: Date                  // Format: yyyy-MM-dd
  submittedAt: Date                 // Format: yyyy-MM-dd
  startDate: Date                   // Filter: yyyy-MM-dd
  endDate: Date                     // Filter: yyyy-MM-dd
  checksheetIds: List<Long>
  chksQuestionIds: List<Long>
  chksQuestionResultIds: List<Long>
  parameters: List<ParamValDTO>
}
```

### CompletionFunnelDTO

```
{
  // Request
  startDate: Date                   // Format: yyyy-MM-dd
  endDate: Date                     // Format: yyyy-MM-dd
  departmentIds: List<Long>
  frequencyOfCheck: String

  // Response
  planned: Long
  inProgress: Long
  submitted: Long
  validated: Long
  approved: Long
  rejected: Long
  breakdown: List<StatusBreakdown>
}

StatusBreakdown: { status: String, count: Long, percentage: Double }
```

### ComplianceHeatmapDTO

```
{
  // Request
  startDate: Date                   // Format: yyyy-MM-dd
  endDate: Date                     // Format: yyyy-MM-dd
  departmentLevel: Integer
  groupBy: String                   // WEEK or MONTH

  // Response
  departmentId: Long
  departmentName: String
  periods: List<PeriodData>
}

PeriodData: { period: String, complianceScore: Double, totalChecksheets: Long, okCount: Long, notOkCount: Long }
```

### RecentSubmissionDTO

```
{
  // Request
  startDate: Date                   // Format: yyyy-MM-dd
  endDate: Date                     // Format: yyyy-MM-dd
  departmentIds: List<Long>
  statusFilter: List<String>
  limit: Integer
  offset: Integer

  // Response
  userChecksheetId: Long
  checksheetId: Long
  checksheetName: String
  version: Long
  departmentId: Long
  departmentName: String
  operatorName: String
  operatorUsername: String
  status: String
  submittedAt: Date                 // Format: yyyy-MM-dd HH:mm:ss
  okCount: Long
  notOkCount: Long
  shift: String
  frequencyOfCheck: String
}
```

### NonConformingQuestionDTO

```
{
  // Request
  startDate: Date                   // Format: yyyy-MM-dd
  endDate: Date                     // Format: yyyy-MM-dd
  checksheetIds: List<Long>
  limit: Integer

  // Response
  questionId: Long
  checksheetId: Long
  checksheetName: String
  questionName: String
  questionDescription: String
  totalCount: Long
  notOkCount: Long
  notOkPercentage: Double
  trend: String                     // UP, DOWN, STABLE
  previousPeriodPercentage: Double
}
```

### ChecksheetValidationDTO

```
{
  id: Long
  checksheetId: Long
  status: ChecksheetValidationStatusType  // PENDING, VALIDATED, DECLINED
  remarks: String
  validatorUserId: Long
  validatedAt: Date
  firstName: String
  lastName: String
  checksheetValidatorHistory: List<ChecksheetValidationHistoryDTO>
  createdAt: Date
  updatedAt: Date
}
```

### ChecksheetApprovalDTO

```
{
  id: Long
  checksheetId: Long
  status: ChecksheetApprovalStatusType    // PENDING, APPROVED, DECLINED
  remarks: String
  approverUserId: Long
  approvedAt: Date
  implementationDate: Date
  createdAt: Date
  updatedAt: Date
}
```

### UserChecksheetDTO

Post-V1.28 this DTO is the wire shape for `inspections` rows (both `kind=AUDIT`
and `kind=INTERVENTION`). The legacy class name is preserved at the
service/DTO layer; the renamed entity is `Inspection`.

```
{
  id: Long                          // inspection id
  userChecksheetId: Long
  checksheetId: Long
  auditAssignmentId: Long           // present on kind=AUDIT (post-V1.28: same as id)
  interventionAssignmentId: Long    // present on kind=INTERVENTION re-inspection waves
  status: String                    // V1.28 enum: ASSIGNED | IN_PROGRESS | SUBMITTED | VALIDATED | APPROVED | DECLINED
                                    //   Legacy INVALIDATED / NOT_APPROVED were folded into IN_PROGRESS by V1.28.
                                    //   Operator-driven writes only accept the legal transitions listed under
                                    //   "UserChecksheet (refactored for audit campaigns)".
  shift: String
  startedAt: Date                   // Format: yyyy-MM-dd HH:mm:ss.SSS
  submittedAt: Date                 // Format: yyyy-MM-dd HH:mm:ss.SSS
  submissionVersion: Byte
  frequencyOfFreqOfChkCnt: Short
  localId: Long
  checksheet: ChecksheetDTO         // Nested in responses
  userDTO: UserDTO                  // Nested in responses
  createdAt: Date
  updatedAt: Date
}
```

### UserChecksheetValidationDTO

```
{
  id: Long
  userChecksheetId: Long
  checksheetId: Long
  status: ChecksheetDataValidationStatusType  // PENDING, VALIDATED, DECLINED
  remarks: String
  dataValidatorUserId: Long
  validatedAt: Date
  firstName: String
  lastName: String
  userChecksheetValidatorHistory: List<UserChecksheetValidationHistoryDTO>
  createdAt: Date
  updatedAt: Date
}
```

### UserChecksheetApprovalDTO

```
{
  id: Long
  userChecksheetId: Long
  checksheetId: Long
  status: ChecksheetDataApprovalStatusType    // PENDING, APPROVED, DECLINED
  remarks: String
  dataApproverUserId: Long
  approvedAt: Date
  deletedAt: Date
  createdBy: Long
  updatedBy: Long
  deletedBy: Long
  createdAt: Date
  updatedAt: Date
}
```

### ChksHeaderDTO

```
{
  id: Long
  checksheetId: Long
  chksHeaderId: Long                // Parent header ID (for nesting)
  name: String
  isResultColumn: Boolean
  isTraceable: Boolean
  summaryReportLevel: Byte
  createdAt: Date
  updatedAt: Date
}
```

### ChksHeaderDataDTO

```
{
  id: Long
  checksheetId: Long
  chksHeaderId: Long
  chksHeaderDataId: Long            // Parent header data ID (for hierarchy)
  name: String
  description: String
  orderNo: Integer
  level: Long
  chksHeaderDataImages: List<MultipartFile>  // For file uploads
  questions: List<ChksQuestionDTO>           // Nested questions
  children: List<ChksHeaderDataDTO>          // Child entries
  chksHeaderDataFiles: List<ChksHeaderDataFileDTO>
  chksChildHeaderDataIds: List<Long>
  createdAt: Date
  updatedAt: Date
}
```

### ChksQuestionDTO

```
{
  id: Long
  checksheetId: Long
  chksHeaderId: Long
  chksHeaderDataId: Long
  questionIds: List<Long>                    // For bulk operations
  name: String
  description: String
  orderNo: Integer
  chksQuestionImages: List<MultipartFile>     // For file uploads
  chksQuestionResults: List<ChksQuestionResultDTO>
  chksQuestionDataFiles: List<ChksQuestionFileDTO>
  judgement: String
  remarks: String
  judgementFiles: List<ChksHeaderDataFileDTO>
  createdAt: Date
  updatedAt: Date
}
```

### ChksQuestionBulkDTO (Request)

```
{
  chksHeaderDataId: Long
  checksheetId: Long
  orderNo: Integer
  questions: List<ChksQuestionDTO>
}
```

### ChksQuestionResultDTO

```
{
  id: Long
  checksheetId: Long
  chksHeaderId: Long
  chksQuestionId: Long
  cloneChksQuestionResultId: Long
  selectedChksQuestionResultId: Long
  answerType: ChksQuestionResultType           // NUMERIC, OBJECTIVE, SUBJECTIVE, MATRIX, etc.
  chksQuestionResultObjectiveType: ChksQuestionResultObjectiveType
  upperLimit: Double
  lowerLimit: Double
  unit: String
  noOfResults: Long
  isOptional: Boolean
  isNotApplicable: Boolean

  // Matrix fields
  matrixName: String
  chksMatrixRowNm: List<String>
  chksMatrixColNm: List<String>
  noOfRows: Long
  noOfColumns: Long
  chksMatrixColName: String
  chksMatrixRowName: String
  matrixFile: MultipartFile
  matrixFileLocation: String
  matrixFileUrl: String

  chksQuestionResultOptions: List<ChksQuestionResultOptionDTO>
  chksQuestionResultMatrices: List<ChksQuestionResultMatrixDTO>

  // In user checksheet context
  userAnswer: String
  matrixAnswers: List<UserChecksheetMatrixAnswerDTO>

  createdAt: Date
  updatedAt: Date
}
```

### BulkChksQuestionResultDTO

```
{
  checksheetId: Long
  chksHeaderIds: List<Long>
  chksQuestionIds: List<Long>
  answerType: ChksQuestionResultType
  chksQuestionResultObjectiveType: ChksQuestionResultObjectiveType
  upperLimit: Double
  lowerLimit: Double
  unit: String
  noOfResults: Long
  isOptional: Boolean
  matrixFile: MultipartFile
  matrixName: String
  chksMatrixRowNm: List<String>
  chksMatrixColNm: List<String>
  noOfRows: Long
  noOfColumns: Long
  chksMatrixColName: String
  chksMatrixRowName: String
  chksQuestionResultMatrices: List<ChksQuestionResultMatrixDTO>
  chksQuestionResultOptions: List<ChksQuestionResultOptionDTO>
}
```

### NpdMasterDTO

```
{
  id: Long
  checksheetId: Long
  checksheetName: String
  createdBy: Long
  npdDate: Date                     // Format: yyyy-MM-dd
  shift: String
  isException: Boolean
  remarks: String
  isDeletable: Boolean

  // Bulk creation
  checksheetIds: List<Long>
  shifts: List<String>
  npdDays: List<NpdDayDTO>
  ids: List<Long>

  // Search filters
  checksheetNameLike: String
  frequencyOfCheck: String
  startDate: Date                   // Format: yyyy-MM-dd
  endDate: Date                     // Format: yyyy-MM-dd

  // Pagination
  page: Integer                     // 0-based
  size: Integer
  npdCount: Long                    // In aggregated responses

  createdAt: Date
}

NpdDayDTO: { npdDate: Date, shifts: List<String> }
```

### ChksGeneralFieldDTO

```
{
  id: Long
  checksheetId: Long
  name: String
  answer: String
  createdAt: Date
  updatedAt: Date
}
```

### SurprizeChecksheetDTO

```
{
  id: Long
  name: String
  localId: Long
}
```

### SurprizeChecksheetFieldDTO

```
{
  id: Long
  surpriseChecksheetId: Long
  responsibleUserId: Long
  responsibleUserUsername: String
  departmentId: Long
  concern: String
  remarks: String
  files: List<MultipartFile>
  creationDate: Date                // Format: yyyy-MM-dd
  localId: Long
}
```

### AppVersionDTO

```
{
  id: Long
  version: Long
  url: String
  os: String
  versionName: String
  isForcefullyUpdate: Boolean
}
```

### ChksHdrSummaryReportLevelDTO

```
{
  checksheetId: Long
  levelOneHeaderIds: List<Long>
  levelTwoHeaderIds: List<Long>
}
```

### ChksHeaderDataFileDTO

```
{
  id: Long
  chksHeaderId: Long
  chksHeaderDataId: Long
  checksheetId: Long
  name: String
  path: String
  url: String
  description: String
  createdAt: Date
  updatedAt: Date
}
```

### ChksQuestionFileDTO

```
{
  id: Long
  checksheetId: Long
  chksHeaderId: Long
  chksQuestionId: Long
  chksHeaderDataId: Long
  path: String
  url: String
  description: String
  createdAt: Date
  updatedAt: Date
}
```


---

# Interventions (V1.27+)

## Naming

PRD-internal vocabulary maps to code:

| PRD label              | Code entity                                       |
|------------------------|---------------------------------------------------|
| Improvement Campaign   | `Intervention`                                    |
| Improvement Plan       | `Inspection` with `kind='INTERVENTION'` (V1.28+)  |

UI labels keep the PRD names. Pre-V1.28, the plan was a separate `InterventionAssignment` entity; V1.28 collapsed it into the unified `Inspection` table. Legacy Java class names like `InterventionAssignmentServiceImpl` survive at the service layer for compatibility — they now operate on `Inspection` rows of kind=INTERVENTION.

## Management endpoints (`/api/intervention`)

| Method | Path                                       | Body                                                                                                                  | Permission                |
|--------|--------------------------------------------|-----------------------------------------------------------------------------------------------------------------------|---------------------------|
| POST   | `/createDraft`                             | `InterventionCreateDTO` (name, theme, priority, auditId, targetDate, questionIds, targetingMode + targetRegionId/targetAuditAssignmentIds)         | `INTERVENTION_MANAGE`     |
| PUT    | `/{id}`                                    | `InterventionUpdateDTO`                                                                                               | `INTERVENTION_MANAGE`     |
| POST   | `/{id}/setQuestions`                       | `[chksQuestionId]`                                                                                                    | `INTERVENTION_MANAGE`     |
| POST   | `/{id}/addQuestions`                       | `[chksQuestionId]`                                                                                                    | `INTERVENTION_MANAGE`     |
| POST   | `/{id}/activate`                           | optional `InterventionCreateDTO` (targeting override)                                                                 | `INTERVENTION_MANAGE`     |
| GET    | `/{id}/activationConflicts`                | —                                                                                                                     | `INTERVENTION_MANAGE`     |
| POST   | `/{id}/close`                              | —                                                                                                                     | `INTERVENTION_MANAGE`     |
| DELETE | `/{id}`                                    | (Drafts only)                                                                                                         | `INTERVENTION_MANAGE`     |
| GET    | `/list`                                    | —                                                                                                                     | `INTERVENTION_MANAGE`     |
| GET    | `/{id}`                                    | —                                                                                                                     | `INTERVENTION_MANAGE`     |
| GET    | `/audit/{auditId}/claimedQuestions`        | —                                                                                                                     | `INTERVENTION_MANAGE`     |

Activate returns **HTTP 409** with a `QuestionConflictDTO[]` body if any of
the intervention's questions are already claimed by another ACTIVE
intervention on the same audit cycle (PRD §3.1.5).

`claimedQuestions` (V1.28+) returns the set of `chksQuestionId`s already
covered by any ACTIVE or DRAFT intervention on this audit so the
"New Intervention" form can hide them from the picker and render a
"claimed by ..." banner. One row per `(chksQuestionId × interventionId)`:

```
[{ chksQuestionId, interventionId, interventionName, priority, status }]
```

## Plan endpoints (`/api/intervention-assignment`)

| Method | Path                                                | Permission                                |
|--------|-----------------------------------------------------|-------------------------------------------|
| GET    | `/myPlans`                                          | `INTERVENTION_ASSIGNMENT_VIEW` (scoped to caller's dealer-principal link) |
| GET    | `/byIntervention/{interventionId}`                  | `INTERVENTION_ASSIGNMENT_VIEW`            |
| GET    | `/byDealer/{auditeeId}`                             | `INTERVENTION_ASSIGNMENT_VIEW`            |
| GET    | `/byLocation/{auditeeLocationId}`                   | `INTERVENTION_ASSIGNMENT_VIEW`            |
| GET    | `/{id}`                                             | `INTERVENTION_ASSIGNMENT_VIEW`            |
| POST   | `/{id}/acknowledge`                                 | `INTERVENTION_ASSIGNMENT_ACKNOWLEDGE` — **also enforces a row-level check**: caller must be the user stamped on `Inspection.dealer_principal_user`. Returns **403 `Only the dealer principal of this dealership can acknowledge this plan`** if `dealer_principal_user` is null on the plan or doesn't match the caller. Idempotent (re-ack just refreshes `acknowledged_at`). |
| POST   | `/{id}/closeNonCompliant` (body: `{reason}`)        | `INTERVENTION_ASSIGNMENT_MANAGE` — **501 in V1.28**: NON_COMPLIANT is a derived state, tracked at GitLab issue `M-COMP-001`. |

## BI summary (`/api/audit/{auditId}/intervention-summary/{level}`)

Levels: `national`, `region/{regionId}`, `dealer/{auditeeId}`,
`location/{locationId}`. Returns:

```
{
  activeCampaigns,
  totalAssignments,
  p1CompletionRate,
  dealersWithCompletedP1, dealersNonCompliantP1,
  networkScoreDelta, networkScoreDeltaDealerCount,
  topCampaigns: [{id, name, priority, planCount, planCompletedCount}],
  redDealersWithPlan, redDealersWithoutPlan
}
```

Reads the same `audit_signal` CTE every other BI panel uses, so the rollup
matches.

## Audit-report overlay (`/api/audit/userChecksheet/{ucId}/improvement-overlay`)

For an audit UC, returns the per-question temporal chain across every
intervention re-inspection wave at this UC's location, plus header
score deltas. Gated by `assertUserChecksheetVisible` (AUDIT_VIEW perm
OR operator OR dealer principal). Shape:

```
{
  userChecksheetId,
  originalScore,    // pct_ok of the original audit UC alone
  currentScore,     // rolled-up post-intervention pct_ok
  scoreDelta,       // currentScore - originalScore

  questionFlags: {
    "<chksQuestionId>": [{ interventionId, campaignName, priority, interventionStatus }]
  },
  reAuditAnswers: {
    "<chksQuestionId>": [{
      reAuditAnswerId, reAuditId, interventionId, interventionName,
      auditorName, judgement, comment, answeredAt, photoUrls
    }]
  },
  headerSnapshots: [
    { interventionId, interventionName, completedAt, wavesCount }
  ]
}
```

**C2/C3 fix (V1.28+).** The `questionFlags[*]` entry keys used to be
serialized as `planId` / `planStatus` (the underlying plan-inspection's
id/status). They are now `interventionId` / `interventionStatus` to
match the campaign-level identifiers everywhere else in the overlay
contract. Frontend click-throughs that fed `planId` into intervention
detail navigation need to read `interventionId` instead.

## Scoring principle

Audit BI reflects the **current effective state** post-intervention, not
the original audit's frozen state. Implemented as one change in
`audit_signal`'s `pct_ok` LATERAL: per `(audit_assignment, chks_question_result_id)`,
the latest APPROVED answer wins across the original UC + every
intervention re-inspection UC on the assignment. Three interventions
flipping the same question OK→NotOK→OK chain still works — the audit
report shows the full timeline; BI shows the latest.

---

## Appendix: Date Format Reference

| Context | Format | Example |
|---------|--------|---------|
| `implementationDate` in checksheet creation | `dd/MM/yyyy` | `23/04/2026` |
| Date filters (`startDate`, `endDate`) | `yyyy-MM-dd` | `2026-04-23` |
| `expiryDate` | `yyyy-MM-dd` | `2026-12-31` |
| Timestamps (`startedAt`, `submittedAt`, `answeredAt`) | `yyyy-MM-dd HH:mm:ss.SSS` | `2026-04-23 10:30:00.000` |
| Response envelope `lastUpdatedTime` | ISO 8601 | `2026-04-23T10:00:00.000+00:00` |

---

## Appendix: File Upload Endpoints

These endpoints use `multipart/form-data` instead of JSON:

| Endpoint | Multipart Fields |
|----------|------------------|
| `POST /api/chksHeaderData/uploadExcel` | `file` (Excel), `checksheetId` (Long), `isAppend` (Boolean) |
| `POST /api/chksHeaderData/uploadFiles` | File fields + header data IDs |
| `POST /api/chksQuestion/uploadFiles` | File fields + question IDs |
| `POST /api/chksQuestionResult/setQuestionResult` | JSON fields + optional `matrixFile` |
| `POST /api/chksQuestionResult/bulkSetQuestionResult` | JSON fields + optional `matrixFile` |
| `POST /api/userChecksheet/createOrUpdateUserChksJudgementFile` | `file` + judgement IDs |
| `POST /api/userChecksheet/createUserChksAnsFile` | `file` + `userChecksheetAnswerId` |
| `POST /api/surpriseChecksheet/createSurpriseChecksheetFields` | `files` + field data |

For 413 / 415 validation on the answer-file and judgement-file uploads (V1.27+), see [§14 User Checksheets](#14-user-checksheets-data-entry).

---

## Appendix: Permission Endpoint Mapping

Complete mapping of endpoints to their required permissions. A user needs **at least ONE** of the listed permissions to access the endpoint. Endpoints not listed here require only authentication (a valid JWT). `SUPER_ADMIN` bypasses all checks.

| Endpoint | Required Permissions (any one) |
|----------|-------------------------------|
| `/api/department/createDepartment` | `DEPARTMENT_CREATE`, `SUBDEPARTMENT_CREATE` |
| `/api/department/searchDepartments` | `DEPARTMENT_LIST`, `SUBDEPARTMENT_LIST` |
| `/api/department/downloadDepartments` | `DEPARTMENT_DOWNLOAD` |
| `/api/department/getDepartments` | `DEPARTMENT_LIST`, `SUBDEPARTMENT_LIST`, `USER_LIST`, `CHECKSHEET_FILL_LISTING` |
| `/api/department/createSections` | `SUBDEPARTMENT_CREATE` |
| `/api/department/searchSections` | `SUBDEPARTMENT_LIST` |
| `/api/department/downloadSections` | `SUBDEPARTMENT_DOWNLOAD` |
| `/api/department/searchUsers` | `USER_LIST` |
| `/api/department/downloadUsers` | `USER_DOWNLOAD` |
| `/api/user/createOrEditOperator` | `USER_CREATE`, `USER_EDIT` |
| `/api/user/deleteUser` | `USER_DELETE` |
| `/api/role/getRoles` | `USER_LIST`, `USER_CREATE`, `USER_EDIT` |
| `/api/role/searchRoles` | `ROLE_LIST` |
| `/api/role/getRole` | `ROLE_VIEW`, `ROLE_EDIT` |
| `/api/role/createRole` | `ROLE_CREATE` |
| `/api/role/updateRole` | `ROLE_UPDATE` |
| `/api/role/deleteRole` | `ROLE_DELETE` |
| `/api/role/getAllPermissions` | `ROLE_CREATE`, `ROLE_UPDATE`, `ROLE_ASSIGN_PERMISSION` |
| `/api/role/getPermissionsByRole` | `ROLE_VIEW`, `ROLE_EDIT`, `ROLE_ASSIGN_PERMISSION` |
| `/api/permission/searchPermissions` | `PERMISSION_LIST` |
| `/api/permission/getPermission` | `PERMISSION_VIEW`, `PERMISSION_UPDATE` |
| `/api/permission/createPermission` | `PERMISSION_CREATE` |
| `/api/permission/updatePermission` | `PERMISSION_UPDATE` |
| `/api/permission/deletePermission` | `PERMISSION_DELETE` |
| `/api/permission/removeRoleFromPermission` | `PERMISSION_UPDATE` |
| `/api/permission/addRolesToPermission` | `PERMISSION_UPDATE` |
| `/api/checksheet/createChecksheetVersion` | `CHECKSHEET_MANAGEMENT_DETAIL_CREATE`, `CHECKSHEET_MANAGEMENT_DETAIL_EDIT`, `CHECKSHEET_MANAGEMENT_TEMPLATE_CREATE`, `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT` |
| `/api/chksGeneralField/createChksGeneralField` | `CHECKSHEET_MANAGEMENT_TEMPLATE_CREATE`, `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT` |
| `/api/chksGeneralField/deleteChksGeneralFieldData` | `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT` |
| `/api/chksHeader/createChksHeader` | `CHECKSHEET_MANAGEMENT_TEMPLATE_CREATE`, `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT` |
| `/api/chksHeader/deleteChksHeaderData` | `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT` |
| `/api/chksHeaderData/updateDescription` | `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT` |
| `/api/chksHeaderData/uploadExcel` | `CHECKSHEET_MANAGEMENT_TEMPLATE_CREATE`, `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT` |
| `/api/chksQuestion/updateDescription` | `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT` |
| `/api/chksQuestion/resetChecksheetData` | `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT` |
| `/api/chksQuestion/uploadFiles` | `CHECKSHEET_MANAGEMENT_TEMPLATE_CREATE`, `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT` |
| `/api/chksQuestion/deleteFile` | `CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT` |
| `/api/userChecksheet/createUserChksAnsFile` | `CHECKSHEET_FILL_ANSWER` (+ inspection ownership / editability guard — see §14) |
| `/api/audit/userChecksheetMeta` | gated by `assertUserChecksheetVisible` (`AUDIT_VIEW` OR operator-of-UC OR dealer-principal-of-UC) |
| `/api/audit/userChecksheet/{id}/improvement-overlay` | gated by `assertUserChecksheetVisible` (same rule) |
| `/api/userChecksheet/getUserChecksheetWithAnswers` | gated by `assertUserChecksheetVisible` (same rule) |
| `/api/userChecksheet/downloadUserChecksheetWithAnswers` (xlsx) | inherits gate via internal call to `getUserChecksheetWithAnswers` |
| `/api/userChecksheet/downloadUserChecksheetWithAnswersPdf` | inherits gate via internal call to `getUserChecksheetWithAnswers` |
| `/api/intervention/*` (create / update / activate / close / list / get / claimedQuestions) | `INTERVENTION_MANAGE` |
| `/api/intervention-assignment/myPlans` | `INTERVENTION_ASSIGNMENT_VIEW` (scoped to caller's dealer-principal link) |
| `/api/intervention-assignment/byIntervention/{id}` | `INTERVENTION_ASSIGNMENT_VIEW` |
| `/api/intervention-assignment/byDealer/{id}` | `INTERVENTION_ASSIGNMENT_VIEW` |
| `/api/intervention-assignment/byLocation/{id}` | `INTERVENTION_ASSIGNMENT_VIEW` |
| `/api/intervention-assignment/{id}` | `INTERVENTION_ASSIGNMENT_VIEW` |
| `/api/intervention-assignment/{id}/acknowledge` | `INTERVENTION_ASSIGNMENT_ACKNOWLEDGE` + row-level: caller must be `Inspection.dealer_principal_user` |
| `/api/intervention-assignment/{id}/closeNonCompliant` | `INTERVENTION_ASSIGNMENT_MANAGE` (501 in V1.28 — tracked under `M-COMP-001`) |

For UI permission-gating rules (which buttons show for which permission) and per-role workflow responsibilities, see [`flows.md`](./flows.md).
