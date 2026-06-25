# End-to-End Flows

Three walkthroughs that trace a request through the real files: login, a full
live session, and tenant isolation. Read [architecture.md](./architecture.md)
first for the layer map and [api.md](./api.md) for the endpoint shapes.

Throughout: the app calls the **BFF** (port 8089); the BFF relays to the **Core**
(port 8090); the Core reads/writes **Postgres**.

## Flow 1 — Login and token issuance

The app sends username + password; it gets back an access token, a refresh
token, and the user record.

1. **App → BFF.** `POST /auth/login` with `{ username, password, deviceType }`.
2. **BFF validates.** `authRoutes` (`bff/src/routes/auth.ts`) runs the
   `loginSchema` (`bff/src/schemas.ts`). A missing field returns `400` before the
   Core is touched. The Fastify logger redacts the `password` field.
3. **BFF → Core.** `coreClient.login` (`bff/src/coreClient.ts`) POSTs the body to
   the Core's `POST /auth/login`.
4. **Core authenticates.** `AuthController.login` → `AuthService.login`
   (`backend/.../service/AuthService.java`): looks up the user
   (`UserRepository.findByUsername`), checks the password with
   `BCryptPasswordEncoder.matches`. A wrong username or password throws
   `ApiException.Unauthorized` → `401`.
5. **Core issues tokens.** `JwtService.issueAccessToken` and
   `issueRefreshToken` (`backend/.../security/JwtService.java`) sign JWTs whose
   claims include `userId` and `dealershipId`. The access token carries
   `type=access`; the refresh token carries `type=refresh` so it cannot be used
   as an access token. Lifetimes come from config: access `3600s` (1 hour),
   refresh `2592000s` (30 days).
6. **Core → BFF → App.** The Core returns
   `ApiResponse { status, message, data: { accessToken, refreshToken, user } }`.
   The BFF's `relay` helper passes the envelope and status straight back.

**Refresh** follows the same path: `POST /auth/refresh` →
`AuthService.refresh` → `JwtService.parseRefreshToken` (rejects a wrong-type or
expired token with `401`), then issues a fresh pair.

On later protected requests, `JwtAuthFilter`
(`backend/.../security/JwtAuthFilter.java`) verifies the Bearer access token and
puts an `AuthenticatedUser` (carrying `userId` + `dealershipId`) into the
security context. Controllers receive it via the `@CurrentUser` argument
resolver.

## Flow 2 — A full live session

Create a session, stream cue events, attach a photo, submit, and read back the
derived outcomes. Every request carries `Authorization: Bearer <accessToken>`.

### a. Create the session

- **App → BFF:** `POST /sessions` with `{ type: "LIVE", checksheetCode:
  "RTS_HONDA_V1", context?: { customerName, vehicleOfInterest } }`.
- **Core:** `SessionController.create` → `SessionService.create`. It first calls
  `ChecksheetService.getByCode` to confirm the checksheet exists (unknown →
  `404`), then inserts a `sessions` row with `status = ACTIVE`,
  `dealership_id`/`user_id` taken from the token. Returns a `SessionDTO` with
  empty `outcomes` and `progress { answered: 0, total: 16 }`.

### b. Stream cue events (idempotent by cueId)

- **App → BFF:** `POST /sessions/{id}/events` with
  `{ events: SessionEventDTO[] }`. The `postEventsSchema` requires at least one
  event and validates each (e.g. `confidence` in `0..1`); a bad batch is `400`
  before the Core is called.
- **Core:** `SessionController.appendEvents` → `SessionService.appendEvents`.
  It loads the session tenant-scoped (`requireOwnedSession`; another
  dealership's id → `404`), and rejects a `COMPLETED` session with `409`. For
  each event it runs `SessionEventRepository.insertIgnoreDuplicate`, which does
  `INSERT ... ON CONFLICT (session_id, cue_id) DO NOTHING`. The response
  `{ accepted }` is the count of rows actually inserted — so **re-posting the
  same batch returns `accepted: 0`** and creates no duplicate rows.

### c. Upload a trade-in photo (multipart relay)

- **App → BFF:** `POST /sessions/{id}/photos` as `multipart/form-data` with a
  `slot` text field and a `file` binary part.
- **BFF:** `photoRoutes` (`bff/src/routes/photos.ts`) reads the parts via
  `@fastify/multipart`, validates `slot` against the allowed list (invalid →
  `400`), rebuilds an `undici` `FormData` preserving the `file` and `slot` field
  names, and calls `coreClient.uploadPhoto`.
- **Core:** `PhotoController.upload` → `PhotoService.upload`. After the
  tenant-scoped session check, it writes the bytes through the `StorageService`
  interface. The implementation `LocalDiskStorage`
  (`backend/.../storage/LocalDiskStorage.java`) stores the file under
  `roadtosale.storage.local-dir` (default `./data/photos`), namespaced by
  session: `<sessionId>/<uuid>.<ext>`. It inserts a `photos` row and returns a
  `PhotoDTO` whose `fileUrl` is `/files/<sessionId>/<uuid>.<ext>`.
- **Serving the bytes:** `WebConfig` registers a public resource handler for
  `/files/**` pointing at the storage dir, so `GET <coreBase>/files/...` returns
  the image (no auth needed for the file URL itself).

### d. Submit the session

- **App → BFF:** `POST /sessions/{id}/submit` with optional `{ transcript }`.
- **Core:** `SessionController.submit` → `SessionService.submit`. On an `ACTIVE`
  session it sets `status = COMPLETED`, sets `ended_at = now()`, and stores the
  transcript if provided. Submitting an already-`COMPLETED` session throws
  `ApiException.Conflict` → `409`.

### e. Read back derived outcomes and progress

- **App → BFF:** `GET /sessions/{id}` (or `GET /sessions` for summaries).
- **Core:** `SessionService.get` loads the session and its events, then
  `deriveOutcomes` computes one outcome per question that has events: the
  highest-confidence event wins, and `satisfied = confidence >= 0.6` (the
  configured threshold). `progress.total` is the checksheet's question count
  (16 for `RTS_HONDA_V1`); `progress.answered` is the number of satisfied
  questions. Outcomes are never stored — they are recomputed every read.

## Flow 3 — Tenant isolation

A user in dealership A must never see dealership B's data.

1. `rep1` (Honda of Fremont) logs in and creates a session; its `dealership_id`
   is `rep1`'s, taken from the token (never from the request).
2. `rep2` (Honda of Oakland) logs in and calls `GET /sessions/{rep1SessionId}`.
3. **Core:** `SessionService.get` → `requireOwnedSession` runs
   `SessionRepository.findByIdAndDealershipId(sessionId, rep2.dealershipId())`.
   Because the session belongs to `rep1`'s dealership, the lookup misses and the
   service throws `ApiException.NotFound` → **`404`** (not `403`), so the API does
   not reveal that the session exists.

The same scoping applies to submit, posting events, and photos: each goes through
`requireOwnedSession`, so cross-tenant writes also return `404`. The "list my
sessions" query is scoped by `(dealership_id, user_id)`, so a rep only ever sees
their own sessions.
