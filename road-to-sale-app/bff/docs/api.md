# Road to Sale BFF — App-facing API

The BFF is the only thing the mobile app talks to. It validates input, checks
Authorization presence, and relays to the Java Core API at `CORE_BASE_URL`,
passing Core's `ApiResponse` envelope straight through.

Authoritative contract: [`../../contract/openapi.yaml`](../../contract/openapi.yaml).
Interactive docs are served at **`/docs`** when the BFF is running.

## Envelope

All JSON responses (except — on success — none here; even photo upload returns
the envelope from Core) use:

```ts
ApiResponse<T> = { status: number; message: string; data: T | null }
```

`data` is `null` on errors.

## Endpoints

| # | Method & path | Auth | Request | Validated by |
|---|---|---|---|---|
| 1 | `POST /auth/login` | public | `{username, password, deviceType}` | `loginBody` JSON Schema |
| 2 | `POST /auth/refresh` | public | `{refreshToken}` | `refreshBody` JSON Schema |
| 3 | `GET /checksheet/:code` | Bearer | path `code` | `checksheetCodeParams` |
| 4 | `GET /sessions` | Bearer | — | — |
| 5 | `POST /sessions` | Bearer | `{type, checksheetCode, context?}` | `createSessionBody` |
| 6 | `GET /sessions/:id` | Bearer | path `id` (uuid) | `sessionIdParams` |
| 7 | `POST /sessions/:id/events` | Bearer | `{events: SessionEventDTO[]}` | `postEventsBody` |
| 8 | `POST /sessions/:id/submit` | Bearer | `{transcript?}` | `submitSessionBody` |
| 9 | `POST /sessions/:id/photos` | Bearer | multipart `{slot, file}` | params + in-handler slot check |
| 10 | `GET /sessions/:id/photos` | Bearer | path `id` (uuid) | `sessionIdParams` |
| — | `GET /health` | public | — | reports `{ status, coreReachable }` |

## Status codes

- `400` — request failed JSON Schema validation (or, for photos, bad/missing
  `slot`/`file`). The BFF returns this **without** calling Core.
- `401` — protected route called without an `Authorization: Bearer <token>`
  header. Returned **without** calling Core. (Signature verification is Core's
  job; the BFF only checks presence.)
- `404`, `409`, etc. — relayed verbatim from Core (e.g. cross-tenant access →
  404, submitting a completed session → 409).
- `502` — Core API is unreachable.

## Request bodies & params

See [`../src/schemas.ts`](../src/schemas.ts) for the exact JSON Schemas. Bodies
use `additionalProperties: false`, so unknown fields are rejected with 400.
`SessionEventDTO.confidence` must be within `[0, 1]`; the events batch must be
non-empty.

## Multipart photo relay

`POST /sessions/:id/photos` accepts `multipart/form-data` with a `slot` text
field and a `file` binary part. The BFF reads the parts via `@fastify/multipart`,
validates the slot against the allowed enum, rebuilds the multipart body as an
undici `FormData` (preserving field names `slot` and `file`, plus filename and
MIME type), and POSTs it to Core's multipart endpoint. Core's `PhotoDTO`
envelope is relayed back unchanged.
