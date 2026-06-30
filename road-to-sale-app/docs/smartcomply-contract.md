# SmartComply API Contract — Road to Sale

This document is the canonical reference for how the Road to Sale mobile app
communicates with the SmartComply backend. Every HTTP call made by
`SmartComplyClient` is documented here.

---

## 1. Overview

Road to Sale acts as a **SmartComply tenant**. It runs inside a standing Honda
audit campaign (id=1) backed by the NADA Road to the Sale checksheet
(id=2001, code=`RTS_HONDA_V1`).

The mapping between Road to Sale concepts and SmartComply entities is:

| Road to Sale concept | SmartComply entity |
|---|---|
| NADA template | `checksheets` row (id=2001, code=`RTS_HONDA_V1`) |
| NADA process step (1–10) | `chks_headers` row (orderNo 1–10) |
| Cue group / audit question | `chks_questions` row |
| Customer walk-in session | `inspections` row (kind=`AUDIT`) |
| Cue detection event | `user_checksheet_answers` row |
| Walk-in campaign | `audits` row (id=1, "Road to Sale – Walk-In") |
| Bootstrap assignment | `inspections` pre-created row (auditId=1, locationId=1) |

Every customer walk-in creates one `inspection` (via
`POST /api/audit/addAuditAssignments`) and one `user_checksheet` (via
`POST /api/userChecksheet/createOrUpdate`). As the voice engine detects cues, each
one fires `POST /api/userChecksheet/createOrUpdateUserChksAns` in real time. The
rep reviews and submits at the end of the visit.

---

## 2. Base URL

| Environment | Value |
|---|---|
| Local dev | `http://localhost:8089/api` |
| Env var | `SMARTCOMPLY_API_URL` (set in `.env`, no trailing slash) |

All paths below are relative to the base URL's `/api` prefix. The client
prepends `/api` automatically; config supplies only the origin
(`http://host:port`).

---

## 3. Authentication

### Mechanism

JWT Bearer tokens. Every authenticated request carries:

```
Authorization: Bearer <accessToken>
```

Tokens are stored in `expo-secure-store`, which maps to the iOS Keychain
(Secure Enclave-backed) and Android Keystore on each platform.

| Key | SecureStore key | Description |
|---|---|---|
| Access token | `rts_access_token` | Short-lived JWT sent on every request |
| Refresh token | `rts_refresh_token` | Long-lived token used to obtain a new access token |
| User ID | `rts_user_id` | Stored integer; used to scope local SQLite queries |

### Login

`POST /api/user/login` (unauthenticated).

On success the client stores both tokens and the user id via SecureStore.

### Refresh

`POST /api/user/refreshToken` (unauthenticated).

Triggered automatically when any authenticated request returns `401`. The
client implements **silent refresh with in-flight deduplication**: if multiple
requests fail with 401 concurrently, only one refresh call is issued; all
pending requests are queued and replayed with the new token once the refresh
completes.

If the refresh itself fails (e.g. refresh token expired), both tokens are
deleted from SecureStore and an `AuthError` is thrown — the app navigates to
the login screen.

### Logout

Logout is client-side only: `SmartComplyClient.logout()` deletes all three
SecureStore keys. No server-side token revocation call is made.

---

## 4. Endpoints

### Response envelope

Every API response (except multipart upload responses) is wrapped in:

```ts
interface ApiResponse<T> {
  status: number;      // HTTP status mirror
  message: string;     // Human-readable status message
  data: T;             // The actual payload
}
```

The client unwraps `data` before returning to callers — all return types
below describe the unwrapped value.

---

### 4.1 Auth

#### `POST /api/user/login`

Creates a session.

Request body:
```ts
{
  username: string;
  password: string;
  deviceType: 'APP';   // always APP for mobile
}
```

Response (`data`):
```ts
{
  accessToken: string;
  refreshToken: string;
  id: number;          // userId
  username: string;
  name: string;
  permissions: string[];
  allRoles: string[];
}
```

Errors: `401` → `AuthError('Invalid username or password.')`.

---

#### `POST /api/user/refreshToken`

Obtains new tokens using a valid refresh token.

Request body:
```ts
{ refreshToken: string }
```

Response (`data`):
```ts
{ accessToken: string; refreshToken: string }
```

Errors: non-2xx → clears stored tokens, returns `null` internally (caller
receives `AuthError`).

---

### 4.2 Assignments

#### `GET /api/audit/myAssignments`

Returns all open assignments for the authenticated operator.

Response (`data`): `AssignmentDTO[]`

```ts
interface AssignmentDTO {
  assignmentId: number;
  auditId: number;
  auditName: string;
  checksheetId: number;
  checksheetName: string;
  locationLabel: string;
  userChecksheetId: number | null;  // null if not yet started
  status: InspectionStatus;
  answeredQuestions: number;
  totalQuestions: number;
}

type InspectionStatus =
  | 'ASSIGNED' | 'IN_PROGRESS' | 'SUBMITTED'
  | 'VALIDATED' | 'APPROVED' | 'DECLINED';
```

---

#### `POST /api/audit/addAuditAssignments`

Creates one or more walk-in assignments under the standing Honda campaign.
Called once per customer walk-in to provision the inspection row before the
session starts.

Request body (`AddAssignmentRequest`):
```ts
{
  auditId: number;           // 1 (walk-in campaign)
  assignments: Array<{
    auditeeLocationId: number;   // Honda showroom location id
    operatorUserId: number;      // logged-in rep's userId
    dealerPrincipalUserId?: number;
  }>;
}
```

Response (`data`): `{ count: number }` — number of assignments created.

---

### 4.3 Checksheet (NADA template)

#### `POST /api/checksheet/getChecksheetDetail`

Fetches the NADA checksheet template. Called once at app boot and cached
locally.

Request body: `{ id: number }` (id=2001 for Honda RTS)

Response (`data`):
```ts
interface ChecksheetDTO {
  id: number;
  name: string;                    // "Road to the Sale – Honda US"
  code: string;                    // "RTS_HONDA_V1"
  status: 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'ARCHIVED';
  headers: ChecksheetHeaderDTO[];
}

interface ChecksheetHeaderDTO {
  id: number;
  name: string;         // NADA step name, e.g. "Greet / Hospitality"
  orderNo: number;      // 1–10
  questions: ChecksheetQuestionDTO[];
}

interface ChecksheetQuestionDTO {
  id: number;
  question: string;
  orderNo: number;
  isMandatory: boolean;
  questionResultType: QuestionResultType;
  resultOptions: QuestionResultOptionDTO[];
}

type QuestionResultType =
  | 'SUBJECTIVE_CONDITION'   // OK / Not OK  ← used for cue detection
  | 'SUBJECTIVE'             // Free text
  | 'OBJECTIVE'              // Numeric
  | 'FILE_UPLOAD';

interface QuestionResultOptionDTO {
  id: number;
  option: string;       // "OK" or "Not OK"
  orderNo: number;
}
```

---

### 4.4 Session lifecycle

#### `POST /api/userChecksheet/createOrUpdate`

Creates or resumes an inspection (UserChecksheet). Returns the first element
of the array the server echoes back.

Request body: `UserChecksheetCreateDTO[]` (single-element array):
```ts
interface UserChecksheetCreateDTO {
  auditAssignmentId: number;   // inspections.id from addAuditAssignments
  status: InspectionStatus;    // 'IN_PROGRESS' when starting
  shift: string;               // e.g. "MORNING"
  startedAt: string;           // "YYYY-MM-DD HH:mm:ss.SSS"
  submissionVersion: number;   // 1 for first submit
  frequencyOfFreqOfChkCnt: number;  // 1 for walk-in
}
```

Response (`data`): `UserChecksheetDTO[]` (client returns index 0):
```ts
interface UserChecksheetDTO {
  id: number;                  // userChecksheetId — used for all subsequent calls
  auditId: number;
  auditeeLocationId: number;
  checksheetId: number;
  auditName: string;
  checksheetName: string;
  status: InspectionStatus;
  startedAt: string;
}
```

**Submit** reuses the same endpoint with `{ id: userChecksheetId, status: 'SUBMITTED' }`.

---

### 4.5 Cue detection answers

#### `POST /api/userChecksheet/createOrUpdateUserChksAns`

Records one cue detection event as a checksheet answer. Used for both
single-answer and batch writes.

Request body: `UserChecksheetAnswerDTO[]`

```ts
interface UserChecksheetAnswerDTO {
  userChecksheetId: number;
  chksQuestionId: number;
  chksQuestionRsltOptionId?: number;  // option id for "OK" (SUBJECTIVE_CONDITION)
  answer?: string;                    // free text (SUBJECTIVE/OBJECTIVE)
  isNotApplicable?: boolean;

  // Road to Sale extension fields (V1.34 migration — nullable on server)
  rtsCueId?: string;                  // e.g. "workflow.greeting_30s"
  rtsCueSource?: 'feature' | 'workflow';
  rtsTranscriptSnippet?: string;      // verbatim excerpt (≤200 chars)
  rtsCueConfidence?: number;          // 0.0–1.0
  rtsVoiceAutoCompleted?: boolean;    // true = engine-fired; false = manual tap
}
```

Response: `void` (HTTP 200, empty body or wrapped empty `data`).

Both `submitAnswer(single)` and `submitAnswers(batch)` call this endpoint;
the only difference is array length. Empty arrays short-circuit before the
network call.

---

### 4.6 Trade-in photos

#### `POST /api/rts/tradePhoto/upload`

Uploads one trade-in photo for a given slot. Uses `multipart/form-data`.
Does **not** go through the standard JSON `request()` helper — the client
constructs the FormData manually and attaches the Authorization header.

Form fields:
- `file` — the photo blob/uri
- `userChecksheetId` — string-encoded integer
- `slot` — one of the 7 TradePhotoSlot values

Response (`data`): `TradePhotoDTO`

```ts
type TradePhotoSlot =
  | 'front_left' | 'front_right'
  | 'rear_left'  | 'rear_right'
  | 'interior'   | 'odometer' | 'vin';

interface TradePhotoDTO {
  id: number;
  inspectionId: number;
  slot: TradePhotoSlot;
  fileUrl: string;
  uploadedAt: string;
}
```

---

#### `GET /api/rts/tradePhoto?userChecksheetId={id}`

Fetches all uploaded trade photos for a session.

Response (`data`): `TradePhotoDTO[]`

---

## 5. Error handling

| HTTP status | Behaviour |
|---|---|
| `401` (first occurrence) | Silent refresh → retry once with new token |
| `401` (after refresh) | Throw `AuthError`; app navigates to login |
| `403` | Throw `SmartComplyApiError(403, message)` — operator lacks permission for this route |
| `4xx` (other) | Throw `SmartComplyApiError(status, responseText)` |
| `5xx` | Throw `SmartComplyApiError(status, responseText)` |

```ts
export class AuthError extends Error { name = 'AuthError'; }

export class SmartComplyApiError extends Error {
  name = 'SmartComplyApiError';
  constructor(public readonly statusCode: number, message: string) { ... }
}
```

**Circuit breaker:** after 5 consecutive failures on the offline write queue
(see §6), the queue stops retrying and marks the write as failed
(`markWriteFailed()`). The error is surfaced to the rep via a banner; the
session data remains in SQLite and can be manually retried after connectivity
is restored.

---

## 6. Offline write queue and retry policy

Cue detection events and session lifecycle calls are written to SQLite before
being forwarded to SmartComply. If the network is unavailable, the write is
queued and drained when connectivity returns.

Drain trigger: `AppState 'active'` listener (app foregrounded or network
restored).

Retry schedule (exponential backoff, capped):

| Attempt | Delay before retry |
|---|---|
| 1 | 1 s |
| 2 | 2 s |
| 3 | 4 s |
| 4 | 8 s |
| 5 | 16 s (cap) |

After 5 retries the write is marked failed (`markWriteFailed()`) and the
circuit breaker stops retrying that write. The session is still complete in
SQLite; the data is not lost.

---

## 7. SmartComply domain mapping

Full table mapping Road to Sale runtime concepts to SmartComply DB entities:

| Road to Sale | SmartComply DB | Seed value |
|---|---|---|
| NADA template | `checksheets` | id=2001, code=`RTS_HONDA_V1`, status=`APPROVED` |
| NADA step (1–10) | `chks_headers` | orderNo 1–10 under checksheetId=2001 |
| Cue group / question | `chks_questions` | 16 questions, all `SUBJECTIVE_CONDITION` |
| Result option "OK" | `chks_question_result_options` | 32 options (OK / Not OK pairs) |
| Customer session | `inspections` (kind=`AUDIT`) | Created per walk-in |
| Cue detection event | `user_checksheet_answers` | One row per detected cue |
| Walk-in campaign | `audits` | id=1, "Road to Sale – Walk-In" |
| Bootstrap assignment | `inspections` pre-seeded row | auditId=1, campaignId=1 |

The `inspections.id` returned by `addAuditAssignments` is passed as
`auditAssignmentId` to `createOrUpdate`. The `userChecksheet.id` returned by
`createOrUpdate` is the `userChecksheetId` used on every subsequent answer and
photo upload call.
