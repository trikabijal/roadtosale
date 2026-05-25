# PRD 0003 — Dictation App (Wispr Flow Replacement)

**Status:** Draft  
**Author:** Bijal Sanghavi  
**Date:** 2026-05-25  
**Module:** `dictation/`

---

## 1. Purpose

A personal voice-to-text dictation tool for daily use on macOS and iOS — replacing Wispr Flow. The primary engineering motivation is not replacing a paid subscription: it is to **dogfood the WhisperKit STT engine under real-world daily use** and accumulate labelled failure data before Road to Sale customers encounter the same failure modes in dealership environments.

Every transcript the app produces becomes a data point. Every correction the user makes becomes a labelled negative. Over weeks of daily use, this builds the calibration foundation for Road to Sale's cue detection confidence thresholds.

---

## 2. Goals

| Goal | Description |
|------|-------------|
| **G1 — Daily driver** | Replace Wispr Flow as the default dictation tool across macOS and iOS |
| **G2 — WhisperKit stress test** | Run WhisperKit on real-world utterances: varied length, ambient noise, trailing-off, mixed vocabulary |
| **G3 — Failure capture** | Every transcript logs latency, confidence, and a one-key correction signal — zero friction |
| **G4 — Simplicity** | No complex text-injection compatibility matrix. Clipboard on Mac; keyboard proxy on iOS. Ship fast. |

---

## 3. Non-goals

- **Not** a full Wispr Flow feature clone (no commands, no custom vocabulary UI, no team sharing)
- **Not** cloud STT — WhisperKit on-device only for both platforms (this is the point)
- **Not** complex app-specific text injection on Mac (Accessibility API workarounds are out of scope)
- **Not** Android

---

## 4. Platforms

### 4.1 macOS — Menu Bar App

**Interaction model:**  
Hold a function key (default: `F5`, user-configurable) → mic records → release → WhisperKit transcribes → result written to clipboard → app attempts `⌘V` paste into the frontmost window.

The paste attempt is best-effort (works reliably in Terminal, Claude Code, most native apps). If the user is in a context where auto-paste is unwanted, they simply `⌘V` themselves. This eliminates the entire Accessibility API compatibility surface.

**Primary use case:** dictating into Claude Code / Terminal. Clipboard-first is correct for this.

**Menu bar presence:**  
- Icon shows mic state (idle / recording / transcribing)
- Click → popover with last 5 transcripts + failure flags
- Settings: hotkey picker, WhisperKit model tier (tiny / small / base), toggle auto-paste on/off

### 4.2 iOS — Custom Keyboard Extension

**Interaction model:**  
The app ships a custom keyboard extension. In any app, the user switches to the Dictation keyboard. A large mic button dominates the layout. Tap → record → WhisperKit transcribes → result inserted via `textDocumentProxy.insertText()`. No app needs to know the keyboard exists.

**Why keyboard extension over share extension / app clip:**  
- Works in every app, every text field, including third-party apps
- `textDocumentProxy` handles text insertion natively — no Accessibility permissions needed
- This is exactly how Wispr Flow's iOS product works
- Allows "Full Access" to be requested for network calls (telemetry logging)

**Layout:**  
- Top row: backspace, space, return (standard keyboard utility)  
- Centre: large mic button (hold to record OR tap to toggle)  
- Bottom: transcript preview strip (last result, truncated to 2 lines)  
- Settings gear → opens containing app

---

## 5. STT Engine

Both platforms use **WhisperKit** (Argmax open-source, on-device, free).

| Platform | Model tier default | Rationale |
|----------|--------------------|-----------|
| macOS | `whisper-large-v3-turbo` | MacBook has CPU/GPU headroom; accuracy over speed |
| iOS keyboard | `whisper-base.en` | Keyboard extension memory limit (~50 MB active); upgrade path to small.en |

WhisperKit replaces `SFSpeechRecognizer` entirely. This is intentional — the whole point is to exercise WhisperKit. `SFSpeechRecognizer` is not used anywhere in this app.

**VAD (Voice Activity Detection):**  
- Mac: energy-threshold VAD on the `AVAudioEngine` tap — stop transcription 800ms after audio drops below threshold (so user can trail off naturally without explicitly releasing the key)
- iOS: same energy-threshold VAD; keyboard has a visual waveform indicator

---

## 6. Telemetry (local only, no network)

Every transcript is written to a local SQLite database in the app's container:

```
transcripts
  id              TEXT PRIMARY KEY
  platform        TEXT  -- 'mac' | 'ios'
  recorded_at     INTEGER  -- Unix ms
  audio_duration_ms  INTEGER
  transcript_text TEXT
  word_count      INTEGER
  whisperkit_confidence  REAL  -- average token probability
  latency_ms      INTEGER  -- audio-end → text-available
  model_tier      TEXT  -- 'base.en' | 'small.en' | 'large-v3-turbo'
  frontmost_app   TEXT  -- macOS only: bundle ID of frontmost app
  was_corrected   INTEGER  -- 0 | 1, set by correction hotkey
  correction_note TEXT  -- optional, set via quick correction UI
```

**Correction signal (Mac):** `⌘⇧Z` within 5 seconds of a transcript landing → marks `was_corrected = 1`. Optional: type a short correction note in the popover.

**Correction signal (iOS):** Tap the transcript preview strip to toggle a ✗ flag.

**Weekly digest:** The containing macOS app has a "Stats" tab showing:
- Transcripts this week, avg latency, avg confidence
- Correction rate (%) — broken down by model tier and frontmost app
- Longest and shortest successful transcripts

This data feeds directly into Road to Sale calibration work.

---

## 7. Module layout

```
dictation/
├── DictationApp/                    macOS Swift app target
│   ├── DictationApp.swift           @main, MenuBarExtra
│   ├── HotkeyManager.swift          CGEventTap, function key intercept
│   ├── RecordingEngine.swift        AVAudioEngine mic capture + VAD
│   ├── TranscriptionEngine.swift    WhisperKit wrapper, model loading
│   ├── ClipboardPaster.swift        NSPasteboard write + CGEvent ⌘V
│   ├── TelemetryStore.swift         SQLite via GRDB or raw SQLite3
│   ├── SettingsView.swift           SwiftUI popover: hotkey, model tier, stats
│   └── Assets.xcassets
│
├── DictationKeyboard/               iOS keyboard extension target
│   ├── KeyboardViewController.swift UIInputViewController subclass
│   ├── KeyboardView.swift           SwiftUI keyboard layout
│   ├── RecordingEngine.swift        Shared (symlinked or package-extracted)
│   ├── TranscriptionEngine.swift    Shared
│   └── TelemetryStore.swift         Shared (writes to shared App Group container)
│
├── DictationContainerApp/           iOS containing app (required by App Store / sideload)
│   ├── ContentView.swift            Stats view + keyboard enable instructions
│   └── DictationContainerApp.swift
│
├── Shared/                          Swift package extracted from above
│   ├── Sources/DictationCore/
│   │   ├── RecordingEngine.swift
│   │   ├── TranscriptionEngine.swift
│   │   └── TelemetryStore.swift
│   └── Package.swift
│
├── docs/
│   ├── architecture.md
│   └── whisperkit-failure-findings.md  (populated over time from telemetry)
│
└── dictation.xcodeproj              Single Xcode project, 3 targets
```

---

## 8. Permissions required

### macOS
| Permission | Why |
|------------|-----|
| Microphone | Record audio |
| Accessibility (optional) | Auto-paste via `CGEvent ⌘V` — works without it in most cases via `NSPasteboard` + AppleScript fallback; request only if paste fails |

### iOS keyboard extension
| Permission | Why |
|------------|-----|
| Microphone | Record audio in keyboard extension |
| Full Access | Required to access shared SQLite telemetry store in App Group container |

---

## 9. Milestones

### M1 — Mac MVP (target: 1 week)
- [ ] macOS app builds and runs as menu bar app
- [ ] F5 hold-to-record → WhisperKit transcription → clipboard → auto-paste attempt
- [ ] Transcript appears in popover (last 5, no persistence yet)
- [ ] Works in Terminal / Claude Code

### M2 — Mac + telemetry (target: +3 days)
- [ ] SQLite telemetry store wired in (every transcript logged)
- [ ] `⌘⇧Z` correction signal
- [ ] Weekly stats view in popover
- [ ] Settings: hotkey picker, model tier selector, auto-paste toggle

### M3 — iOS keyboard (target: +1 week)
- [ ] Keyboard extension target compiles
- [ ] Mic button → record → WhisperKit → `insertText()`
- [ ] Transcript preview strip
- [ ] Telemetry writes to shared App Group SQLite
- [ ] Containing app shows stats + enable instructions

### M4 — Polish + findings doc (target: ongoing)
- [ ] `dictation/docs/whisperkit-failure-findings.md` populated from real use
- [ ] Failure patterns documented with audio-length / confidence correlations

---

## 10. Open questions

| # | Question | Default |
|---|----------|---------|
| OQ1 | Hold-to-record vs toggle-to-record on Mac? | **Hold** ✓ locked |
| OQ2 | Auto-paste off by default? | **Auto-paste ON** ✓ locked |
| OQ3 | Which function key as default hotkey? | **F5** ✓ locked |
| OQ4 | iOS: hold-to-record or tap-to-toggle? | **Tap-to-toggle** ✓ locked |
| OQ5 | Shared core as Swift Package or copy-paste? | **Swift Package (`DictationCore`)** ✓ locked |
| OQ6 | SQLite library on Apple platforms? | **GRDB** ✓ locked |
