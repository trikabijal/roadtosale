# System API & Integration Boundaries — Road to Sale (monorepo)

This is the system-level index of which module exposes what, and to whom. It links to the
per-module API docs rather than copying them. For exact method signatures, request/response
shapes, or error classes, follow the link to the module doc — that is the source of truth.

See also [`architecture.md`](architecture.md) for the module map and
[`flows.md`](flows.md) for end-to-end traces.

---

## 1. Boundary summary

| Boundary | Provider | Consumer(s) | Shape | Authoritative doc |
|---|---|---|---|---|
| Voice model contract (STT + cleanup) | `voice-engine` (spec/data) | Native code in `road-to-sale-app` + `dictation` | Contract + data pack, **re-implemented natively** (not linked) | [`voice-engine/docs/model-contracts.md`](../voice-engine/docs/model-contracts.md) |
| Vehicle catalog | `vehicle-feature-catalog` | `road-to-sale-app` (bundled), lab | In-process facade (Python + TS) + data | [`vehicle-feature-catalog/docs/api.md`](../vehicle-feature-catalog/docs/api.md) |
| SmartComply REST API | **External SmartComply backend** | `road-to-sale-app` (`SmartComplyClient`) | HTTP/JSON over JWT, `:8089` | [`road-to-sale-app/docs/smartcomply-contract.md`](../road-to-sale-app/docs/smartcomply-contract.md) |
| Lab facade | `voice-engine/lab` | Developers (offline benchmarking) | Python facade + CLI (`voice-lab`) | [`voice-engine/docs/api.md`](../voice-engine/docs/api.md) |
| App voice facade | `road-to-sale-app/src/voice` | App session engine | TS interface `IVoiceEngine` over a native module bridge | (in-app; see app source) |

---

## 2. The voice model contract (voice-engine)

`voice-engine` exposes a contract and data, not a runtime API the apps call. There are two
swappable model layers, each behind a stable `{ provider, model }` contract:

1. **STT — `TranscriptionStrategy`** (speech-to-text). Streaming for the live lane
   (`start(context) → Session` emitting `TranscriptEvent`s); a batch variant for dictation.
   Providers: `whisperkit`, `apple_speech_transcriber`, `argmax`, `sherpa_onnx`, `mock`.
2. **Cleanup — `CleanupStrategy`** (the cleanup LLM, a large language model that tidies the
   raw transcript). `clean(CleanupRequest) → CleanupResult`, with levels `off | light | full`.
   Providers: `foundation-models` (Apple), `gemini-nano` / `mediapipe` (Android), and the
   always-available deterministic `rule-based` fallback that every platform must match when no
   LLM is available.

The data pack (prompts, filler list, command grammar, vocab, junk phrases) and a shared
**telemetry schema** travel with the contract:
`raw_text · cleaned_text · level · provider · model · was_corrected · latency_ms ·
confidence · frontmost_app · failure_tags`.

Authoritative: [`voice-engine/docs/model-contracts.md`](../voice-engine/docs/model-contracts.md).
For the lab facade (`VoiceEngineLab`) and the TS skeleton facade (`VoiceEngine`), see
[`voice-engine/docs/api.md`](../voice-engine/docs/api.md).

> Reminder: consumers do **not** import the TS package. The native iOS/Android modules in
> `road-to-sale-app` and the Swift `DictationCore` package re-implement these contracts.

---

## 3. The vehicle catalog API (vehicle-feature-catalog)

Twin Python and TypeScript facades with matching field shapes, both fronted by
`VehicleFeatureCatalog`:

```python
from vehicle_feature_catalog import VehicleFeatureCatalog
catalog = VehicleFeatureCatalog.load("vehicle-feature-catalog/data")
features = catalog.list_features_for_trim("honda.crv-hybrid-awd.2026.sport-touring")
```

```ts
import { VehicleFeatureCatalog } from 'vehicle-feature-catalog';
const catalog = await VehicleFeatureCatalog.load('vehicle-feature-catalog/data');
```

`road-to-sale-app` consumes this as **bundled data** (`road-to-sale-app/src/catalog/`,
built from the package), not as a live service. Full facade contract, data models, and error
classes: [`vehicle-feature-catalog/docs/api.md`](../vehicle-feature-catalog/docs/api.md).

---

## 4. The SmartComply HTTP API (consumed, not provided here)

The SmartComply (AuditPro) backend is an external dependency in a separate repository. This
repo owns only the client side — `road-to-sale-app/src/api/SmartComplyClient.ts` (interface
`ISmartComplyClient`). Road to Sale runs as a SmartComply tenant under a standing Honda
walk-in campaign.

| Concern | Summary |
|---|---|
| Base URL | `http://localhost:8089` (dev); client appends `/api`. Env: `SMARTCOMPLY_API_URL` / `EXPO_PUBLIC_SMARTCOMPLY_API_URL` |
| Auth | JWT bearer; login `POST /api/user/login`, refresh `POST /api/user/refreshToken`; silent refresh with in-flight dedup; tokens in `expo-secure-store` |
| Assignments | `GET /api/audit/myAssignments`, `POST /api/audit/addAuditAssignments` (provision a walk-in inspection) |
| Template | `POST /api/checksheet/getChecksheetDetail` (NADA checksheet `id=2001`, `RTS_HONDA_V1`) |
| Session lifecycle | `POST /api/userChecksheet/createOrUpdate` (start / resume / submit) |
| Cue events | `POST /api/userChecksheet/createOrUpdateUserChksAns` (one row per detected cue, with `rts*` extension fields) |
| Trade photos | `POST /api/rts/tradePhoto/upload` (multipart), `GET /api/rts/tradePhoto` |
| Errors | `AuthError` (401 after refresh), `SmartComplyApiError(status, msg)`; offline write queue with exponential backoff and a 5-failure circuit breaker |

The full endpoint reference (request/response DTOs, the Road to Sale ↔ SmartComply entity
mapping, error handling, and the offline retry policy) lives in
[`road-to-sale-app/docs/smartcomply-contract.md`](../road-to-sale-app/docs/smartcomply-contract.md).

> Because the backend is now a separate repo, treat that contract doc as a **boundary
> agreement**: changes to it must be coordinated with the SmartComply team. You cannot change
> both sides in one commit — they are different repos.

---

## 5. Internal app facades (for orientation)

These are internal to `road-to-sale-app` and not cross-module boundaries, but newcomers ask
about them:

- `IVoiceEngine` (`src/voice/IVoiceEngine.ts`) — lifecycle + `onTranscript` /
  `onStateChange` / `onError` event callbacks; backed by `NativeVoiceModule` →
  Swift/Kotlin native modules.
- `ISessionRepository` (`src/db/`) — SQLite persistence + the offline write queue
  (`RetryQueueConsumer`).
- `SessionEngine` / `ChecklistEngine` (`src/session/`) — session lifecycle and NADA
  checklist state.

See the app source under [`road-to-sale-app/src/`](../road-to-sale-app/src/) for detail.
