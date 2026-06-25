# Core API — Internal Flows

How a request moves through the Core's own files. These are the Core-side
internals; for the end-to-end app → BFF → Core → Postgres walkthroughs, see the
canonical system flows:
[../../docs/backend/flows.md](../../docs/backend/flows.md).

Throughout, the BFF calls the Core under `/api/v1` (and `GET /health` at the
root).

## Login + token issuance

1. `POST /api/v1/auth/login` → `AuthController.login` → `AuthService.login`.
2. `UserRepository.findByUsername` (username is globally unique, so this is
   unambiguous), then `BCryptPasswordEncoder.matches`. Bad credentials →
   `ApiException.Unauthorized` → `401`.
3. `JwtService.issueAccessToken` / `issueRefreshToken` sign JWTs whose claims
   include `userId`, `dealershipId`, and `type` (`access` / `refresh`).
4. Returns `ApiResponse { data: { accessToken, refreshToken, user } }`.

Refresh: `POST /api/v1/auth/refresh` → `AuthService.refresh` →
`JwtService.parseRefreshToken` (rejects a wrong-type or expired token with
`401`), then issues a fresh pair.

On later protected requests, `JwtAuthFilter` verifies the Bearer access token
and puts an `AuthenticatedUser` (carrying `userId` + `dealershipId`) into the
security context; controllers receive it via `@CurrentUser`.

## Create + events + outcomes

- **Create:** `POST /api/v1/sessions` → `SessionController.create` →
  `SessionService.create`. Confirms the checksheet exists (unknown → `404`),
  inserts a `sessions` row with `status = ACTIVE` and `dealership_id`/`user_id`
  from the token.
- **Events:** `POST /api/v1/sessions/{id}/events` →
  `SessionService.appendEvents`. Loads the session tenant-scoped
  (`requireOwnedSession`; cross-tenant → `404`), rejects a `COMPLETED` session
  with `409`, then runs `SessionEventRepository.insertIgnoreDuplicate`
  (`INSERT ... ON CONFLICT (session_id, cue_id) DO NOTHING`). `accepted` is the
  count of rows actually inserted, so a re-post returns `accepted: 0`.
- **Read:** `GET /api/v1/sessions/{id}` → `SessionService.get` →
  `deriveOutcomes`: per question, the highest-confidence event wins, satisfied
  when `confidence >= 0.6` (configured threshold). Outcomes are never stored.
- **List:** `GET /api/v1/sessions` → `SessionService.list` — paginated
  (`page`+`size` or `limit`+`offset`; default 50, cap 200) and batch-loads the
  page's events in one `findBySessionIdIn` query (no N+1).
- **Submit:** `POST /api/v1/sessions/{id}/submit` → `SessionService.submit` —
  sets `COMPLETED` + `ended_at`, stores the transcript; a second submit → `409`.

## Photo upload + serve

- **Upload:** `POST /api/v1/sessions/{id}/photos` → `PhotoController.upload` →
  `PhotoService.upload`. After the tenant-scoped session check, it sniffs the
  file's leading bytes and accepts only JPEG/PNG/WebP (else `400`), rejects files
  over 10 MB (`413`), writes the bytes via `StorageService` (`LocalDiskStorage`
  → `<sessionId>/<uuid>.<ext>`), inserts a `photos` row, and returns a
  `PhotoDTO` whose `fileUrl` is the BFF-relative content path.
- **Serve:** `GET /api/v1/sessions/{id}/photos/{photoId}/content` →
  `PhotoController.content` → `PhotoService.content`. Tenant-scoped: a missing
  token → `401`, a cross-tenant photo → `404`. Returns the raw bytes with the
  photo's `Content-Type` — not the JSON envelope. There is no public
  `/files/**` handler.

## Error mapping

`GlobalExceptionHandler` turns every exception into an `ApiResponse`:
`BadRequest`→400, `Unauthorized`→401, `NotFound`→404, `Conflict`→409,
`MaxUploadSizeExceededException`→413, validation→400, anything else→500.

## See also

- System flows (app → BFF → Core → Postgres): [../../docs/backend/flows.md](../../docs/backend/flows.md)
- Internal API: [api.md](./api.md)
- Architecture: [architecture.md](./architecture.md)
</content>
