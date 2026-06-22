# Road to Sale App — Architecture

The mobile app a Honda showroom salesperson runs during a customer walk-in. It
guides them through the 10-step NADA "Road to the Sale" process, listens to the
conversation on-device to auto-complete checklist steps, captures trade-in
photos, and submits everything to the **SmartComply** compliance backend.

This is a real, working **Expo / React Native** app (Expo SDK 56, RN 0.85,
React 19). It ships its **own** native speech-to-text on both platforms — it
does **not** bundle the `voice-engine/` package. Instead it *mirrors* that
package's TypeScript contracts so cue events look identical across the product
family (see [Voice bridge](#5-voice-layer--native-stt-bridge)).

> New here? Read this file top-to-bottom, then [`flows.md`](./flows.md) for the
> end-to-end walkthroughs and [`api.md`](./api.md) for the two API surfaces
> (SmartComply HTTP + the native voice module JS API).
>
> To build, test, and run it locally, see [`build.md`](./build.md).

---

## 1. Top-level shape

```
road-to-sale-app/
├── index.ts                 registerRootComponent(App)
├── App.tsx                  StatusBar + RootNavigator + useRetryQueueConsumer()
├── app.json                 Expo config; registers ./plugins/withVoiceModule
├── plugins/withVoiceModule.ts   Expo config plugin — wires native voice into iOS/Android
├── src/
│   ├── navigation/          RootNavigator + the param-list types
│   ├── screens/             7 screens (Auth, Home, SessionSetup, ActiveSession, TradeIn, SessionSummary, History)
│   ├── api/                 SmartComplyClient — HTTP client to the backend
│   ├── db/                  SQLite repo + offline retry queue + caches
│   ├── session/             SessionEngine + ChecklistEngine (business state)
│   ├── voice/               NativeVoiceModule — JS bridge to native STT
│   ├── cue-packs/           Cue → checklist-question bindings (YAML → JSON)
│   ├── catalog/             Vehicle make/model/trim/feature lookup (bundle.json)
│   ├── crm/                 AuditProCrmProvider — appointments adapter over the API client
│   └── theme/               Colors, typography, dark-mode aware
├── ios/VoiceModule/         Swift native module (SFSpeechRecognizer)
└── android/.../voice/       Kotlin native module (sherpa-onnx Whisper + Silero VAD + foreground service)
```

Everything outside `screens/` is **headless** — the screens are thin and
reactive; all state and side effects live in the engine / repo / client layers,
each reached through a **lazy singleton** (`getSessionEngine()`,
`getSessionRepository()`, `getSmartComplyClient()`, `getVoiceEngine()`). The
singletons are the only construction points and each exposes a `set…()` override
for test injection. Screens never `new` these classes directly.

---

## 2. Navigation & screens

Entry chain: `index.ts` → `App.tsx` → `RootNavigator` (a
`createNativeStackNavigator`). The route param contract lives in
[`src/navigation/types.ts`](../src/navigation/types.ts) (`RootStackParamList`).

| Screen | Route params | Role |
|---|---|---|
| **Auth** (`AuthScreen.tsx`) | — | Login. On mount, `SmartComplyClient.hasValidToken()` skips straight to Home if a token exists. `initialRouteName`. |
| **Home** (`HomeScreen.tsx`) | — | Today's appointments (cache-first via `AuditProCrmProvider`), a pending-sync badge, logout, and the "New Walk-In" CTA. |
| **SessionSetup** (`SessionSetupScreen.tsx`) | `{ appointment?: AppointmentBrief }` | Customer info + cascading vehicle picker (Make→Model→Year→Trim) + trim highlights. "Start Session" fetches the checksheet, loads the cue pack, and starts the engine. |
| **ActiveSession** (`ActiveSessionScreen.tsx`) | `{ sessionId }` | The live coaching screen: checklist, mic indicator, voice → cue capture, manual overrides, Trade-In + End Session. `headerBackVisible: false`. |
| **TradeIn** (`TradeInScreen.tsx`) | `{ sessionId }` | 7-slot photo capture (expo-camera) + voice notes + condition note; background photo upload. |
| **SessionSummary** (`SessionSummaryScreen.tsx`) | `{ sessionId; readOnly? }` | Read-back of a completed session, pending-write sync button, PDF/text share. |
| **History** (`HistoryScreen.tsx`) | — | Past sessions from SQLite, opens summary in `readOnly`. |

**Crash recovery:** `RootNavigator` checks `getActiveSessions()` on mount; if a
session is still `active` it offers Resume (navigate to `ActiveSession`) or
Discard (mark `crashed`).

---

## 3. The API layer (`src/api/`)

`SmartComplyClient` ([`SmartComplyClient.ts`](../src/api/SmartComplyClient.ts))
is the single HTTP client to the SmartComply backend, implementing the
`ISmartComplyClient` facade interface. It owns:

- **JWT auth** — `login()` / `logout()` and silent token refresh. Tokens and the
  user id live in `expo-secure-store` (iOS Keychain / Android Keystore) under
  keys `rts_access_token`, `rts_refresh_token`, `rts_user_id`.
- **Silent refresh with in-flight dedup** — a `401` on any authenticated request
  triggers one `refreshToken` call; concurrent 401s queue on `_refreshQueue` and
  replay with the new token. Refresh failure clears tokens and throws `AuthError`.
- **Response unwrapping** — every JSON response is `ApiResponse<T> = { status,
  message, data }`; the client returns `data`.
- **Errors** — `AuthError` (auth/refresh failed) and `SmartComplyApiError(status,
  message)` (other non-2xx).

> The full request/response contract is documented in
> [`smartcomply-contract.md`](./smartcomply-contract.md) and summarized in
> [`api.md`](./api.md). **The SmartComply backend source is no longer in this
> repo — it is a separate service the app reaches over HTTP at `:8089`.** The
> base URL is resolved (in [`clientSingleton.ts`](../src/api/clientSingleton.ts))
> from `Constants.expoConfig.extra.smartComplyApiUrl`, then
> `SMARTCOMPLY_API_URL`, defaulting to `http://localhost:8089`.

`src/crm/AuditProCrmProvider.ts` is a thin adapter over the client that maps
`myAssignments` into the `Appointment` shape Home renders.

---

## 4. Persistence & offline-first (`src/db/`)

The app is **offline-first**. Local SQLite (`expo-sqlite`, db
`road_to_sale.db`) is the source of truth for sessions; the network is a
best-effort sync target.

`SqliteSessionRepository` (behind `ISessionRepository`) owns four tables
([`schema.ts`](../src/db/schema.ts)):

| Table | Purpose |
|---|---|
| `sessions` | One row per walk-in. Customer / vehicle / checklist / trade-in stored as JSON blobs. `status ∈ active \| ending \| ended \| crashed`. |
| `pending_writes` | The **retry queue** — answer batches / submits that haven't reached SmartComply yet. `retry_count`, `status ∈ queued \| failed`. |
| `template_cache` | Cached NADA checksheet (keyed by template id) so sessions can start offline. |
| `appointments_cache` | Cached Home appointments (keyed by `date::repId`). |

### Retry queue

[`RetryQueueConsumer.ts`](../src/db/RetryQueueConsumer.ts) drains
`pending_writes` with capped exponential backoff `[1s, 2s, 4s, 8s, 16s]`,
`MAX_RETRIES = 5`. `dispatchWrite()` replays each payload by `type`
(`submitAnswers`, `submitSession`) against the live client; success deletes the
row, failure increments the retry count. After 5 failures the write is marked
`failed` — a circuit breaker that stops burning the network but keeps the data
safe in SQLite.

The hook `useRetryQueueConsumer()` is mounted once in `App.tsx`; it drains on
mount and on every `AppState 'active'` (app foregrounded / connectivity
restored). `SessionSummaryScreen` also offers a manual "Sync to SmartComply".

---

## 5. Voice layer — native STT bridge

This is the most platform-specific part of the app. The voice engine runs
**entirely on-device** (no audio leaves the phone) and is reached from JS through
one facade.

### JS side (`src/voice/`)

- [`IVoiceEngine.ts`](../src/voice/IVoiceEngine.ts) — the facade interface:
  `requestPermissions`, `start(options)`, `stop`, `mute`, `unmute`, and the three
  subscription methods `onTranscript`, `onStateChange`, `onError`. State machine:
  `idle → starting → listening ⇄ muted → stopping → stopped` (+ `error`).
- [`NativeVoiceModule.ts`](../src/voice/NativeVoiceModule.ts) — the
  implementation. It grabs `NativeModules.RtsVoiceModule` and wraps it in a
  `NativeEventEmitter`, translating the three native events
  (`onTranscriptEvent`, `onVoiceStateChange`, `onVoiceError`) into JS listeners.
  Exposed via the `getVoiceEngine()` singleton.
- **Graceful mock fallback** — if `RtsVoiceModule` isn't linked (Expo Go, web,
  simulator without prebuild), `isMocked` is true and `start()` just flips state
  to `listening` with no audio. The app stays runnable everywhere.
- [`types.ts`](../src/voice/types.ts) — `TranscriptEvent` / `CueDetection`. This
  file is a **deliberate mirror** of `voice-engine/src/types/index.ts`; the
  header comment says "must stay in sync."

### iOS native (`ios/VoiceModule/`)

[`RtsVoiceModule.swift`](../ios/VoiceModule/RtsVoiceModule.swift) — an
`RCTEventEmitter` using **`SFSpeechRecognizer`** with
`requiresOnDeviceRecognition = true` and partial results. `AVAudioEngine` taps
the mic; `customVocabulary` is passed as `contextualStrings`. Emits
`onTranscriptEvent` (`engine: "apple_speech_transcriber"`),
`onVoiceStateChange`, `onVoiceError`. [`RtsVoiceModule.m`](../ios/VoiceModule/RtsVoiceModule.m)
is the `RCT_EXTERN_MODULE` bridge declaration.

### Android native (`android/.../voice/`)

[`RtsVoiceModule.kt`](../android/app/src/main/kotlin/com/trika/roadtosale/voice/RtsVoiceModule.kt)
— a `ReactContextBaseJavaModule` running **sherpa-onnx**: `AudioRecord` feeds 16
kHz PCM into a **Silero VAD**, and each detected speech segment is transcribed by
an **offline Whisper `small.en`** recognizer (int8). A coroutine on
`Dispatchers.IO` owns the recording loop. Emits the same three events
(`engine: "sherpa_onnx_vad"`). A **foreground service**
([`RtsVoiceForegroundService.kt`](../android/app/src/main/kotlin/com/trika/roadtosale/voice/RtsVoiceForegroundService.kt),
`foregroundServiceType="microphone"`) keeps the mic alive while the screen is
backgrounded. Registered through
[`RtsVoicePackage.kt`](../android/app/src/main/kotlin/com/trika/roadtosale/voice/RtsVoicePackage.kt).

### The Expo config plugin

[`plugins/withVoiceModule.ts`](../plugins/withVoiceModule.ts) (referenced in
`app.json` → `plugins`) wires the native code in at `expo prebuild` so the bridge
is not a hand-edited native project:

- **iOS:** adds the three `VoiceModule/*` files to the app target and sets the
  Swift Objective-C bridging header.
- **Android:** adds the sherpa-onnx AAR + JitPack repo to `build.gradle` and
  registers `RtsVoicePackage()` in `MainApplication.kt`.

Permissions/usage strings are declared in `app.json` (`NSMicrophoneUsageDescription`,
`NSSpeechRecognitionUsageDescription`, iOS `UIBackgroundModes: ["audio"]`;
Android `RECORD_AUDIO`, `FOREGROUND_SERVICE`).

> **Relationship to `voice-engine/`:** the app does not import that package — it
> re-implements the same contracts natively. The canonical contract these mirror
> is [`../../voice-engine/docs/model-contracts.md`](../../voice-engine/docs/model-contracts.md)
> (`TranscriptionStrategy` / `TranscriptEvent`). Keep `src/voice/types.ts` and
> the native event payloads aligned with it.

---

## 6. Session engine & checklist (`src/session/`)

Pure, in-memory business logic — no React, no native, no I/O except the one
SmartComply call to flip status.

- **`SessionEngine`** ([`SessionEngine.ts`](../src/session/SessionEngine.ts)) —
  `startSession()` calls `client.startOrResumeSession()` (→ `IN_PROGRESS`),
  builds a `ChecklistEngine`, and holds a `Session` per id in a `Map`.
  `processCueDetection()` forwards detections to the checklist engine;
  `endSession()` flips to `ending → ended` and submits to SmartComply.
  `overrideQuestion()` is the manual "Mark Complete". Screens subscribe via
  `onSessionUpdate()`.
- **`ChecklistEngine`** ([`ChecklistEngine.ts`](../src/session/ChecklistEngine.ts))
  — `init(checksheet, cuePackEntries)` builds two lookup maps: `cueId →
  questionIds` and `questionId → requiredCueIds`. A question becomes `complete`
  when all its required cues have fired (or any cue fires if none are required),
  `partial` after at least one cue, `overridden` on manual confirm. A step is
  complete when all its **mandatory** questions are complete/overridden.

The SmartComply checksheet (NADA template) defines the steps/questions; the
**cue pack** binds detected cue ids to those questions.

---

## 7. Cue packs & vehicle catalog

- **`src/cue-packs/`** — `road-to-sale-v1.yaml` is the human-authored source;
  `build-cue-pack.ts` compiles it to `road-to-sale-v1.json` (Metro can't import
  YAML). [`loader.ts`](../src/cue-packs/loader.ts) imports the JSON as
  `CuePackEntry[]` (`cueId`, `templateQuestionId`, `required`, optional
  `okOptionId`). Run the build step before starting the app.
- **`src/catalog/`** — `build-catalog.ts` compiles the vehicle data into
  `bundle.json`; [`loader.ts`](../src/catalog/loader.ts) loads it once and
  exposes indexed lookups (`list_makes`, `list_models`, `list_trims`,
  `list_features_for_trim`). Drives the SessionSetup vehicle picker and trim
  highlights.

---

## 8. Where to go next

- [`api.md`](./api.md) — the two API surfaces consumed by this app.
- [`flows.md`](./flows.md) — step-by-step UI→backend walkthroughs.
- [`smartcomply-contract.md`](./smartcomply-contract.md) — full backend HTTP contract.
- System-level docs (up one): [`../../docs/architecture.md`](../../docs/architecture.md) (whole-system architecture),
  [`../../docs/flows.md`](../../docs/flows.md) (cross-module flows). Deeper dives:
  [`../../docs/ROAD_TO_SALE_TECHNICAL_ARCHITECTURE.md`](../../docs/ROAD_TO_SALE_TECHNICAL_ARCHITECTURE.md),
  [`../../docs/ROAD_TO_SALE_AUDIO_ARCHITECTURE.md`](../../docs/ROAD_TO_SALE_AUDIO_ARCHITECTURE.md),
  [`../../docs/ROAD_TO_SALE_PRD.md`](../../docs/ROAD_TO_SALE_PRD.md).
- Voice contract this app mirrors (across):
  [`../../voice-engine/docs/model-contracts.md`](../../voice-engine/docs/model-contracts.md).
</content>
</invoke>
