# Task List — PRD 0003: Dictation App

**Source PRD:** `dev/tasks/0003-prd-dictation-app.md`  
**Branch:** create `feat/dictation-app` from `main`  
**Module root:** `dictation/`

Locked decisions: hold-to-record (Mac), auto-paste ON, F5 hotkey, tap-to-toggle (iOS), DictationCore Swift package, GRDB for SQLite.

---

## Phase 1 — Shared Core Package (DictationCore)

Do this first. Both the macOS app and iOS keyboard extension consume this package. Nothing in Phase 2 or 3 can start until Phase 1 is committed.

### Task 1 — Scaffold the Xcode project and Swift package

- [ ] 1.1 Create `dictation/` directory at repo root
- [ ] 1.2 Create `dictation/dictation.xcodeproj` with three targets:
  - `DictationApp` (macOS, App)
  - `DictationKeyboard` (iOS, Keyboard Extension)
  - `DictationContainerApp` (iOS, App)
- [ ] 1.3 Create `dictation/Shared/` as a local Swift Package (`Package.swift`):
  - Package name: `DictationCore`
  - Library product: `DictationCore`
  - Target: `Sources/DictationCore/`
  - Dependencies: GRDB (add via SPM: `https://github.com/groue/GRDB.swift`, up-to-next-major from 6.0.0)
  - WhisperKit (add via SPM: `https://github.com/argmaxinc/WhisperKit`, up-to-next-major from 0.9.0)
- [ ] 1.4 Add `DictationCore` as a local package dependency in `dictation.xcodeproj` for all three targets
- [ ] 1.5 Add `dictation/.gitignore` (ignore `.build/`, `*.xcuserstate`, `DerivedData/`)
- [ ] 1.6 Add `dictation/README.md` — what it is, how to build, open `dictation.xcodeproj` in Xcode, set signing team

### Task 2 — RecordingEngine (DictationCore)

`dictation/Shared/Sources/DictationCore/RecordingEngine.swift`

This class wraps `AVAudioEngine` and emits audio buffers suitable for WhisperKit. It also does simple energy-based VAD.

- [ ] 2.1 Define protocol `RecordingEngineDelegate`:
  ```swift
  public protocol RecordingEngineDelegate: AnyObject {
      func recordingEngine(_ engine: RecordingEngine, didReceiveBuffer buffer: AVAudioPCMBuffer, atTime time: AVAudioTime)
      func recordingEngineDidDetectSilence(_ engine: RecordingEngine)  // VAD: silence after speech
  }
  ```
- [ ] 2.2 Implement `RecordingEngine` class:
  - `AVAudioEngine` with input node tap at 16kHz mono (WhisperKit's required format)
  - `start()` and `stop()` methods — `stop()` flushes the final buffer
  - Energy-threshold VAD: track RMS of each buffer; if RMS drops below `silenceThreshold` (default 0.01) for longer than `silenceDurationMs` (default 800ms), call `delegate.recordingEngineDidDetectSilence()`
  - `silenceThreshold: Float` and `silenceDurationMs: Int` are public var properties (configurable per platform)
  - macOS and iOS share this class — no platform-specific code inside it
- [ ] 2.3 Request microphone permission before starting — if denied, throw `RecordingError.microphonePermissionDenied`
- [ ] 2.4 Handle audio session interruptions (phone call on iOS) — stop gracefully

### Task 3 — TranscriptionEngine (DictationCore)

`dictation/Shared/Sources/DictationCore/TranscriptionEngine.swift`

Wraps WhisperKit. Accepts audio from RecordingEngine and returns a `TranscriptionResult`.

- [ ] 3.1 Define `TranscriptionResult`:
  ```swift
  public struct TranscriptionResult {
      public let text: String
      public let confidence: Double   // average token probability 0.0–1.0
      public let durationMs: Int      // audio duration in ms
      public let latencyMs: Int       // time from audio-end to result
      public let modelTier: ModelTier
  }
  
  public enum ModelTier: String {
      case baseEn = "base.en"
      case smallEn = "small.en"
      case largeV3Turbo = "large-v3-turbo"
  }
  ```
- [ ] 3.2 Implement `TranscriptionEngine` class:
  - `init(modelTier: ModelTier)` — loads WhisperKit model on a background actor
  - `transcribe(buffers: [AVAudioPCMBuffer]) async throws -> TranscriptionResult`
  - Concatenate buffers into one audio array, pass to `WhisperKit.transcribe(audioArray:)`
  - Compute `confidence` as the average of all segment probabilities from the result
  - Compute `latencyMs` from when the last buffer arrived to when the result returns
  - `isLoaded: Bool` property — true after model finishes loading
  - `loadModel() async throws` — call this on app launch to pre-warm; don't block UI
- [ ] 3.3 Make `TranscriptionEngine` an `@MainActor`-safe observable (`ObservableObject` with `@Published var isLoaded`)

### Task 4 — TelemetryStore (DictationCore)

`dictation/Shared/Sources/DictationCore/TelemetryStore.swift`

SQLite store via GRDB. Writes to the app's container (macOS) or shared App Group container (iOS).

- [ ] 4.1 Define `TranscriptRecord` as a GRDB `Record`:
  ```swift
  public struct TranscriptRecord: Identifiable, Codable, FetchableRecord, PersistableRecord {
      public var id: String            // UUID
      public var platform: String      // "mac" | "ios"
      public var recordedAt: Date
      public var audioDurationMs: Int
      public var transcriptText: String
      public var wordCount: Int
      public var whisperkitConfidence: Double
      public var latencyMs: Int
      public var modelTier: String
      public var frontmostApp: String? // macOS only (bundle ID)
      public var wasCorrected: Bool
      public var correctionNote: String?
  }
  ```
- [ ] 4.2 Implement `TelemetryStore` class:
  - `init(databaseURL: URL)` — opens/creates GRDB database, runs migrations
  - GRDB migration: create `transcript_records` table from the struct above
  - `save(_ record: TranscriptRecord) async throws`
  - `markCorrected(id: String, note: String?) async throws`
  - `fetchRecent(limit: Int) async throws -> [TranscriptRecord]`
  - `fetchWeeklyStats() async throws -> WeeklyStats`
- [ ] 4.3 Define `WeeklyStats`:
  ```swift
  public struct WeeklyStats {
      public let totalCount: Int
      public let correctionRate: Double    // 0.0–1.0
      public let avgConfidence: Double
      public let avgLatencyMs: Double
      public let avgAudioDurationMs: Double
  }
  ```
- [ ] 4.4 macOS database URL: `~/Library/Application Support/com.trika.dictation/telemetry.sqlite`
- [ ] 4.5 iOS shared URL: App Group container `group.com.trika.dictation` → `telemetry.sqlite` (keyboard extension and containing app both read/write this file)

---

## Phase 2 — macOS App (M1 + M2)

### Task 5 — Menu bar scaffold + model loading

`dictation/DictationApp/`

- [ ] 5.1 `DictationApp.swift` — `@main` struct conforming to `App`:
  - `MenuBarExtra("Dictation", systemImage: "mic")` — icon changes to `mic.fill` while recording, `waveform` while transcribing
  - No dock icon (`LSUIElement = true` in Info.plist)
  - On launch: call `transcriptionEngine.loadModel()` on a `Task`
- [ ] 5.2 `AppState.swift` — `@MainActor ObservableObject`:
  - `enum DictationState { case idle, recording, transcribing }`
  - `@Published var state: DictationState = .idle`
  - `@Published var recentTranscripts: [TranscriptRecord] = []`
  - `@Published var engineLoaded: Bool = false`
  - Holds instances of `RecordingEngine`, `TranscriptionEngine`, `TelemetryStore`, `HotkeyManager`, `ClipboardPaster`
- [ ] 5.3 `MenuBarView.swift` — SwiftUI view for the popover:
  - State chip: "Ready" / "Recording…" / "Transcribing…" with appropriate color
  - If `engineLoaded == false`: "Loading model…" spinner
  - List of last 5 transcripts (text truncated to 2 lines, timestamp)
  - Each transcript row: tap to copy to clipboard; `⌘⇧Z` correction shortcut shown as hint
  - "Settings" button at bottom → sheet or separate window
- [ ] 5.4 Add `NSMicrophoneUsageDescription` and `NSAppleEventsUsageDescription` to Info.plist

### Task 6 — Hotkey (F5 hold-to-record)

`dictation/DictationApp/HotkeyManager.swift`

- [ ] 6.1 Use `CGEventTap` to intercept `keyDown` and `keyUp` for the configured function key
  - Default keycode for F5: `kVK_F5` = `0x60`
  - The event tap must be created with `CGEventTapCreate` at `kCGHIDEventTap` (before other apps see it)
  - Suppress the original F5 event so it doesn't trigger system brightness/media controls
- [ ] 6.2 On `keyDown` for the hotkey:
  - If `state == .idle`: transition to `.recording`, call `recordingEngine.start()`
  - If `state == .recording`: ignore (key repeat)
- [ ] 6.3 On `keyUp` for the hotkey:
  - If `state == .recording`: transition to `.transcribing`, call `recordingEngine.stop()`, begin transcription (Task 7)
- [ ] 6.4 Handle the Accessibility permission requirement for `CGEventTap`:
  - Call `AXIsProcessTrusted()` on launch
  - If not trusted: show a one-time `NSAlert` directing the user to System Settings → Privacy → Accessibility → enable Dictation
  - The event tap falls back gracefully to a polling approach using `NSEvent.addGlobalMonitorForEvents` if Accessibility is not granted (note: global monitor can't suppress the original event, but recording still works)
- [ ] 6.5 `var hotKeyCode: CGKeyCode` — persisted in `UserDefaults` so the user can change it in Settings

### Task 7 — Recording → Transcription → Clipboard → Paste pipeline

`dictation/DictationApp/AppState.swift` (extend)

This is the core loop that wires all the DictationCore pieces together on the Mac.

- [ ] 7.1 Conform `AppState` to `RecordingEngineDelegate`:
  - `didReceiveBuffer`: accumulate buffers into `var audioBuffers: [AVAudioPCMBuffer] = []`
  - `didDetectSilence`: if `state == .recording`, trigger stop + transcription automatically (VAD-triggered stop, same path as key-up)
- [ ] 7.2 `func beginTranscription() async`:
  - Set `state = .transcribing`
  - Call `transcriptionEngine.transcribe(buffers: audioBuffers)`
  - On success: call `ClipboardPaster.writeAndPaste(text:)` (Task 8), save to `TelemetryStore`, prepend to `recentTranscripts`, clear `audioBuffers`, set `state = .idle`
  - On error: show brief error in menu bar popover, set `state = .idle`
- [ ] 7.3 `func markLastTranscriptCorrected()`:
  - Called when user presses `⌘⇧Z` (or taps the ✗ in the popover)
  - Calls `telemetryStore.markCorrected(id: recentTranscripts[0].id, note: nil)`
  - Updates `recentTranscripts[0].wasCorrected = true`
- [ ] 7.4 Add `frontmostApp` capture: before calling `beginTranscription()`, read `NSWorkspace.shared.frontmostApplication?.bundleIdentifier` and store it — include in the `TranscriptRecord`

### Task 8 — Clipboard write + auto-paste

`dictation/DictationApp/ClipboardPaster.swift`

- [ ] 8.1 `func writeAndPaste(text: String, autoPaste: Bool)`:
  - Write `text` to `NSPasteboard.general` (clear first, then `setString:forType:.string`)
  - If `autoPaste == true`: send `⌘V` via `CGEvent`:
    ```swift
    let vDown = CGEvent(keyboardEventSource: nil, virtualKey: 0x09, keyDown: true)!
    vDown.flags = .maskCommand
    vDown.post(tap: .cghidEventTap)
    let vUp = CGEvent(keyboardEventSource: nil, virtualKey: 0x09, keyDown: false)!
    vUp.post(tap: .cghidEventTap)
    ```
  - Add a 50ms delay before sending the CGEvent (give the frontmost window time to regain focus after the menu bar interaction)
- [ ] 8.2 `var autoPaste: Bool` — read from `UserDefaults`, default `true`

### Task 9 — Settings UI + telemetry stats

`dictation/DictationApp/SettingsView.swift`

- [ ] 9.1 Hotkey picker: show current key label, button "Press a key…" → listen for next key event, update `HotkeyManager.hotKeyCode` and `UserDefaults`
- [ ] 9.2 Model tier picker: `Picker` with `.baseEn`, `.smallEn`, `.largeV3Turbo` — changing it triggers `transcriptionEngine.loadModel()` with new tier
- [ ] 9.3 Auto-paste toggle: `Toggle("Auto-paste after transcription", isOn: $autoPaste)`
- [ ] 9.4 Stats section (`WeeklyStats` from `TelemetryStore.fetchWeeklyStats()`):
  - "This week: X transcripts · Avg confidence: XX% · Correction rate: X%"
  - "Avg latency: XXXms · Avg audio: X.Xs"
- [ ] 9.5 `⌘⇧Z` global shortcut: wire via `NSEvent.addLocalMonitorForEvents` inside the popover view — calls `appState.markLastTranscriptCorrected()`

---

## Phase 3 — iOS Keyboard Extension (M3)

### Task 10 — App Group + shared container setup

- [ ] 10.1 Enable App Group `group.com.trika.dictation` on both `DictationContainerApp` and `DictationKeyboard` targets in Xcode (Signing & Capabilities)
- [ ] 10.2 Enable Microphone permission on `DictationKeyboard` extension target
- [ ] 10.3 Enable "Full Access" requirement in the keyboard extension's `Info.plist`: `RequestsOpenAccess = YES`
- [ ] 10.4 Confirm `TelemetryStore` uses `FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.com.trika.dictation")` when on iOS (add a `platform` flag or compile condition)

### Task 11 — KeyboardViewController

`dictation/DictationKeyboard/KeyboardViewController.swift`

- [ ] 11.1 Subclass `UIInputViewController`
- [ ] 11.2 On `viewDidLoad`: instantiate `KeyboardView` (SwiftUI), wrap in `UIHostingController`, add as child view controller filling `inputView`
- [ ] 11.3 Pass a `KeyboardViewModel` (ObservableObject) down to the SwiftUI view — the view model owns `RecordingEngine`, `TranscriptionEngine`, `TelemetryStore`
- [ ] 11.4 `KeyboardViewModel.insertText(_ text: String)`: call `self.textDocumentProxy.insertText(text)` — this is the bridge from SwiftUI back to `UIInputViewController`; use a closure or delegate pattern since `textDocumentProxy` lives on the view controller

### Task 12 — KeyboardView (SwiftUI layout)

`dictation/DictationKeyboard/KeyboardView.swift`

Layout (portrait, approximately 260pt tall — standard keyboard height):

- [ ] 12.1 Top utility row (full width, 44pt tall):
  - Left: `backspace` button → `textDocumentProxy.deleteBackward()`
  - Centre: `space` button → `textDocumentProxy.insertText(" ")`
  - Right: `return` button → `textDocumentProxy.insertText("\n")`
- [ ] 12.2 Centre area: large mic button (120×120pt circle):
  - Idle: grey mic icon
  - Recording: pulsing red mic icon (scale animation 1.0→1.1 repeating)
  - Transcribing: spinner
  - Tap toggles recording: idle → recording, recording → transcribing → idle
- [ ] 12.3 Transcript preview strip (below mic, 2 lines max):
  - Shows the last transcript text in `colors.secondary`
  - Tap the strip → mark as corrected (sets `wasCorrected = true` in telemetry)
  - If `engineLoaded == false`: show "Loading model…" in place of the transcript strip
- [ ] 12.4 Bottom row: keyboard switcher button (globe icon) → `self.advanceToNextInputMode()` via callback

### Task 13 — Recording → transcription → insertion pipeline (iOS)

`dictation/DictationKeyboard/KeyboardViewModel.swift`

- [ ] 13.1 Mirrors `AppState` on Mac but simpler:
  - `@Published var state: DictationState`
  - `@Published var lastTranscript: String = ""`
  - `var insertTextCallback: ((String) -> Void)?` — set by `KeyboardViewController` to call `textDocumentProxy.insertText`
- [ ] 13.2 `func toggleRecording()`:
  - If idle → start `RecordingEngine`
  - If recording → stop, begin `TranscriptionEngine.transcribe()`
  - On result: call `insertTextCallback?(result.text)`, update `lastTranscript`, save to `TelemetryStore`, set idle
- [ ] 13.3 VAD silence detection also triggers stop (same as Mac — `RecordingEngineDelegate.recordingEngineDidDetectSilence`)
- [ ] 13.4 `modelTier` is hardcoded to `.baseEn` for the keyboard extension (memory constraint — no picker in the keyboard)

### Task 14 — iOS Containing App

`dictation/DictationContainerApp/`

This app exists to satisfy Apple's requirement that keyboard extensions ship inside a containing app. Keep it minimal.

- [ ] 14.1 `ContentView.swift` — two tab views:
  - **Setup tab:** step-by-step instructions to enable the keyboard (Settings → General → Keyboard → Keyboards → Add New Keyboard → Dictation → Allow Full Access)
  - **Stats tab:** loads `TelemetryStore` from the shared App Group and shows `WeeklyStats` — same data as the macOS stats section
- [ ] 14.2 App icon, launch screen, bundle ID `com.trika.dictation` (containing app)
- [ ] 14.3 Keyboard extension bundle ID: `com.trika.dictation.keyboard` (Xcode sets this automatically when you add the extension target)

---

## Phase 4 — Findings Doc (ongoing)

### Task 15 — Findings infrastructure

- [ ] 15.1 Create `dictation/docs/whisperkit-failure-findings.md` with template:
  ```markdown
  # WhisperKit Failure Findings
  
  Updated from telemetry. Each finding: condition → observed behaviour → failure rate → Road to Sale implication.
  
  ## Findings
  (populated from use)
  
  ## Open questions for lab
  (populated from use)
  ```
- [ ] 15.2 Create `dictation/docs/architecture.md` — one-page overview: 3 targets, DictationCore package, GRDB, WhisperKit, data flow diagram (ASCII is fine)

---

## Task completion order

```
Phase 1 (Tasks 1–4)  →  Phase 2 (Tasks 5–9)  →  Phase 3 (Tasks 10–14)  →  Phase 4 (Task 15)
```

Within Phase 2, Tasks 5–7 must be sequential (scaffold → hotkey → pipeline). Tasks 8–9 can run in parallel after Task 7.

Within Phase 3, Task 10 is a prerequisite for 11–13. Task 14 can run in parallel with 11–13.

---

## Definition of done

- [ ] `./dictation.xcodeproj` builds all three targets without errors
- [ ] Mac: hold F5 in Terminal → transcript appears in clipboard → pastes automatically
- [ ] Mac: transcript logged in SQLite, visible in popover
- [ ] Mac: `⌘⇧Z` marks last transcript corrected
- [ ] iOS: Dictation keyboard appears in keyboard switcher
- [ ] iOS: tap mic → transcript inserted into any text field
- [ ] iOS: transcript logged in shared SQLite, visible in containing app Stats tab
- [ ] No third-party dependencies except GRDB and WhisperKit (both via SPM)
