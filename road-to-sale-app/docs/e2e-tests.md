# Road to Sale App — Test Plan

**Scope:** the `road-to-sale-app/` Expo / React Native module — the salesperson
walk-in app that guides the 10-step NADA "Road to the Sale", listens on-device to
auto-complete checklist steps, captures trade-in photos, and submits everything
to the external **SmartComply** backend over HTTP.

This is a **plan first**, not a test inventory. It describes what *should* be
tested based on the module's public facades and user journeys, **independent of
what tests exist today**. The tests are the *implementation* of this plan; the
[Coverage ledger](#coverage-ledger-plan-vs-implementation) at the end computes
the delta (covered vs. pending) against the real test files, and the
[Pending test backlog](#pending-test-backlog) is the balance owed.

Read [`architecture.md`](./architecture.md) for the layer map, [`api.md`](./api.md)
for the two API surfaces, and [`flows.md`](./flows.md) for the end-to-end
walkthroughs these journeys mirror. System-level flows live up one level at
[`../../docs/flows.md`](../../docs/flows.md) (and the deeper [`../../docs/ROAD_TO_SALE_TECHNICAL_ARCHITECTURE.md`](../../docs/ROAD_TO_SALE_TECHNICAL_ARCHITECTURE.md)).

**Black-box rule (non-negotiable):** every test here drives a **public facade** —
a singleton facade method (`getSmartComplyClient()`, `getSessionEngine()`,
`getSessionRepository()`, `getVoiceEngine()`), an interface contract
(`ISmartComplyClient`, `ISessionRepository`, `IVoiceEngine`), or a user-facing
screen flow — and asserts an **observable** result (a persisted SQLite row, an
HTTP request body, an emitted session/cue state, a navigation, an inserted
transcript). No test reaches into private methods or asserts internal structure.
The **SmartComply backend is external** (separate repo, consumed over HTTP at
`:8089`); every test mocks it at the **client boundary** — either `global.fetch`
(for `SmartComplyClient` itself) or a fake `ISmartComplyClient` (for everything
above it). The **native voice modules** (Swift `SFSpeechRecognizer`, Kotlin
sherpa-onnx) are out of process; JS tests mock `NativeModules.RtsVoiceModule` and
verify the **JS-bridge contract**, not the recognizers.

---

## 1. Architecture as test surface

The app is layered headless engines behind lazy singletons, with thin reactive
screens on top. Each boundary is a contract; tests are written **against the
contract**, so a screen rewrite never breaks an engine test and a backend change
never breaks anything above the client.

```
  Screens (Auth, Home, SessionSetup, ActiveSession, TradeIn, Summary, History)
        │  (navigation + observable state only)
  ┌─────┴───────────────────────────────────────────────────────────┐
  │  SessionEngine ── ChecklistEngine        AuditProCrmProvider      │
  │       │                                       │                   │
  │  SmartComplyClient (ISmartComplyClient)  ◄────┘   NativeVoiceModule│
  │       │  HTTP (fetch)                              │ JS bridge     │
  │  SqliteSessionRepository ── RetryQueueConsumer    (RtsVoiceModule) │
  └───────┼──────────────────────┼────────────────────┼──────────────┘
   SmartComply backend     SQLite (road_to_sale.db)   Swift / Kotlin STT
       (EXTERNAL)           catalog/loader  cue-packs/loader
```

| Facade / boundary | Public surface under test | Backend mocked at | Tier-1/2 IDs |
|---|---|---|---|
| `SmartComplyClient` (`ISmartComplyClient`) | login, refresh+retry, logout, endpoints, response unwrap, errors, multipart | `global.fetch` | C-API-* |
| `SqliteSessionRepository` (`ISessionRepository`) | create/get/update session, pending-write queue ops, template + appointment caches | `expo-sqlite` mock | C-DB-* |
| `RetryQueueConsumer` | drain, backoff order, dispatch-by-type, 5-retry circuit breaker | fake client + repo | C-RQ-* |
| `SessionEngine` | start/end lifecycle, cue forwarding, override, subscriptions | fake `ISmartComplyClient` | C-SE-* |
| `ChecklistEngine` | init maps, cue→question status, step completeness, override, dedup | (pure) | C-CE-* |
| `NativeVoiceModule` (`IVoiceEngine`) | permissions, start/stop/mute state machine, event fan-out, mock fallback | `NativeModules` mock | C-VOICE-* |
| `catalog/loader` | make/model/trim/feature lookups, availability filter, bundle integrity | (pure bundle) | C-CAT-* |
| `AuditProCrmProvider` | today's appointments filter+map, getAppointment, createWalkIn | fake `ISmartComplyClient` | C-CRM-* |
| App orchestrator (user journeys) | login→home→session→submit, offline sync, voice cue capture, trade-in upload, crash recovery | layered | J1–J11 |

Every public boundary in the table must have ≥1 Tier-1 or Tier-2 test.

---

## 2. Test tiers

| Tier | What | When | Budget |
|---|---|---|---|
| **Tier 1 — critical path** | Login + token refresh; session start→submit happy path; cue→checklist completion; offline write survives + drains; voice start/stop/transcript bridge; catalog lookups; SQLite round-trip | Every commit | < 30 s |
| **Tier 2 — integration / E2E** | Full user journeys end-to-end (login→home→setup→active→summary→submit), offline-first sync + manual resync, crash recovery resume/discard, trade-in capture+background upload, cache-first appointments with offline badge, mute/unmute, multipart upload | Before every PR / release | 2–5 min |
| **Tier 3 — edge / stress** | Concurrent-401 refresh dedup under load, retry-queue circuit-breaker exhaustion, large checksheet/cue volume, malformed/partial backend payloads, rapid voice start/stop, dedup of repeated cues at scale, secure-store unavailable | Weekly / major release | 10–30 min |

Tests that require a real backend, a device, or native STT are **gated/skipped**
when unavailable so CI stays green; they run in the device/integration lane. All
component tests run fully mocked.

---

## 2.5 End-to-end user journeys — the PRIMARY suite

These are written as **salesperson behaviour**, not API calls: each journey drives
the app through its public facades the way a person does (log in → pick a walk-in →
talk through the checklist → submit), across multiple layers, asserting only what
the user/persistence can observe. They are the primary tests; the per-contract
cases in §3 are the component-level backstop a failing journey decomposes into.
Each mirrors a section of [`flows.md`](./flows.md).

> **Test seam:** the four singletons each expose a `set…()` override
> (`setSmartComplyClient`, `setSessionRepository`, `setSessionEngine`,
> `setVoiceEngine`). Journeys inject a fake backend client, an in-memory/fake
> repo, and a scripted voice engine (or use `MockVoiceEngine`), then drive the
> real engines/screens on top. No real network, SQLite file, or native module.

- **J1 — Returning user skips login (Tier 1).** A valid token is in secure store →
  launch. *Expect:* `hasValidToken()` is true and the app lands on **Home**
  without showing the login form. (flows.md (a))
- **J2 — Fresh login (Tier 1, core).** No token → enter credentials → Sign In.
  *Expect:* a `POST /api/user/login` with `deviceType: 'APP'`; on 200 the three
  secure-store keys are written and the app navigates to **Home**; on 401 an
  inline "Invalid username or password." error and **no** navigation.
- **J3 — Full sales session, online (Tier 2, the spine).** Login → Home →
  "New Walk-In" → pick Make/Model/Year/Trim → Start Session → a few final
  transcripts fire cues → Mark Complete one question manually → End Session.
  *Expect:* `getChecksheetDetail` then `startOrResumeSession` (status
  `IN_PROGRESS`) are called; the session is created in SQLite (`status: active`);
  cue detections advance questions to `partial`/`complete`; the manual override
  shows `overridden`; End flips `active → ending → ended` and calls
  `submitSession` (status `SUBMITTED`); the app lands on **SessionSummary**.
  (flows.md (b))
- **J4 — Offline session survives and syncs later (Tier 2, the trust case).**
  Start a session, then force the backend to throw on `submitSession` /
  `submitAnswers`. *Expect:* nothing is lost — the session stays in SQLite, the
  write lands in `pending_writes` (`status: queued`), End Session does **not**
  throw; later `drainPendingWrites()` (foreground) replays it and on success the
  row is **deleted**; the Summary pending banner clears. (flows.md (b5))
- **J5 — Retry circuit breaker (Tier 2).** A pending write whose backend call
  keeps failing. *Expect:* each drain increments `retryCount` (not deleted);
  after the 5th failure the write is marked `failed` (circuit breaker) and is no
  longer dispatched — the data is still safe in SQLite. (flows.md (b5))
- **J6 — Crash recovery: resume vs discard (Tier 2).** App starts with a session
  left `active`. *Expect:* `getActiveSessions()` surfaces it; choosing Resume
  navigates back to **ActiveSession** for that id; choosing Discard updates the
  row to `status: crashed` and it no longer resumes. (flows.md "Crash recovery")
- **J7 — Voice cue capture into the checklist (Tier 1).** On ActiveSession the
  voice engine emits a `final` `TranscriptEvent`. *Expect:* the screen forwards
  only finals to `engine.processCueDetection`, the bound question advances, and a
  `partial` transcript is **not** forwarded. (flows.md (b3), (d))
- **J8 — Mic mute / unmute (Tier 2).** Toggle the mic on ActiveSession. *Expect:*
  `voice.mute()` → state `muted` and frames dropped; `voice.unmute()` → state
  `listening`; no session teardown. (flows.md (d))
- **J9 — Trade-in capture + background upload (Tier 2).** Open Trade-In, capture a
  slot photo. *Expect:* the photo is persisted to the session's `tradeIn` blob in
  SQLite **before** any upload; `uploadTradePhoto(ucId, slot, uri, mime)` fires
  fire-and-forget (multipart) and an upload failure only `console.warn`s — local
  state and photo are intact. (flows.md (c))
- **J10 — Cache-first appointments + offline badge (Tier 2).** Home mounts.
  *Expect:* cached appointments render immediately; a network refresh via
  `AuditProCrmProvider.getTodayAppointments()` updates them and re-caches; if the
  refresh throws while a cache exists, the offline badge shows and **no** error is
  surfaced. (flows.md (b1))
- **J11 — Logout clears credentials (Tier 1).** From Home, Logout. *Expect:* the
  three secure-store keys are deleted, no server call is made, and the app
  navigates to **Auth**. (flows.md (a))

**Journey → tier rule:** J1, J2, J7, J11 are Tier 1 (fast, fully mocked, every
commit). J3–J6, J8–J10 are Tier 2 integration journeys. All J* journeys are
**black-box** and belong in `road-to-sale-app/tests/` (see the [ledger](#coverage-ledger-plan-vs-implementation)
— that directory is currently **empty**).

---

## 3. Component coverage by contract (backstop for the journeys)

Narrower, contract-level cases a failing journey decomposes into — same black-box
rule, one boundary at a time.

### API client — `SmartComplyClient` / `ISmartComplyClient`
- **C-API-1 (Tier 1):** `login()` stores access + refresh tokens (and user id) in
  secure store on 200, and sends `deviceType: 'APP'` in the body.
- **C-API-2 (Tier 1):** `login()` throws `AuthError` on 401.
- **C-API-3 (Tier 1):** an authenticated GET (`getMyAssignments`) attaches
  `Authorization: Bearer <token>`.
- **C-API-4 (Tier 1, critical):** a 401 on an authenticated call triggers one
  `refreshToken` + a single retry, then returns the unwrapped `data`.
- **C-API-5 (Tier 2):** refresh failure clears tokens and throws `AuthError`.
- **C-API-6 (Tier 3):** **concurrent 401s dedup** — N simultaneous 401s trigger
  exactly **one** `refreshToken` call; all callers replay with the new token.
- **C-API-7 (Tier 1):** `startOrResumeSession`/`submitSession` wrap the DTO in a
  single-element array and return index `0` (envelope unwrap).
- **C-API-8 (Tier 2):** `submitAnswers([])` short-circuits — **no** network call.
- **C-API-9 (Tier 1):** static helpers — `hasValidToken()` true/false by token
  presence; `getStoredUserId()` parses int / returns null.
- **C-API-10 (Tier 2):** `logout()` deletes the three secure-store keys and makes
  no HTTP call.
- **C-API-11 (Tier 2):** `uploadTradePhoto` builds `FormData` (file, slot,
  userChecksheetId) and attaches the bearer directly.
- **C-API-12 (Tier 2):** `SmartComplyApiError(status, message)` on other non-2xx
  (e.g. 403) surfaces the status + response text.

### Persistence — `SqliteSessionRepository` / `ISessionRepository`
- **C-DB-1 (Tier 1):** `createSession` issues an `INSERT` and JSON-stringifies the
  customer + vehicle blobs.
- **C-DB-2 (Tier 1):** `getSession` returns null on no row; parses JSON blobs and
  `smartComplyUserChecksheetId` when present.
- **C-DB-3 (Tier 1):** `queuePendingWrite` returns a non-empty id and `INSERT`s
  into `pending_writes`.
- **C-DB-4 (Tier 1):** `getPendingWrites` maps rows to `PendingWrite` objects
  (null `lastAttemptedAt` handled).
- **C-DB-5 (Tier 1):** `markWriteSucceeded` deletes the row; `incrementWriteRetry`
  and `markWriteFailed` update status/count.
- **C-DB-6 (Tier 2):** `cacheTemplate`/`getCachedTemplate` round-trip; miss → null.
- **C-DB-7 (Tier 2):** `cacheAppointments`/`getCachedAppointments` round-trip
  (keyed by `date::repId`); miss → null.
- **C-DB-8 (Tier 2):** `updateSession` persists status transitions and the
  `tradeIn` blob; `getActiveSessions` returns only `status: active`.

### Retry queue — `RetryQueueConsumer`
- **C-RQ-1 (Tier 2):** `drainPendingWrites` dispatches a queued `submitAnswers`
  payload via the client, then `markWriteSucceeded` (row deleted).
- **C-RQ-2 (Tier 2):** dispatch routes by `payload.type` — `submitAnswers` vs
  `submitSession` reach the matching client method; unknown type is dropped.
- **C-RQ-3 (Tier 2):** a failing dispatch calls `incrementWriteRetry`, not delete.
- **C-RQ-4 (Tier 3, circuit breaker):** a write at `retryCount >= 5` is
  `markWriteFailed` and **not** dispatched.
- **C-RQ-5 (Tier 3):** backoff selects `[1s,2s,4s,8s,16s]` by `retryCount`
  (clamped) — assert with fake timers, no real waiting.
- **C-RQ-6 (Tier 2):** `getPendingWrites` throwing (repo not ready) makes drain a
  safe no-op (no unhandled rejection).

### Session lifecycle — `SessionEngine`
- **C-SE-1 (Tier 1):** `startSession` calls `startOrResumeSession` with the
  correct DTO shape and returns a session with `status: active`, retrievable via
  `getSession`.
- **C-SE-2 (Tier 1):** `endSession` transitions `active → ending → ended` and
  calls `submitSession` with the right `userChecksheetId`.
- **C-SE-3 (Tier 1, never-lose):** `endSession` does **not** throw when
  `submitSession` fails (queued for retry); the session still ends.
- **C-SE-4 (Tier 1):** `endSession` throws when the session id is unknown.
- **C-SE-5 (Tier 1):** `processCueDetection` forwards to the checklist engine and
  the resulting state is observable.
- **C-SE-6 (Tier 2):** `overrideQuestion` marks a question `overridden`.
- **C-SE-7 (Tier 1):** `onSessionUpdate` fires on start and end; unsubscribe stops
  further calls.

### Checklist — `ChecklistEngine`
- **C-CE-1 (Tier 1):** `init` builds initial state — all questions `pending`,
  counts correct, cue→question / question→requiredCue maps built.
- **C-CE-2 (Tier 1):** a **required** cue completes its question; a supporting
  (non-required) cue only marks it `partial`.
- **C-CE-3 (Tier 1):** a no-required-cue question completes when **any** cue fires.
- **C-CE-4 (Tier 1):** a step is `complete` only when all its **mandatory**
  questions are complete/overridden; `completedCount` tracks it.
- **C-CE-5 (Tier 2):** duplicate cue ids do not add duplicate `detectedCues`; a
  cue not in the pack is a no-op.
- **C-CE-6 (Tier 1):** `overrideQuestion` sets `overridden` + note, recomputes
  step completeness, and a later cue does **not** downgrade it.
- **C-CE-7 (Tier 2):** `onUpdate` fires on every cue/override with the updated
  state; unsubscribe stops it.

### Voice bridge — `NativeVoiceModule` / `IVoiceEngine`
- **C-VOICE-1 (Tier 1):** `requestPermissions()` delegates to native and returns
  the `PermissionStatus`.
- **C-VOICE-2 (Tier 1):** `start()` transitions to `starting` synchronously, then
  to `listening` when native fires `onVoiceStateChange`; `customVocabulary` is
  passed to native `startListening`.
- **C-VOICE-3 (Tier 1):** `stop()` transitions to `stopping`, calls native
  `stopListening`, and reflects `stopped` on the native event.
- **C-VOICE-4 (Tier 2):** `mute()`/`unmute()` call native and move state
  `muted`/`listening`.
- **C-VOICE-5 (Tier 1):** `onTranscript` fans `onTranscriptEvent` (object **and**
  JSON-string payloads) out to listeners; unsubscribe stops delivery.
- **C-VOICE-6 (Tier 2):** `onError` wraps a native error string into an `Error`
  and delivers it.
- **C-VOICE-7 (Tier 2, mock fallback):** with `NativeModules.RtsVoiceModule`
  absent, `isMocked` is true, `start()` flips to `listening` with no native call,
  and `requestPermissions()` returns `'granted'` — the app stays runnable.

### Catalog — `catalog/loader`
- **C-CAT-1 (Tier 1):** `list_makes` returns ≥1 make incl. Honda; each has id+name.
- **C-CAT-2 (Tier 1):** `list_models` (all / filtered by make) — Honda models,
  numeric `year`, known models exist, unknown make → empty.
- **C-CAT-3 (Tier 1):** `list_trims` (all / by model) — correct trim, no `year`
  field, unknown model → empty.
- **C-CAT-4 (Tier 1):** `get_make/get_model/get_trim/get_feature` return the right
  entity and **throw** on unknown id.
- **C-CAT-5 (Tier 2):** `list_features_for_trim` returns features (id, display,
  category, brand_scope); default = standard-availability only; explicit
  `["standard"]` matches default; unknown trim → empty.
- **C-CAT-6 (Tier 2):** bundle integrity — ≥1 make, ≥5 models, ≥10 trims, Honda +
  Toyota present, non-empty `generatedAt`.

### CRM provider — `AuditProCrmProvider`
- **C-CRM-1 (Tier 1):** `getTodayAppointments` returns only `ASSIGNED` /
  `IN_PROGRESS` assignments, mapped to `Appointment`; fields mapped correctly;
  none → empty array.
- **C-CRM-2 (Tier 2):** `getAppointment(id)` finds by string id; throws when
  absent.
- **C-CRM-3 (Tier 2):** `createWalkIn` calls `createWalkInAssignment` with parsed
  repId + env locationId and returns the newest `ASSIGNED` appointment; throws if
  none appears after creation.

---

## 4. Black-box discipline note

- Journeys (J*) and contract cases (C-*) interact **only** through public facades:
  singleton accessors, the `I*` interfaces, and screen navigation/observable
  state. No importing of private helpers, no asserting internal field layout
  beyond the documented DTO/state shapes.
- The **backend is mocked at exactly one boundary**: `global.fetch` for
  `SmartComplyClient`'s own tests, a fake `ISmartComplyClient` for every layer
  above it. Tests never assume backend internals beyond the documented HTTP
  contract ([`smartcomply-contract.md`](./smartcomply-contract.md)).
- The **native STT modules are never executed** in JS tests — only the
  `NativeModules.RtsVoiceModule` bridge surface and event payloads are asserted.
  Real recognizer accuracy is a device-lane concern, not part of this suite.

## 5. Test data / fixtures

- **Checksheet + cue pack:** a small fixture `ChecksheetDTO` with two steps /
  mixed mandatory questions, plus a matching `CuePackEntry[]` (required and
  non-required bindings) — used by all `ChecklistEngine` / `SessionEngine` /
  journey tests.
- **Assignments:** a fixture `AssignmentDTO[]` covering `ASSIGNED`,
  `IN_PROGRESS`, and a filtered-out status, for the CRM provider.
- **Transcript events:** scripted `TranscriptEvent`s (partial + final, object and
  JSON-string form) fed through the voice facade.
- **Catalog:** the real built `bundle.json` (Honda + Toyota) is the fixture.
- **Backend responses:** canned `ApiResponse<T>` envelopes for each endpoint, plus
  401/403/5xx and malformed-envelope variants.
- Fixtures co-located with their suite or under `tests/fixtures/` for journeys.
  No real customer audio/text/PII in the repo.

## 6. Conventions & platform matrix

- **Runner:** Jest + `jest-expo`. Component/contract tests (`src/**/*.test.ts`)
  run fully mocked on every commit; black-box journeys live in
  `road-to-sale-app/tests/` and may run in a separate (slower) lane.
- **Native + backend gating:** anything needing a real device, real SQLite file,
  or real backend is **skipped** (not failed) when unavailable, so CI stays green.
- **Platform matrix:** the JS layer (engines, client, repo, voice bridge) is
  platform-neutral and tested once. iOS-vs-Android divergence lives in the native
  modules (`RtsVoiceModule.swift` vs `.kt`) and is verified on-device, not here;
  JS tests assert only the shared bridge contract and `engine_metadata.engine`
  tag passthrough.
- Latency/observable-timing assertions use fake timers — no real `setTimeout`
  waiting in the retry-queue tests.

---

## Coverage ledger (plan vs. implementation)

Computed against the **real** test files on branch `docs/refresh`:
`src/api/SmartComplyClient.test.ts`, `src/db/SqliteSessionRepository.test.ts`,
`src/session/SessionEngine.test.ts`, `src/session/ChecklistEngine.test.ts`,
`src/voice/NativeVoiceModule.test.ts`, `src/catalog/loader.test.ts`,
`src/crm/AuditProCrmProvider.test.ts` — **106 tests total, all
unit/component-level.**

> **Structural gap (headline):** `road-to-sale-app/tests/` contains **only a
> `.gitkeep`** — it is an empty reserved directory. There are **NO black-box,
> multi-layer E2E journey tests** (J1–J11) yet. Every one of the 106 existing
> tests targets a single facade in isolation with the layer below mocked. The
> primary suite (§2.5) is entirely **pending**.
>
> **Second gap:** `src/db/RetryQueueConsumer.ts` (drain, backoff, dispatch-by-
> type, the 5-retry **circuit breaker**, crash-recovery wiring) has **no test
> file at all** — the entire C-RQ-* block and journeys J4/J5/J6 are unverified.
> Per the global circuit-breaker standard this is the most important untested
> code in the module.

### Component contracts (§3)

| Plan item | Tier | Status | Notes |
|---|---|---|---|
| C-API-1 | 1 | ✅ COVERED | `SmartComplyClient.test.ts › login() › stores access and refresh tokens…` + `› sends deviceType APP in the request body` |
| C-API-2 | 1 | ✅ COVERED | `SmartComplyClient.test.ts › login() › throws AuthError on 401` |
| C-API-3 | 1 | ✅ COVERED | `…getMyAssignments() › sends Authorization: Bearer header when a token is stored` |
| C-API-4 | 1 | ✅ COVERED | `…getMyAssignments() › triggers token refresh + retry on 401, then returns data` |
| C-API-5 | 2 | ✅ COVERED | `…getMyAssignments() › throws AuthError when refresh also fails` |
| C-API-6 | 3 | ⛔ PENDING | concurrent-401 single-refresh **dedup** under load is not exercised (only the single-401 path) |
| C-API-7 | 1 | ✅ COVERED | `…startOrResumeSession() › wraps the DTO in an array and returns the first element` |
| C-API-8 | 2 | ⛔ PENDING | `submitAnswers([])` no-network short-circuit untested |
| C-API-9 | 1 | ✅ COVERED | `…static helpers › hasValidToken() …` (×2) + `getStoredUserId() …` (×2) |
| C-API-10 | 2 | ⛔ PENDING | `logout()` deleting the three keys / no HTTP call untested |
| C-API-11 | 2 | ⛔ PENDING | `uploadTradePhoto` multipart/FormData + bearer untested |
| C-API-12 | 2 | ⛔ PENDING | `SmartComplyApiError` (403/non-2xx) path untested |
| C-DB-1 | 1 | ✅ COVERED | `SqliteSessionRepository.test.ts › createSession() › INSERT…` + `› JSON-stringifies customer and vehicle fields` |
| C-DB-2 | 1 | ✅ COVERED | `…getSession() › returns null…` + `› parses JSON fields…` + `› parses smartComplyUserChecksheetId when present` |
| C-DB-3 | 1 | ✅ COVERED | `…queuePendingWrite() › returns a non-empty string ID` + `› INSERT INTO pending_writes` |
| C-DB-4 | 1 | ✅ COVERED | `…getPendingWrites() › maps rows to PendingWrite objects` + `› sets lastAttemptedAt to null…` |
| C-DB-5 | 1 | ⚠️ PARTIAL | `…markWriteSucceeded() › calls DELETE…` covered; **`incrementWriteRetry` and `markWriteFailed` have NO test** |
| C-DB-6 | 2 | ✅ COVERED | `…cacheTemplate()/getCachedTemplate() › round-trips…` + `› returns null for cache miss` |
| C-DB-7 | 2 | ✅ COVERED | `…cacheAppointments()/getCachedAppointments() › round-trips…` + `› returns null for cache miss` |
| C-DB-8 | 2 | ⛔ PENDING | `updateSession` (status transitions, `tradeIn` blob) and `getActiveSessions` untested |
| C-RQ-1 | 2 | ⛔ PENDING | no `RetryQueueConsumer` test file exists |
| C-RQ-2 | 2 | ⛔ PENDING | dispatch-by-type untested |
| C-RQ-3 | 2 | ⛔ PENDING | failure→increment untested |
| C-RQ-4 | 3 | ⛔ PENDING | **circuit breaker (retry ≥5 → failed) untested** |
| C-RQ-5 | 3 | ⛔ PENDING | backoff sequence untested |
| C-RQ-6 | 2 | ⛔ PENDING | drain no-op when repo not ready untested |
| C-SE-1 | 1 | ✅ COVERED | `SessionEngine.test.ts › startSession() › correct DTO shape` + `› status active` + `› stores…via getSession()` |
| C-SE-2 | 1 | ✅ COVERED | `…endSession() › transitions status through ending → ended` + `› calls submitSession with the correct userChecksheetId` |
| C-SE-3 | 1 | ✅ COVERED | `…endSession() › does not throw if submitSession fails (queues for retry)` |
| C-SE-4 | 1 | ✅ COVERED | `…endSession() › throws when sessionId does not exist` |
| C-SE-5 | 1 | ⚠️ PARTIAL | covered transitively via `ChecklistEngine` tests, but `SessionEngine.processCueDetection` has no **direct** test |
| C-SE-6 | 2 | ⚠️ PARTIAL | `overrideQuestion` tested on `ChecklistEngine`; **not** through `SessionEngine.overrideQuestion` |
| C-SE-7 | 1 | ✅ COVERED | `…onSessionUpdate() › fires on startSession` + `› fires on endSession` + `› unsubscribe…` |
| C-CE-1 | 1 | ✅ COVERED | `ChecklistEngine.test.ts › init() › builds correct initial state…` |
| C-CE-2 | 1 | ✅ COVERED | `…processCueDetection() › marks question partial when only a supporting cue…` + `› marks question complete when the required cue fires` |
| C-CE-3 | 1 | ✅ COVERED | `…processCueDetection() › completes a no-required-cue question when any cue fires` |
| C-CE-4 | 1 | ✅ COVERED | `…› marks step complete when all mandatory questions…` + `› updates completedCount…` |
| C-CE-5 | 2 | ✅ COVERED | `…› does not add duplicate detectedCues…` + `› is a no-op for a cue not in the pack` |
| C-CE-6 | 1 | ✅ COVERED | `…overrideQuestion() › sets status to overridden…` + `› recomputes step isComplete…` + `› does not downgrade an already-overridden question…` |
| C-CE-7 | 2 | ✅ COVERED | `…onUpdate() › fires…processCueDetection` + `› fires…overrideQuestion` + `› unsubscribe…` + `› passes updated state…` |
| C-VOICE-1 | 1 | ✅ COVERED | `NativeVoiceModule.test.ts › requestPermissions() › delegates to native…returns "granted"` |
| C-VOICE-2 | 1 | ✅ COVERED | `…start() › transitions to "starting" synchronously…` + `› passes customVocabulary…` + `› transitions to "listening" when native fires…` |
| C-VOICE-3 | 1 | ✅ COVERED | `…stop() › transitions state to "stopping" then calls native stopListening` + `› reflects "stopped" state…` |
| C-VOICE-4 | 2 | ✅ COVERED | `…mute() / unmute() › mute()…"muted"` + `› unmute()…"listening"` |
| C-VOICE-5 | 1 | ✅ COVERED | `…onTranscript() › delivers TranscriptEvent…` + `› parses JSON string payloads…` + `› returns an unsubscribe function…` |
| C-VOICE-6 | 2 | ✅ COVERED | `…onError() › delivers Error objects when native fires onVoiceError` |
| C-VOICE-7 | 2 | ⛔ PENDING | **mock-fallback path (`isMocked`, no native module) untested** — tests only run with the native mock present |
| C-CAT-1 | 1 | ✅ COVERED | `loader.test.ts › list_makes ›` (×3) |
| C-CAT-2 | 1 | ✅ COVERED | `…list_models ›` (×6 incl. unknown make → empty) |
| C-CAT-3 | 1 | ✅ COVERED | `…list_trims ›` (×5 incl. no year, unknown model → empty) |
| C-CAT-4 | 1 | ✅ COVERED | `…get_make/get_model/get_trim/get_feature ›` returns + throws-on-unknown |
| C-CAT-5 | 2 | ✅ COVERED | `…list_features_for_trim ›` (×7 incl. default standard-only, explicit `["standard"]`, unknown → empty) |
| C-CAT-6 | 2 | ✅ COVERED | `…Bundle integrity ›` (×5) |
| C-CRM-1 | 1 | ✅ COVERED | `AuditProCrmProvider.test.ts › getTodayAppointments() › returns only ASSIGNED and IN_PROGRESS…` + `› maps fields…` + `› empty array when none` |
| C-CRM-2 | 2 | ✅ COVERED | `…getAppointment() › finds the correct assignment by string id` + `› throws if…not found` |
| C-CRM-3 | 2 | ✅ COVERED | `…createWalkIn() › calls createWalkInAssignment…` + `› returns the newest ASSIGNED…` + `› throws if no ASSIGNED…` |

### End-to-end journeys (§2.5)

| Journey | Tier | Status | Notes |
|---|---|---|---|
| J1 Returning user skips login | 1 | ⛔ PENDING | `hasValidToken()` is unit-tested, but the Auth-screen → Home navigation journey is not |
| J2 Fresh login | 1 | ⚠️ PARTIAL | client-level login/401 covered (C-API-1/2); screen flow + navigation untested |
| J3 Full sales session online | 2 | ⛔ PENDING | each layer is unit-tested in isolation; no end-to-end setup→active→submit journey |
| J4 Offline session survives + syncs | 2 | ⛔ PENDING | depends on the untested `RetryQueueConsumer`; no journey |
| J5 Retry circuit breaker | 2 | ⛔ PENDING | `RetryQueueConsumer` untested entirely |
| J6 Crash recovery resume/discard | 2 | ⛔ PENDING | `getActiveSessions` + RootNavigator prompt untested |
| J7 Voice cue capture into checklist | 1 | ⚠️ PARTIAL | voice events (C-VOICE-5) and cue→checklist (C-CE-2) covered separately; the screen wiring that forwards **only finals** to the engine is untested |
| J8 Mic mute/unmute | 2 | ⚠️ PARTIAL | facade mute/unmute covered (C-VOICE-4); screen-level toggle journey untested |
| J9 Trade-in capture + bg upload | 2 | ⛔ PENDING | TradeIn screen, local-first persist, fire-and-forget upload untested |
| J10 Cache-first appts + offline badge | 2 | ⚠️ PARTIAL | provider + cache round-trip covered (C-CRM-1, C-DB-7); Home cache-first-then-refresh + badge journey untested |
| J11 Logout clears credentials | 1 | ⛔ PENDING | `logout()` key deletion (C-API-10) and the Home→Auth navigation both untested |

**Tally:** plan items = **11 journeys + 49 component contracts = 60**.
- ✅ COVERED: **34** (all component-level).
- ⚠️ PARTIAL: **7** (C-DB-5, C-SE-5, C-SE-6, J2, J7, J8, J10).
- ⛔ PENDING: **19** (5 component + 11 journeys… counted precisely below).

---

## Pending test backlog

The balance owed. **No test code was written or modified for this plan** — this is
the documentation deliverable only. Each item below is a concrete test to write,
grouped by tier. PARTIAL items are listed as the *remaining* slice to finish.

### Tier 1 (run every commit) — 4
1. **C-API-8** — `submitAnswers([])` returns without calling `fetch` (assert mock not called).
2. **C-SE-5 (finish)** — direct `SessionEngine.processCueDetection` test: a forwarded detection advances the checklist and emits.
3. **J1** — Auth screen with a valid stored token navigates straight to Home (no login form).
4. **J11** — `logout()` deletes the three secure-store keys, makes no HTTP call, and Home→Auth navigates.

### Tier 2 (integration / E2E journeys, before PR/release) — 11
5. **C-API-10** — `logout()` key deletion + no network (client-level).
6. **C-API-11** — `uploadTradePhoto` builds correct multipart FormData + bearer.
7. **C-API-12** — non-2xx (403) throws `SmartComplyApiError(status, message)`.
8. **C-DB-5 (finish)** — `incrementWriteRetry` and `markWriteFailed` update count/status.
9. **C-DB-8** — `updateSession` persists status + `tradeIn`; `getActiveSessions` returns only active.
10. **C-RQ-1/2/3/6** — `RetryQueueConsumer`: drain→dispatch→delete; dispatch-by-type; failure→increment; safe no-op when repo not ready.
11. **C-VOICE-7** — mock-fallback voice engine (`isMocked`) keeps the app runnable.
12. **C-SE-6 (finish)** — `SessionEngine.overrideQuestion` marks the question overridden through the engine facade.
13. **J3** — full online session journey (setup → active cue capture + manual override → end → submit → Summary).
14. **J4** — offline session: writes land in `pending_writes`, End doesn't throw, later drain deletes the row.
15. **J9** — Trade-in: photo persisted locally first, `uploadTradePhoto` fire-and-forget, upload failure tolerated.
16. (journey finishers) **J7/J8/J10** — screen-level wiring: finals-only forwarding into the engine; mic toggle; Home cache-first-then-refresh + offline badge.

### Tier 3 (edge / stress, weekly / major release) — 4
17. **C-API-6** — concurrent 401s trigger exactly one `refreshToken`; all callers replay.
18. **C-RQ-4** — **circuit breaker:** a write at `retryCount >= 5` is `markWriteFailed` and not dispatched.
19. **C-RQ-5** — backoff selects `[1s,2s,4s,8s,16s]` by retryCount (fake timers).
20. **J5/J6** — retry-queue exhaustion journey; crash-recovery Resume vs Discard (`getActiveSessions` → RootNavigator prompt → `status: crashed`).

**Pending summary:** ~**20 test areas** to write — **4 Tier-1**, **~12 Tier-2
(incl. the 5 E2E journeys J3/J4/J7/J8/J9/J10 and the J1/J11 fast ones above)**,
**4 Tier-3**. The two biggest holes are (1) the **entire `tests/` E2E journey
directory is empty** (no black-box, multi-layer user-journey test exists) and
(2) **`RetryQueueConsumer` — including the offline circuit breaker — has zero
tests**, which is the offline-first guarantee the whole app rests on.

---

## Cross-references

- This module: [`architecture.md`](./architecture.md) · [`api.md`](./api.md) · [`flows.md`](./flows.md) · [`smartcomply-contract.md`](./smartcomply-contract.md)
- System flows (up): [`../../docs/flows.md`](../../docs/flows.md), [`../../docs/architecture.md`](../../docs/architecture.md)
- Sibling test plan modelled on: [`../../dictation/docs/e2e-tests.md`](../../dictation/docs/e2e-tests.md)
- Voice contract this app mirrors (across): [`../../voice-engine/docs/model-contracts.md`](../../voice-engine/docs/model-contracts.md)
</content>
</invoke>
