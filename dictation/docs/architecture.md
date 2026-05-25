# Dictation — Architecture

## Overview

Dictation is a three-target Apple-platforms app that replaces Wispr Flow with a fully on-device, WhisperKit-powered speech-to-text pipeline. All product logic lives in **DictationCore**, a local Swift Package consumed by every target. No target-specific code leaks into the shared package.

---

## Targets

| Target | Platform | Type | Bundle ID |
|--------|----------|------|-----------|
| DictationApp | macOS 14+ | Application (menu bar, LSUIElement) | com.trika.dictation |
| DictationKeyboard | iOS 17+ | App Extension (custom keyboard) | com.trika.dictation.keyboard |
| DictationContainerApp | iOS 17+ | Application (host for keyboard ext) | com.trika.dictation |

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

#### TranscriptionEngine (`TranscriptionEngine.swift`)

A `@MainActor ObservableObject` wrapping WhisperKit. Responsibilities:

- Loads and hot-swaps WhisperKit models by `ModelTier` (tiny → large-v3-turbo).
- Accumulates `[AVAudioPCMBuffer]` from the recording session, flattens them to `[Float]`, and calls `WhisperKit.transcribe(audioArray:)`.
- Derives a **confidence score** (0.0–1.0) from `avgLogprob` across all returned segments: `confidence = exp(mean(avgLogprob))`.
- Returns a `TranscriptionResult` with text, confidence, audio duration, transcription latency, and the model tier used.

Published properties (`isLoaded`, `isTranscribing`) drive UI state in the target apps without coupling them to WhisperKit types.

#### TelemetryStore (`TelemetryStore.swift`)

A Swift `actor` backed by a GRDB `DatabaseQueue`. Responsibilities:

- Schema migration (GRDB `DatabaseMigrator`, `v1_create_transcripts`).
- Writes one `TranscriptRecord` per completed transcription.
- Exposes `markCorrected(id:note:)` — called when the user edits the pasted text, signalling a WhisperKit failure.
- Reads: `fetchRecent(limit:)` for the history UI; `fetchWeeklyStats()` for the dashboard.
- Provides platform-appropriate DB URLs: `~/Library/Application Support/com.trika.dictation/telemetry.sqlite` on macOS; App Group shared container on iOS.

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
                                  TranscriptionEngine
                                    └── WhisperKit.transcribe(audioArray:)
                                               │
                                               ▼
                                      TranscriptionResult
                                        ├── text ──► clipboard (macOS) / textDocumentProxy (iOS)
                                        └── record ──► TelemetryStore.save()
                                                            │
                                                            ▼
                                                     GRDB SQLite
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
- macOS-only code (Accessibility API, `NSWorkspace` for frontmost app) lives exclusively in `DictationApp/`.
- iOS-only code (`UIInputViewController`, `textDocumentProxy`) lives exclusively in `DictationKeyboard/`.
- The App Group shared container is the only cross-process communication channel (telemetry DB). No XPC, no shared memory.
