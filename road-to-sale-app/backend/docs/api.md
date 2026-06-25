# Core API — Internal API Reference

The Core API is the Java / Spring Boot source of truth. It is **internal**: only
the BFF calls it. The app-facing contract lives on the BFF.

> This is a short, Core-specific reference. The full app-facing contract, every
> DTO, and the error-code table are in the canonical system docs:
> [../../docs/backend/api.md](../../docs/backend/api.md).

## Base + auth

- **Base URL (local dev):** `http://localhost:8090`.
- **Auth:** every endpoint except `GET /health` and `POST /api/v1/auth/**`
  requires a `Authorization: Bearer <accessToken>` header. `JwtAuthFilter`
  verifies the signature and reads the `userId` + `dealershipId` claims.
- **Swagger UI:** `/swagger-ui.html`; OpenAPI JSON at `/v3/api-docs`.
- **Envelope:** every JSON response (success and error) is
  `ApiResponse { status, message, data }`.

## Endpoints (all under `/api/v1`, except `/health`)

| Method & path | Purpose |
|---|---|
| `GET /health` | Liveness + DB reachability (public; **root**, not `/api/v1`) |
| `POST /api/v1/auth/login` | Login → access + refresh tokens + user |
| `POST /api/v1/auth/refresh` | New token pair from a valid refresh token |
| `GET /api/v1/checksheets/{code}` | Static NADA checksheet (note: **plural** `checksheets`) |
| `GET /api/v1/sessions` | List the caller's sessions (paginated; `page`+`size` or `limit`+`offset`; default size 50, cap 200) |
| `POST /api/v1/sessions` | Create a session (`ACTIVE`) |
| `GET /api/v1/sessions/{id}` | One session + derived outcomes |
| `POST /api/v1/sessions/{id}/submit` | Complete a session |
| `POST /api/v1/sessions/{id}/events` | Append a batch of cue events (idempotent by `cueId`) |
| `POST /api/v1/sessions/{id}/photos` | Upload one photo (multipart; JPEG/PNG/WebP, ≤ 10 MB) |
| `GET /api/v1/sessions/{id}/photos` | List a session's photos |
| `GET /api/v1/sessions/{id}/photos/{photoId}/content` | Serve photo bytes (authed, tenant-scoped) |

### Path differences vs the app-facing BFF

The internal Core paths differ from the app-facing ones on purpose:

| Concern | Core (internal) | BFF (app-facing) |
|---|---|---|
| Prefix | `/api/v1` | root |
| Checksheet | `/api/v1/checksheets/{code}` (plural) | `/checksheet/{code}` (singular) |
| Health | `/health` (root) | `/health` (root) |

The BFF maps each app-facing path to the matching Core `/api/v1` path in
`bff/src/coreClient.ts`.

## Error codes

`GlobalExceptionHandler` renders every exception as an `ApiResponse`:

| Status | When |
|---|---|
| `400` | Bean Validation failure, bad enum, or a photo that is not a JPEG/PNG/WebP image (bytes are sniffed) |
| `401` | Missing / invalid / expired token, or bad login credentials |
| `404` | Unknown checksheet code, unknown session/photo id, or a cross-tenant resource (reported as not found) |
| `409` | Submitting an already-completed session, or posting events to a completed session |
| `413` | Photo upload over the 10 MB cap |
| `500` | Anything unexpected |

## Photo bytes

There is no public `/files/**` URL. `PhotoDTO.fileUrl` is the BFF-relative path
`/sessions/{id}/photos/{photoId}/content`; the bytes are served only by the
authenticated, tenant-scoped Core endpoint
`GET /api/v1/sessions/{id}/photos/{photoId}/content`. A cross-tenant photo
returns 404; a missing token returns 401.

## See also

- App-facing contract + all DTOs: [../../docs/backend/api.md](../../docs/backend/api.md)
- Schema: [../../docs/backend/data-model.md](../../docs/backend/data-model.md)
- Request walkthroughs: [../../docs/backend/flows.md](../../docs/backend/flows.md)
- Authoritative OpenAPI: [`../../contract/openapi.yaml`](../../contract/openapi.yaml)
</content>
