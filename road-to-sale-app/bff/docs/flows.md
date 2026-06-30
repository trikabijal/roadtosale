# Road to Sale BFF — Flows

Each app request follows the same shape. Two representative walkthroughs.

## Login (public)

1. App → `POST /auth/login` with `{username, password, deviceType}`.
2. `server.ts` validates the body against `loginBody` (schemas.ts). Missing/extra
   fields → `400` envelope, **Core not called**.
3. `routes/auth.ts` handler (wrapped in `withCore`) calls
   `coreClient.login(body)` → `POST {CORE_BASE_URL}/api/v1/auth/login`.
4. Core returns its `ApiResponse` envelope (token pair + user). `relay()` sets
   Core's status and sends the body verbatim.
5. If Core is down, `coreClient` throws `CoreUnreachableError`; `withCore` maps it
   to a `502` envelope.
6. `onResponse` hook logs method/path/status/latency (Authorization redacted).

## Submit a session (protected)

1. App → `POST /sessions/:id/submit` with `Authorization: Bearer <token>` and
   optional `{transcript}`.
2. `requireAuth` preHandler checks the header is present and `Bearer`. Absent →
   `401`, **Core not called**.
3. Body validated against `submitSessionBody`.
4. Handler calls `coreClient.submitSession(id, body, authorization)` →
   `POST {CORE_BASE_URL}/api/v1/sessions/{id}/submit`, forwarding the token.
5. Core enforces tenancy + state: a foreign-tenant id → `404`; an already-
   completed session → `409`. Whatever Core returns is relayed verbatim.

## Upload a photo (multipart)

1. App → `POST /sessions/:id/photos` (multipart: `slot` + `file`) with Bearer.
2. `requireAuth` → 401 if header absent.
3. `routes/photos.ts` iterates `req.parts()`: buffers the `file` part, reads the
   `slot` field.
4. Validates `slot` against the allowed enum and that a file was provided →
   `400` (Core not called) on failure.
5. Rebuilds an undici `FormData` (`slot` + `file` Blob with filename/MIME) and
   calls `coreClient.uploadPhoto(id, form, authorization)` →
   `POST {CORE_BASE_URL}/api/v1/sessions/{id}/photos`.
6. Core's `PhotoDTO` envelope is relayed back.
