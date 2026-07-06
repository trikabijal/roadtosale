# Just Talk — QC master test-plan index

The QA control center for the **Just Talk** dictation deployable (global CLAUDE.md §13; lifecycle in
`~/.claude/workflows/test-plan-lifecycle.md`). This is the authoritative plan. Built from
`~/.claude/workflows/create-test-plan.md`: two layers — **E2E** (facade-driven, black-box, tiered)
and **Unit** (module-driven, run every PR).

> `docs/e2e-tests.md` is the older single-file plan and is now **superseded by this `qc/` tree** —
> kept only as the cross-platform (iOS/Android) reference. The per-feature plans, the checklist, and
> the coverage manifest below are the source of truth.

## The two layers

| Layer | Proves | Boundary | Tiered? | Runs |
|---|---|---|---|---|
| **E2E** | the system works for the user | public facades only (contract methods, adapter APIs, user flows) | Tier 1/2/3 | per PR (1/2) + scheduled (3) |
| **Unit** | each module's logic is correct | one module + direct deps | no | every PR |

## Feature test plans (`features/`)

| # | Feature | Facades / modules | Plan |
|---|---|---|---|
| f1 | Dictation pipeline (record→transcribe→clean→insert) | `AppState` orchestrator, `StreamingDictationSession` | [f1-dictation-pipeline.md](features/f1-dictation-pipeline.md) |
| f2 | Live pill (streaming) | `StreamingTranscriber`, `StreamingAgreement`, WhisperKit/Apple sessions | [f2-streaming-pill.md](features/f2-streaming-pill.md) |
| f3 | STT providers & selection | `SpeechTranscriber`, `STTProvider`/`STTConfig`, `SpeechTranscriberFactory`, WhisperKit/Apple | [f3-stt-providers.md](features/f3-stt-providers.md) |
| f4 | Cleanup | `TextCleanup`, `RuleBasedCleanup`, `FoundationModelsCleanup`, `CleanupOutputSanitizer`, packs | [f4-cleanup.md](features/f4-cleanup.md) |
| f5 | Capture & persistence | `RecordingEngine`, `CapturedAudioStream`, `RecordingStore`, `AudioSampleBridge` | [f5-capture-persistence.md](features/f5-capture-persistence.md) |
| f6 | Telemetry | `TelemetryStore`, `TranscriptRecord`, stats | [f6-telemetry.md](features/f6-telemetry.md) |
| f7 | System requirements gate | `SystemPreflight`/`SystemCapabilities`, `RequirementsView` | [f7-system-requirements.md](features/f7-system-requirements.md) |
| f8 | Activation, hotkey & permissions | `HotkeyManager`/`HotkeyConfig`/`HotkeyConflict`, `PermissionsService`, onboarding | [f8-activation-hotkey-permissions.md](features/f8-activation-hotkey-permissions.md) |
| f9 | Insertion & HUD | `ClipboardPaster`, `RecordingHUD` | [f9-insertion-hud.md](features/f9-insertion-hud.md) |

## Preventive checklist

New/revised feature plans are graded against **[test-plan-checklist.md](test-plan-checklist.md)** —
the standing list of recurring defect classes distilled from this project's own RCAs (the F1–F13
input/paste audit, the vocab-echo, the cleanup content-drop). A class with a surface here but no
scenario is a gap to add.

## Executable tests

- **Unit / contract (fast, model-free):** `../Shared/Tests/DictationCoreTests/` — Swift + XCTest.
  Run: `cd dictation && ./test.sh core`.
- **macOS adapter:** `../JustTalkTests/` — Run: `./test.sh app` (needs **no running Just Talk
  instance** — the single-instance lock blocks the test host).
- **Machine manifest:** [coverage.yml](coverage.yml) maps every plan id → its test method.

## Test tiers

| Tier | Proves | Env | Speed | When |
|---|---|---|---|---|
| **1** | our code correct; core flow works; bad input rejected | no model, no mic, no network; in-memory / tmp I/O | < 5 s | every PR |
| **2** | pipeline integrates; artifacts written; fallbacks fire | mocked model / clipboard; real internal orchestration | < 30 s | every PR |
| **3** | real world: real STT/LLM models, real mic/hardware | on-device models, live mic, macOS 26 | 1–5 min | manual / device lane |

Model-backed tests **skip** (not fail) when weights / Apple Intelligence are absent, so CI stays green.

## Role-based review of this plan (the workflow's four lenses)

- **Tester** — every facade element + behavior is a ledger row; inputs cover valid/invalid/edge/
  adversarial; outcomes are observable without internals; unit tests are independent + parallel-safe.
- **Developer** — E2E asserts contracts not internals; the mock boundary is the model / mic /
  clipboard; data is per-test (tmp dirs, in-memory DB); a refactor behind a facade breaks nothing.
- **Product Manager** — Tier 1 proves the promised flow (speak → cleaned text appears); errors are
  graceful (retry, requirements screen), never a crash or lost audio.
- **Engineering Manager** — Tier 1 is model-free + seconds; markers = `core`/`app` + tier; deferrals
  tracked per feature; no sleeps / shared state in unit tests.

## Summary of current coverage (2026-07-06)

- **✅ COVERED well (85 tests):** cleanup (rule-based + FM + packs + sanitizer + content-drop guard +
  factory), hallucination filter, capture-buffer ordering, recording store + bridge, telemetry
  (retention + stats + search + markCorrected + usage), streaming `StreamingAgreement`, streaming
  session assembly, timeout primitive, hotkey config/conflict.
- **⛔ AUTOMATABLE GAPS (this pass adds):** `SystemPreflight` decision logic (f7), STT
  provider/config/factory gating (f3), `AppleAudioConverter` (f3, macOS-26-gated).
- **⛔ MANUAL / device (deferred):** real STT transcription, `RecordingEngine` live capture,
  `ClipboardPaster` paste, live `HotkeyManager` tap, `AppState` orchestrator, HUD — need
  harnesses/hardware; tracked in each feature's Deferred table.
