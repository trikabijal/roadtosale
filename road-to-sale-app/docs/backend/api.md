# API Reference

The app-facing API — the 10 endpoints the BFF exposes and the mobile app calls.
This is the facade that replaces the old SmartComply client.

- **Authoritative contract:** `road-to-sale-app/contract/openapi.yaml`. The DTOs
  below mirror it.
- **Base URL (local dev):** `http://localhost:8089` (the BFF).
- **Swagger UI:** the BFF serves it at `/docs`.

For how requests travel through the stack, see [flows.md](./flows.md); for the
isolation rule, see [architecture.md](./architecture.md).

## The ApiResponse envelope

Every JSON response — success or error — uses one envelope:

```ts
ApiResponse<T> = {
  status: number;   // HTTP status code, e.g. 200
  message: string;  // e.g. "OK"
  data: T | null;   // payload; null on error
}
```

The one exception is the multipart photo **upload request** body
(`multipart/form-data`); its response is still an `ApiResponse`.

## Authentication

Protected endpoints require an `Authorization: Bearer <accessToken>` header. The
BFF checks the header is present and shaped like a Bearer token; the Core API
verifies the signature and reads the `userId` and `dealershipId` claims. A
missing or malformed header returns `401`.

The two `/auth/*` endpoints and `/health` are public (no token).

## Error codes

| Status | Meaning | When |
|---|---|---|
| `400` | Bad request | Body/params fail validation (e.g. missing field, bad enum, confidence out of 0..1, empty event batch, invalid photo slot, or a photo upload that is not a JPEG/PNG/WebP image — the file's bytes are sniffed, not its declared type). |
| `401` | Unauthorized | Missing/invalid/expired token, or wrong username/password on login. |
| `404` | Not found | Unknown checksheet code, unknown session id or photo id, **or a session/photo that belongs to another dealership** (cross-tenant access is reported as not found). |
| `409` | Conflict | Submitting an already-completed session, or posting events to a completed session. |
| `413` | Payload too large | A photo upload exceeds the 10 MB file-size cap. |
| `502` | Bad gateway | The BFF could not reach the Core API. |

## Endpoints

All paths are on the BFF. "Auth" = requires a Bearer token.

### 1. `POST /auth/login` — public

Log in with username and password.

- **Body:** `LoginRequest` `{ username, password, deviceType }`
- **Response data:** `LoginResponse` `{ accessToken, refreshToken, user }`
- **Errors:** `400` (missing field), `401` (bad credentials)

### 2. `POST /auth/refresh` — public

Exchange a refresh token for a new access + refresh pair.

- **Body:** `RefreshRequest` `{ refreshToken }`
- **Response data:** `RefreshResponse` `{ accessToken, refreshToken }`
- **Errors:** `400` (missing field), `401` (invalid/expired refresh token)

### 3. `GET /checksheet/{code}` — auth

Get a NADA checksheet by code (static reference data). Example code:
`RTS_HONDA_V1`.

- **Response data:** `ChecksheetDTO`
- **Errors:** `401`, `404` (unknown code)

### 4. `GET /sessions` — auth

List the caller's sessions, most recent first. **Paginated.**

- **Query (optional):** either `page` + `size`, or `limit` + `offset`. `size`
  (or `limit`) defaults to **50** and is hard-capped at **200**. If both pairs
  are given, `limit`/`offset` wins.
- **Response data:** `SessionSummaryDTO[]` (one page)
- **Errors:** `401`

### 5. `POST /sessions` — auth

Create a session. It starts with status `ACTIVE`.

- **Body:** `CreateSessionRequest` `{ type, checksheetCode, context? }`
- **Response data:** `SessionDTO`
- **Errors:** `400`, `401`. (The Core returns `404` if the `checksheetCode` is
  unknown.)

### 6. `GET /sessions/{id}` — auth

Get one session, including derived per-question `outcomes`.

- **Response data:** `SessionDTO`
- **Errors:** `401`, `404` (unknown id or another dealership's session)

### 7. `POST /sessions/{id}/events` — auth

Append a batch of detected cue events. **Idempotent by `cueId`:** an event whose
`cueId` was already recorded for the session is ignored, not errored.

- **Body:** `PostEventsRequest` `{ events: SessionEventDTO[] }` (at least 1 event)
- **Response data:** `PostEventsResponse` `{ accepted }` — count of new
  (non-duplicate) events appended
- **Errors:** `400`, `401`, `404`, `409` (session is `COMPLETED`)

### 8. `POST /sessions/{id}/submit` — auth

Submit (complete) a session: set status `COMPLETED`, set `endedAt`, and store the
optional final `transcript`.

- **Body (optional):** `SubmitSessionRequest` `{ transcript? }`
- **Response data:** `SessionDTO` (status `COMPLETED`)
- **Errors:** `401`, `404`, `409` (already completed)

### 9. `POST /sessions/{id}/photos` — auth

Upload one trade-in photo for a slot. Request is `multipart/form-data` with a
`slot` text field and a `file` binary part.

- The file must be a **JPEG, PNG, or WebP** image. The Core checks the file's
  actual leading bytes (magic-byte sniffing), so renaming a non-image or faking
  the `Content-Type` does not get past it.
- Maximum file size is **10 MB**.
- **Response data:** `PhotoDTO`
- **Errors:** `400` (missing/invalid slot or file, or not a supported image
  type), `401`, `404`, `413` (file over 10 MB)

### 10. `GET /sessions/{id}/photos` — auth

List all photos for a session.

- **Response data:** `PhotoDTO[]`
- **Errors:** `401`, `404`

### 11. `GET /sessions/{id}/photos/{photoId}/content` — auth

Download the bytes of one photo. This is the URL that `PhotoDTO.fileUrl` points
at. The response is the raw image (with the photo's `Content-Type`), **not** the
`ApiResponse` envelope.

- **Response:** the image bytes
- **Errors:** `401` (no token), `404` (unknown photo, or a photo that belongs
  to another dealership)

There is no longer a public `/files/**` URL. Photo bytes are served only through
this authenticated, tenant-scoped endpoint.

## Data models (DTOs)

Field types match `contract/openapi.yaml`. `?` marks an optional/nullable field.

### Enums

| Enum | Values |
|---|---|
| `SessionType` | `LIVE`, `MOCK` |
| `SessionStatus` | `ACTIVE`, `COMPLETED` |
| `EventSource` | `feature`, `workflow` (lowercase) |
| `PhotoSlot` | `front_left`, `front_right`, `rear_left`, `rear_right`, `interior`, `odometer`, `vin` (lowercase) |
| `ResultType` | `CUE`, `FREE_TEXT`, `NUMERIC`, `PHOTO` |

### Auth

```ts
user = {
  id: string;            // uuid
  username: string;
  name: string;
  dealershipId: string;  // uuid
  roles: string[];
}

LoginRequest    = { username: string; password: string; deviceType: string }
LoginResponse   = { accessToken: string; refreshToken: string; user: user }
RefreshRequest  = { refreshToken: string }
RefreshResponse = { accessToken: string; refreshToken: string }
```

### Sessions

```ts
SessionContext = {
  customerName?: string;
  vehicleOfInterest?: string;
}

CreateSessionRequest = {
  type: SessionType;
  checksheetCode: string;
  context?: SessionContext;
}

SubmitSessionRequest = {
  transcript?: string;
}

SessionProgress = {
  answered: number;   // count of satisfied questions
  total: number;      // total questions in the checksheet
}

SessionSummaryDTO = {
  id: string;             // uuid
  type: SessionType;
  status: SessionStatus;
  checksheetCode: string;
  startedAt: string;      // date-time
  endedAt?: string | null;
  context?: SessionContext;
  progress: SessionProgress;
}

SessionOutcome = {
  questionId: string;
  stepNo: number;
  satisfied: boolean;
  confidence?: number | null;
  transcriptSpan?: string | null;
}

SessionDTO = SessionSummaryDTO & {
  transcript?: string | null;
  outcomes: SessionOutcome[];
}
```

### Events

```ts
SessionEventDTO = {
  cueId: string;          // client-generated, unique per session for idempotency
  questionId: string;
  stepNo: number;
  detectedAt: string;     // date-time
  confidence: number;     // 0..1
  transcriptSpan: string;
  source: EventSource;
}

PostEventsRequest  = { events: SessionEventDTO[] }
PostEventsResponse = { accepted: number }   // new (non-duplicate) events appended
```

### Photos

```ts
PhotoDTO = {
  id: string;          // uuid
  sessionId: string;   // uuid
  slot: PhotoSlot;
  fileUrl: string;     // BFF-relative path to endpoint #11:
                       // /sessions/<sessionId>/photos/<photoId>/content
  uploadedAt: string;  // date-time
}
```

### Checksheet

```ts
ChecksheetQuestion = {
  id: string;            // question_id referenced by session_events
  text: string;
  orderNo: number;
  isMandatory: boolean;
  resultType: ResultType;
}

ChecksheetStep = {
  id: string;
  name: string;
  orderNo: number;
  questions: ChecksheetQuestion[];
}

ChecksheetDTO = {
  code: string;          // e.g. RTS_HONDA_V1
  name: string;
  version: string;
  steps: ChecksheetStep[];
}
```

## The internal Core API

The BFF is a validating relay (see [architecture.md](./architecture.md)). The
internal Core API serves the **same resource shapes**, but under a different
prefix than the app-facing paths above:

- Core resource endpoints live under **`/api/v1`** — for example
  `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`,
  `GET /api/v1/sessions`, `POST /api/v1/sessions`,
  `GET /api/v1/sessions/{id}`, `POST /api/v1/sessions/{id}/events`,
  `POST /api/v1/sessions/{id}/submit`, `POST /api/v1/sessions/{id}/photos`,
  `GET /api/v1/sessions/{id}/photos`, and
  `GET /api/v1/sessions/{id}/photos/{photoId}/content`.
- The checksheet path on the Core is **plural**:
  `GET /api/v1/checksheets/{code}`. (App-facing it stays singular,
  `GET /checksheet/{code}`.)
- `GET /health` is the one exception: it stays at the **root** on the Core
  (no `/api/v1`).

The BFF maps each app-facing path to the matching Core `/api/v1` path in
`bff/src/coreClient.ts`. The two prefixes (root + singular `/checksheet` for the
app; `/api/v1` + plural `/checksheets` for the Core) are deliberate: the
internal contract is versioned and uses REST-plural collections, while the
app-facing contract keeps the shape the front end already expects.

Photo bytes are **not** served at a public `/files/**` URL anymore. They are
served only through the authenticated, tenant-scoped
`GET /api/v1/sessions/{id}/photos/{photoId}/content` endpoint on the Core, which
the BFF exposes to the app as `GET /sessions/{id}/photos/{photoId}/content`
(endpoint #11 above).
