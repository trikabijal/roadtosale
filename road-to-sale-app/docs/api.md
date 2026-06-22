# Road to Sale App — APIs Consumed

This app talks to **two** API surfaces:

1. **SmartComply HTTP API** — the compliance backend (auth, checksheets,
   sessions, cue answers, trade-in photos). External service over HTTP.
2. **Native voice module JS API** — the on-device STT bridge
   (`NativeVoiceModule` ↔ Swift / Kotlin).

> **The SmartComply backend now lives in a separate repository.** Its source is
> no longer under this monorepo. The app consumes it purely over HTTP (default
> `http://localhost:8089` in dev). Do not look for a `smartcomply/` path here.

---

## 1. SmartComply HTTP API

The canonical, exhaustive request/response contract is
[`smartcomply-contract.md`](./smartcomply-contract.md). This section is the
quick map; read the contract doc for every field, the response envelope, the
domain-entity mapping, and the error/retry tables.

### Client & facade

- Implementation: [`src/api/SmartComplyClient.ts`](../src/api/SmartComplyClient.ts)
- Interface (facade): [`src/api/ISmartComplyClient.ts`](../src/api/ISmartComplyClient.ts)
- Singleton accessor: `getSmartComplyClient()` in
  [`src/api/clientSingleton.ts`](../src/api/clientSingleton.ts)
- DTOs: [`src/api/types.ts`](../src/api/types.ts)

Base URL resolution order: `Constants.expoConfig.extra.smartComplyApiUrl` →
`process.env.SMARTCOMPLY_API_URL` → `http://localhost:8089`. The client appends
`/api` to every path itself.

### Auth model

- **JWT Bearer.** Access + refresh tokens and the user id are stored in
  `expo-secure-store` (`rts_access_token`, `rts_refresh_token`, `rts_user_id`).
- `login()` posts `{ username, password, deviceType: 'APP' }`, stores tokens.
- **Silent refresh:** a `401` on any authenticated call triggers one
  `refreshToken` call and a single retry; concurrent 401s are deduped through an
  in-flight queue. Refresh failure clears tokens and throws `AuthError` (app
  routes back to login).
- `logout()` is client-side only — it deletes the three secure-store keys (no
  server revocation call).

### Endpoints (via the facade)

| Facade method | HTTP | Path | Used by |
|---|---|---|---|
| `login(req)` | POST | `/api/user/login` | AuthScreen |
| `refreshToken(t)` | POST | `/api/user/refreshToken` | (internal, on 401) |
| `logout()` | — | (local only) | HomeScreen |
| `getMyAssignments()` | GET | `/api/audit/myAssignments` | HomeScreen (via `AuditProCrmProvider`) |
| `createWalkInAssignment(req)` | POST | `/api/audit/addAuditAssignments` | walk-in provisioning |
| `getChecksheetDetail(id)` | POST | `/api/checksheet/getChecksheetDetail` | SessionSetup (NADA template) |
| `startOrResumeSession(dto)` | POST | `/api/userChecksheet/createOrUpdate` | SessionEngine.startSession |
| `submitSession(ucId)` | POST | `/api/userChecksheet/createOrUpdate` (`status: SUBMITTED`) | SessionEngine.endSession |
| `submitAnswer(a)` / `submitAnswers(a[])` | POST | `/api/userChecksheet/createOrUpdateUserChksAns` | cue detection events |
| `uploadTradePhoto(ucId, slot, uri, mime)` | POST (multipart) | `/api/rts/tradePhoto/upload` | TradeInScreen |
| `getTradePhotos(ucId)` | GET | `/api/rts/tradePhoto?userChecksheetId=…` | trade-in read-back |

Notes:
- Every JSON response is unwrapped from `ApiResponse<T> = { status, message,
  data }`; the facade returns `data`.
- `startOrResumeSession` / `submitSession` send/receive single-element arrays;
  the client returns index `0`.
- `submitAnswers([])` short-circuits before hitting the network.
- `uploadTradePhoto` bypasses the JSON helper and builds `FormData` manually,
  attaching the bearer token directly.

### Errors

- `AuthError` — 401 after refresh, or refresh failure.
- `SmartComplyApiError(statusCode, message)` — other non-2xx (403 = operator
  lacks route permission; 4xx/5xx surface the response text).
- Offline writes go through the SQLite retry queue with a 5-attempt circuit
  breaker (see [`flows.md`](./flows.md) §2 and the contract doc §6).

---

## 2. Native voice module JS API

The on-device speech bridge. JS calls Swift / Kotlin through one facade; the
native side streams transcripts back as events. No audio ever leaves the device.

### Facade

- Interface: [`src/voice/IVoiceEngine.ts`](../src/voice/IVoiceEngine.ts)
- Implementation + singleton: [`src/voice/NativeVoiceModule.ts`](../src/voice/NativeVoiceModule.ts)
  (`getVoiceEngine()`)
- Event payload types: [`src/voice/types.ts`](../src/voice/types.ts)
  (mirror of `voice-engine/src/types`)

### Methods

| Method | Signature | Behaviour |
|---|---|---|
| `requestPermissions()` | `() => Promise<PermissionStatus>` | iOS: speech + mic auth; Android: `RECORD_AUDIO` check. Returns `'granted' \| 'denied' \| 'restricted' \| 'undetermined'`. |
| `start(options)` | `(VoiceEngineOptions) => Promise<void>` | Starts capture. `options = { language: 'en-US', customVocabulary?: string[] }`. iOS passes `customVocabulary` as `contextualStrings`. Real `listening` state arrives via the native event. |
| `stop()` | `() => Promise<void>` | Stops capture / cancels the recognition task; Android also stops the foreground service. |
| `mute()` | `() => void` | Drops audio frames without tearing down the session → state `muted`. |
| `unmute()` | `() => void` | Resumes → state `listening`. |
| `state` | `VoiceEngineState` (getter) | Current state: `idle \| starting \| listening \| muted \| stopping \| stopped \| error`. |

### Events (subscriptions)

Each returns an **unsubscribe** function. Always unsubscribe on unmount.

| Subscription | Native event | Payload |
|---|---|---|
| `onTranscript(cb)` | `onTranscriptEvent` | `TranscriptEvent` `{ text, stability: 'partial'\|'final', timestamp_ms, latency_ms_from_audio_start, confidence, engine_metadata }` |
| `onStateChange(cb)` | `onVoiceStateChange` | `VoiceEngineState` string |
| `onError(cb)` | `onVoiceError` | `Error` (wrapped from a native message string) |

### Native implementations

| Platform | Engine | File | `engine_metadata.engine` |
|---|---|---|---|
| iOS | `SFSpeechRecognizer` (on-device, partial results) | [`ios/VoiceModule/RtsVoiceModule.swift`](../ios/VoiceModule/RtsVoiceModule.swift) | `apple_speech_transcriber` |
| Android | sherpa-onnx: Silero VAD + offline Whisper `small.en` + mic foreground service | [`android/.../voice/RtsVoiceModule.kt`](../android/app/src/main/kotlin/com/trika/roadtosale/voice/RtsVoiceModule.kt) | `sherpa_onnx_vad` |

> The `TranscriptEvent` shape is the same contract `voice-engine` defines — see
> [`../../voice-engine/docs/model-contracts.md`](../../voice-engine/docs/model-contracts.md).
> `src/voice/types.ts` must stay in sync with `voice-engine/src/types/index.ts`.

### Mock fallback

When `NativeModules.RtsVoiceModule` is absent (Expo Go / web / un-prebuilt
simulator), `NativeVoiceModule` runs in mock mode: `start()` flips to `listening`
and emits nothing, `requestPermissions()` returns `'granted'`. The app stays
fully runnable; just no real transcripts.

---

## Cross-references

- App architecture: [`architecture.md`](./architecture.md)
- Flows: [`flows.md`](./flows.md)
- Full backend contract: [`smartcomply-contract.md`](./smartcomply-contract.md)
- System docs (up): [`../../docs/architecture.md`](../../docs/architecture.md), [`../../docs/api.md`](../../docs/api.md) (system boundary index). Deeper dive: [`../../docs/ROAD_TO_SALE_TECHNICAL_ARCHITECTURE.md`](../../docs/ROAD_TO_SALE_TECHNICAL_ARCHITECTURE.md)
- Voice contract (across): [`../../voice-engine/docs/model-contracts.md`](../../voice-engine/docs/model-contracts.md)
</content>
