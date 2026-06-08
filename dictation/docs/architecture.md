# Just Talk — Architecture

## Overview

Just Talk is a multi-target Apple-platforms app that replaces Wispr Flow with a fully on-device, WhisperKit-powered speech-to-text pipeline. All product logic lives in **DictationCore**, a local Swift Package consumed by every target. No target-specific code leaks into the shared package. (The shared package and the iOS targets keep the `Dictation*` names for now; only the macOS product was renamed to Just Talk.)

---

## Targets

| Target | Platform | Type | Bundle ID |
|--------|----------|------|-----------|
| JustTalk | macOS 14+ | Application (menu bar, LSUIElement) | com.trika.justtalk.mac |
| JustTalkTests | macOS 14+ | Unit-test bundle (hosted by JustTalk) | com.trika.justtalk.mac.tests |
| DictationKeyboard | iOS 17+ | App Extension (custom keyboard) | com.trika.dictation.ios.keyboard |
| DictationContainerApp | iOS 17+ | Application (host for keyboard ext) | com.trika.dictation.ios |

DictationKeyboard is embedded in DictationContainerApp. Both iOS targets share the App Group `group.com.trika.dictation` so TelemetryStore's SQLite database is accessible to both processes.

---

## DictationCore — Shared Swift Package

Located at `dictation/Shared/`. All three targets declare it as a local package dependency in `project.yml`. External dependencies (GRDB, WhisperKit) are declared once in `Package.swift` and resolved transitively.

### Components

#### RecordingEngine (`RecordingEngine.swift`)

Owns the AVAudioEngine session. Responsibilities:

- Requests microphone permission (platform-appropriate: `AVCaptureDevice` on macOS, `AVAudioApplication` on iOS 17+).
- Installs a tap at the hardware's native sample rate and format.
- Converts each buffer to **16 kHz mono Float32** via `AVAudioConverter` — the exact format WhisperKit expects.
- Runs an energy-threshold **Voice Activity Detector (VAD)**:
  - Computes per-buffer RMS.
  - Sets `hasSpeechStarted = true` when RMS ≥ `silenceThreshold` (default 0.01, ~-40 dBFS).
  - Starts a silence timer when RMS drops below threshold after speech has begun.
  - Fires `recordingEngineDidDetectSilence` after `silenceDurationMs` (default 800 ms) of continuous silence.
- Delivers every converted buffer to the delegate on the audio thread.

The VAD is intentionally simple — energy-only, no ML. This makes failure modes transparent and lets the telemetry data guide future improvements.

#### Two pluggable model contracts

Both model layers sit behind a contract, chosen at runtime by `{provider, model}` config.
This mirrors the strategy pattern in `voice-engine/` (see `voice-engine/docs/model-contracts.md`),
so iOS/Android implement the *same* contracts with their native engines.

**`SpeechTranscriber`** (`SpeechTranscriber.swift`) — the voice-understanding model.

- `load(onProgress:)`, `transcribe(buffers:audioStartDate:)`, optional `setVocabularyBias`.
- `STTProvider` (`whisperKit`, `appleSpeech`, `mock`) + `STTConfig {provider, model}` + `SpeechTranscriberFactory`.
- `WhisperKitTranscriber` is the WhisperKit implementation: loads/hot-swaps models by `ModelTier`
  (split into explicit `WhisperKit.download(progressCallback:)` → load for first-run progress),
  flattens buffers to `[Float]`, derives confidence from `avgLogprob`, filters silence-hallucinations,
  and applies custom-vocabulary biasing via `DecodingOptions.promptTokens`.
- `TranscriptionResult` is provider-agnostic (`provider` + `model`).

**`TextCleanup`** (`TextCleanup.swift`) — the cleanup LLM. `clean(_:)` never throws.

- `CleanupProvider` (`foundationModels`, `ruleBased`) + `CleanupConfig {provider, level}` + `TextCleanupFactory`.
- `FoundationModelsCleanup` (`FoundationModelsCleanup.swift`) uses Apple's on-device
  `SystemLanguageModel` / `LanguageModelSession`. Availability-gated, with a degenerate-output
  guard and a deterministic command/vocab post-pass; falls back to rule-based on any failure.
- `RuleBasedCleanup` (`RuleBasedCleanup.swift`) is the deterministic fallback + `ruleBased` provider,
  mirroring the `voice-engine` reference implementation.
- **Knowledge as data:** prompts/fillers/command-grammar/junk-list/thresholds load from
  `Resources/dictation-cleanup-pack.json` (canonical copy in `voice-engine/cleanup-packs/`).

#### TelemetryStore (`TelemetryStore.swift`)

A Swift `actor` backed by a GRDB `DatabaseQueue`. Responsibilities:

- Schema migration (GRDB `DatabaseMigrator`, `v1_create_transcripts`).
- Writes one `TranscriptRecord` per completed transcription.
- Exposes `markCorrected(id:note:)` — called when the user edits the pasted text, signalling a WhisperKit failure.
- Reads: `fetchRecent(limit:)` for the history UI; `fetchWeeklyStats()` for the dashboard.
- Provides platform-appropriate DB URLs: `~/Library/Application Support/com.trika.dictation/telemetry.sqlite` on macOS; App Group shared container on iOS.

---

## macOS Onboarding, Permissions & Hotkey (JustTalk target)

This UI/permission layer is macOS-only and lives entirely in `JustTalk/`.

- **PermissionsService** — the single source of truth for the two required permissions
  (Microphone, Accessibility). All status reads are **non-prompting** (`AVCaptureDevice.authorizationStatus`,
  `AXIsProcessTrusted()`), so the app can poll them freely. System prompts fire *only* from
  the explicit `requestMic()` / `promptAccessibility()` methods, which are wired to wizard
  buttons. This is the fix for prompts appearing "out of the blue": `AppState.setup()` no
  longer requests anything automatically at launch.
- **Onboarding wizard** (`OnboardingView` + `OnboardingWindow`) — a card-based setup flow
  with live status ticks, shown on first launch or whenever a required permission is missing,
  and reopenable from the menu bar → Setup. A 1.2s poll (`AppState.startPermissionPolling`)
  plus `didBecomeActive` make the ticks update as the user toggles settings; once granted, the
  hotkey event tap is installed automatically (`refreshPermissions` on the grant transition).
- **Configurable hotkey** (`HotkeyConfig` + `HotkeyManager`) — the activation key is chosen
  from a curated set (Fn, right ⌘/⌥/⌃, F5/F6/F13). `HotkeyManager` installs a `CGEventTap`
  and matches the active `HotkeyConfig`: Fn/right-modifiers via `.flagsChanged` (right keys
  disambiguated from their left twins by virtual keyCode), function keys via `.keyDown/.keyUp`.
  Matched keys are suppressed (return `nil`) so the Globe picker / stray key never reaches the
  foreground app.
- **Conflict detection** (`HotkeyConflict`) — macOS exposes no API to enumerate other apps'
  event taps, so conflict detection is best-effort: it reads the OS Globe setting
  (`AppleFnUsageType`) and scans `NSWorkspace.runningApplications` for known competitors
  (Wispr Flow). The **definitive** check is the wizard's "press your key to test" step: in
  `HotkeyManager.isTesting` mode a press routes to `hotkeyDidReceiveConfiguredKey()` only (no
  recording), proving the key reaches the app regardless of what might be intercepting it.

---

## Data Flow

```
Microphone
    │
    ▼
RecordingEngine
    ├── installTap (native hardware format)
    ├── AVAudioConverter → 16 kHz mono Float32
    ├── RMS VAD (800 ms silence window)
    │       │
    │       └── delegate: didDetectSilence ──────────────────┐
    │                                                         │
    └── delegate: didReceiveBuffer ──► buffer accumulator     │
                                           │                  │
                                           │ (silence fires)  │
                                           ▼◄─────────────────┘
                                  SpeechTranscriber (WhisperKit)
                                    └── transcribe(audioArray:) → raw text
                                               │
                                               ▼
                                  TextCleanup (Foundation Models)
                                    └── clean(rawText, level, vocab, grammar)
                                        └── fallback → RuleBasedCleanup
                                               │
                                               ▼
                                      cleaned text
                                        ├──► clipboard+paste (clipboard restored after)
                                        └──► TranscriptRecord {raw, cleaned, level, provider}
                                                            │
                                                            ▼
                                                     GRDB SQLite (v2)
                                                     (platform DB URL)
```

---

## Telemetry & Dogfooding Rationale

Every transcription is persisted — text, confidence, latency, audio duration, model tier, and (on macOS) the frontmost application. When the user corrects a transcription, `markCorrected` flags the row and captures a note.

This data directly feeds **Road to Sale STT calibration**:

- High correction rate on a model tier → switch to a more accurate tier for dealership cue detection.
- Systematic hallucinations in specific apps (e.g., noisy car environments) → surface in `whisperkit-failure-findings.md`.
- Latency regressions across WhisperKit versions → caught before they affect Road to Sale users.

Daily personal use at the developer's desk is the cheapest possible test harness for a production STT pipeline.

---

## Isolation Guarantees

- DictationCore never imports from any target-specific module.
- macOS-only code (Accessibility API, `NSWorkspace`, the floating `RecordingHUD`,
  `LoginItem`/`SMAppService` launch-at-login, the onboarding wizard, `PermissionsService`,
  `HotkeyManager`/`HotkeyConfig`/`HotkeyConflict`) lives exclusively in `JustTalk/`.
- iOS-only code (`UIInputViewController`, `textDocumentProxy`) lives exclusively in `DictationKeyboard/`.
- The App Group shared container is the only cross-process communication channel (telemetry DB). No XPC, no shared memory.
