# Just Talk — Architecture

> **Module docs:** [architecture.md](architecture.md) (this file) · [api.md](api.md) · [flows.md](flows.md) · [build.md](build.md) (build / run / test / local install)
> **Deep dives:** [e2e-tests.md](e2e-tests.md) (cross-platform test plan) · [macos-input-paste-audit.md](macos-input-paste-audit.md) (hotkey/paste subsystem audit)
> **System context:** [../../docs/architecture.md](../../docs/architecture.md) (whole-monorepo) · [../../voice-engine/docs/model-contracts.md](../../voice-engine/docs/model-contracts.md) (the STT/cleanup contract this module mirrors)

## Overview

**Just Talk** is a Wispr Flow–style, fully on-device dictation product for Apple platforms.
Press an activation key, speak, and your words appear — cleaned into polished writing by an
on-device LLM — in whatever app is focused. Neither audio nor text ever leaves the machine.

There are **two product lines**, both built from the same shared core:

| Product line | Status | What it is |
|--------------|--------|------------|
| **macOS menu-bar app** (`JustTalk`) | **Shipping / daily driver** | The menu-bar app, bundle `com.trika.justtalk.mac`. Hotkey → record → transcribe → clean → paste into the frontmost app. |
| **iOS custom keyboard** (`DictationContainerApp` + `DictationKeyboard`) | **Implemented — device-signing gated** | A custom keyboard extension plus its host app. Because an iOS keyboard extension **cannot access the microphone** (a hard sandbox limit — no entitlement exists; Wispr Flow, Gboard and SwiftKey all hit the same wall), dictation uses a **container-app handoff ("Flow Session")**: the keyboard launches the host app via a URL scheme, the app records + transcribes in the background, and the keyboard inserts the result. Fully built; final on-device validation is gated on a paid Apple Developer account (custom keyboards can't run in the Simulator and need device provisioning). |

All product logic lives in **DictationCore**, a local Swift package every target consumes.
Target-specific code never goes in the shared package; macOS-only and iOS-only code stay in
their own target folders.

> **Naming note:** only the macOS *product* was renamed to "Just Talk". The shared package
> and the iOS targets keep their `Dictation*` names, and several on-disk paths (model cache,
> telemetry DB, App Group) still use the `com.trika.dictation` prefix for cache portability.

---

## Targets

Defined in [`project.yml`](../project.yml) (XcodeGen generates `JustTalk.xcodeproj`):

| Target | Platform | Type | Bundle ID |
|--------|----------|------|-----------|
| `JustTalk` | macOS 14+ | Application (menu bar, `LSUIElement` — no Dock icon) | `com.trika.justtalk.mac` |
| `JustTalkTests` | macOS 14+ | Unit-test bundle (hosted by `JustTalk`) | `com.trika.justtalk.mac.tests` |
| `DictationContainerApp` | iOS 17+ | Application (host for the keyboard extension) | `com.trika.justtalk.ios` |
| `DictationKeyboard` | iOS 17+ | App Extension (custom keyboard) | `com.trika.justtalk.ios.keyboard` |

- `DictationKeyboard` is embedded in `DictationContainerApp` (`embed: true`). An app
  extension's bundle ID must be a child of its host's, hence the `…ios.keyboard` suffix.
- The macOS target uses **manual signing** with a pinned local Development certificate so
  macOS TCC keeps Mic / Accessibility / Input Monitoring grants stable across rebuilds
  (ad-hoc signing changes identity each build and re-prompts). The iOS targets use automatic
  signing.
- Both iOS targets share the App Group `group.com.trika.dictation` — the cross-process channel
  for the Flow Session handoff (shared telemetry SQLite, the pending-transcript hand-off store,
  and the surface Darwin notifications signal across).
- The container app declares the **`justtalk://` URL scheme** (the keyboard opens
  `justtalk://record` to start a Flow Session) and **`UIBackgroundModes: audio`** (so recording
  keeps running after the user swipes back to their app). The keyboard links only the light
  **`DictationCoreBase`** product; the container app and the macOS app link the full
  **`DictationCore`** (see the package split below).

---

## DictationCore — the shared Swift package

Located at [`dictation/Shared/`](../Shared). Declared as a local package in `project.yml`.

**Package split — two products** ([`Shared/Package.swift`](../Shared/Package.swift)), driven by
the iOS keyboard extension's hard **~70 MB memory limit** (WhisperKit's model + CoreML footprint
blows past it):

| Product | Sources | Links | Consumed by |
|---------|---------|-------|-------------|
| **`DictationCoreBase`** | `Sources/DictationCore/` | **GRDB only** — all the contracts, the Apple transcribers, cleanup, capture, telemetry, `DictationHandoff`, `SystemCapabilities`. **No WhisperKit.** | the **keyboard extension** and the **iOS container app** |
| **`DictationCore`** (full) | `Sources/DictationCoreWhisper/` | `@_exported import DictationCoreBase` + **WhisperKit** + `WhisperKitTranscriber` + `SpeechTranscriberFactory` | the **macOS app** |

So the base product is self-contained and WhisperKit-free; the full product re-exports it and adds
the heavyweight WhisperKit path. `SpeechTranscriberFactory` lives in the **full** product — the base
product has no factory, so the memory-capped iOS side instantiates Apple transcribers directly.

> **Relationship to `voice-engine/`:** DictationCore does **not** import `voice-engine/`. It
> **mirrors** voice-engine's strategy contract in Swift — `SpeechTranscriber` is the batch
> sibling of voice-engine's streaming `TranscriptionStrategy`, and `TextCleanup` mirrors its
> `CleanupStrategy`. See the source comments referencing
> [`../../voice-engine/docs/model-contracts.md`](../../voice-engine/docs/model-contracts.md).
> The plan is to re-implement the same shape natively on Android and other platforms.

DictationCore is the **facade** the app targets program against (see [api.md](api.md)). Its
components:

### Recording — `RecordingEngine.swift`

Owns the `AVAudioEngine` session.

- Requests microphone permission (platform-appropriate: `AVCaptureDevice` on macOS,
  `AVAudioApplication`/`AVAudioSession` on iOS 17+).
- Installs a tap at the hardware's native format, converting each buffer to **16 kHz mono
  Float32** (`AVAudioConverter`) — exactly what WhisperKit expects.
- Hardware-format guards: clears any leftover tap and rejects a 0-rate/0-channel format
  (mid-session device change), turning an otherwise process-aborting `installTap` into a
  recoverable `RecordingError`.
- Energy-threshold **VAD**: per-buffer RMS, fires `recordingEngineDidDetectSilence` after
  `silenceDurationMs` of continuous silence. **The VAD signal is deliberately NOT used to
  auto-stop** — auto-stopping on a pause chopped sentences mid-thought. Recording is controlled
  by the activation key (and a hard 10-minute safety stop). A pause is instead treated as a
  **segment boundary**: the coordinator flushes the buffers since the last flush to the streaming
  session for incremental STT (the live HUD pill).

### Capture buffer, streaming session & semantic state

- **`CapturedAudioStream.swift`** — the thread-safe, order-preserving hold for captured buffers.
  The mic tap appends here synchronously on its render thread under an `os_unfair_lock` with a
  strictly O(1) critical section (no syscall), and `snapshot`/`drain` are O(1) too (Array
  copy-on-write is a retain, not an element copy) — so the audio render thread is never
  meaningfully blocked. It replaced `BufferAccumulator` (an `NSLock`-per-append store); appending
  in arrival order on the serial audio thread is what keeps long recordings from scrambling.
- **`StreamingDictationSession.swift`** — the single capture→transcribe path. As the recording
  engine flushes VAD-delimited segments during speech, `ingest(segment:)` transcribes each with the
  **one** main `transcriber` and appends the raw words (driving the live HUD pill); it assembles
  **raw STT only** while speaking. `finish()` runs **one** `cleanup.clean(...)` pass over the whole
  transcript at stop. Cleanup is deliberately not per-sentence: the previous per-sentence pass fed
  the prior cleaned sentence back as `priorContext`, which the small on-device model echoed into
  3–4× repeats (`qc/bugs/streaming/repeated-sentence.md`); one pass has no `priorContext` to
  compound. There is no second (preview) model — running two models starved the Neural Engine and
  dropped mic buffers, so the tiny live-preview transcriber was deleted.
- **`StreamingTranscriber` + `StreamingAgreement` + `WhisperKitStreamingSession`** (PRD 0008) — the
  **per-word roll-up pill**. A LocalAgreement-2 streaming session (vended by the loaded transcriber
  via `makeStreamingSession()`, reusing its **one** model) re-transcribes the growing buffer on a
  throttled tick and emits a stable `confirmed` prefix + tentative `hypothesis` tail. `AppState`
  drives the pill from it (confirmed solid, hypothesis dimmed, head-truncated single line — words
  stream in, oldest scroll off). It is the **live pill only**: the pasted text is still the batch
  pass, so the streaming pill can never corrupt output. Gated by `streamingPillEnabled` with a clean
  fallback to the per-segment preview. `StreamingAgreement` is pure/unit-tested; the confirm logic
  and `clipTimestamps` windowing are procured from WhisperKit's `AudioStreamTranscriber` (which owns
  its own mic and so couldn't be dropped in — see PRD 0008 §0).
- **`DictationState.swift`** — the forward-looking **semantic state** surface. Two orthogonal enums
  replace the tangled `dictationState` / `engineLoaded` / free-floating `statusMessage`:
  `DictationPhase { idle, capturing, finishing, inserted, failed(FailReason) }` (what THIS
  dictation is doing) and `EngineAvailability { warmingUp, ready, blocked(BlockReason) }` (can the
  system work at all — composing the mic-permission fact with model-warmth, which a single `Bool`
  can't express). Currently **additive**: `AppState` exposes computed `phase`/`availability` derived
  from the old published fields; views still bind the old fields until a later phase flips them over.

### Two pluggable model contracts

Both model layers sit behind a contract, selected at runtime by a `{provider, model}` config.
This is the strategy pattern mirrored from voice-engine.

**`SpeechTranscriber`** (`SpeechTranscriber.swift`, in `DictationCoreBase`) — the speech-to-text model.
- `load(onProgress:)`, `transcribe(buffers:audioStartDate:)`, optional `setVocabularyBias`,
  `reset()`, and `makeStreamingSession() -> StreamingTranscriber?` (the live pill — see below).
- `STTProvider` (`whisperKit`, `appleSpeech`, `mock`) + `STTConfig {provider, model}`.
  `SpeechTranscriberFactory` lives in the **full `DictationCore`** product (it needs WhisperKit);
  the base product has no factory. **Apple Speech is the DEFAULT on both Mac and iOS**; WhisperKit
  is the multilingual BACKUP. There are **three transcriber implementations** behind one contract:
- **`AppleAnalyzerTranscriber`** (`AppleAnalyzerTranscriber.swift`, **macOS 26 / iOS 26**) — the
  **primary** provider on modern OSes, on Apple's on-device `SpeechAnalyzer` + `Speech.SpeechTranscriber`
  (`preset: .progressiveTranscription`). Fast (~2× WhisperKit large-v3-turbo, ~250 ms), no model
  download, native volatile/finalized streaming (its `AppleStreamingSession` powers a smooth pill with
  no re-decode cost). `config.model` is a BCP-47 locale (e.g. `en-US`). `load()` requests Speech
  authorization and **throws cleanly if denied or the locale is unsupported** (macOS then falls back to
  WhisperKit). Language assets auto-download via `AssetInventory`; `AppleAudioConverter` bridges our
  16 kHz mono buffers to Apple's required format.
- **`AppleSpeechTranscriber`** (`AppleSpeechTranscriber.swift`, **iOS 17+ / older OSes**) — the
  lightweight Apple path on the classic `SFSpeechRecognizer`. Batch-only (no `makeStreamingSession`),
  no download, ~10 MB — small enough for the iOS keyboard extension's ~70 MB budget. Feeds all buffers
  into an `SFSpeechAudioBufferRecognitionRequest` and returns the single final result. Used when
  `SpeechAnalyzer` isn't available (pre-26); the factory picks it automatically.
- **`WhisperKitTranscriber`** (`WhisperKitTranscriber.swift`, in the **full `DictationCore`** product) —
  the **multilingual / accuracy BACKUP**: downloads/loads by `ModelTier` (default **`largeV3Turbo`**,
  ~954 MB; split download → load for first-run progress), gain-normalizes quiet audio, filters
  silence-hallucinations (peak floor + known-junk-phrase + low-confidence checks), and biases custom
  vocabulary via `DecodingOptions.promptTokens`. Model files cached under **Application Support**
  (`com.trika.dictation/huggingface`), not `~/Documents`, to avoid a burst of macOS Documents-folder
  TCC prompts. **The only provider covering Hinglish/Gujarati** (verified on-device, Apple ships NO
  Hindi/Gujarati model — only en/de/es/fr/it/ja/ko/pt/zh) and best on proper nouns (prompt-biasing).
- **Provider selection is the design lever: English → Apple (fast), Hinglish/Gujarati → WhisperKit**
  (PRD 0008 §Outcome). On macOS the runtime default is chosen by `SystemCapabilities.recommendedProvider`
  (Apple when Apple Silicon + macOS 26, else WhisperKit) — see `SystemCapabilities` below. The iOS side,
  linking only the WhisperKit-free base product, is **Apple-only** (Analyzer on 26, `SFSpeechRecognizer`
  otherwise).
- `TranscriptionResult` is provider-agnostic (carries `provider` + `model`).

**`TextCleanup`** (`TextCleanup.swift`) — the cleanup model (the on-device LLM that polishes
the text). `clean(_:)` **never throws** (cleanup must never block paste).
- `CleanupProvider` (`foundationModels`, `ruleBased`), `CleanupLevel` (`off`/`light`/`full`),
  `CleanupConfig {provider, level}` + `TextCleanupFactory`.
- **`FoundationModelsCleanup`** (`FoundationModelsCleanup.swift`) uses Apple's on-device
  `SystemLanguageModel`/`LanguageModelSession`. Availability-gated (`macOS 26+`/`iOS 26+`),
  with a length-scaled timeout, a degenerate-output guard, an output sanitizer, and a
  deterministic command/vocab/lexicon post-pass. Falls back to rule-based on any failure and
  flags `usedFallback`. Critically, it **never feeds the vocab/term list to the model** (the
  small model echoed names onto the clipboard); spelling is enforced by STT bias + the
  deterministic post-pass instead.
- **`CleanupOutputSanitizer`** (`CleanupOutputSanitizer.swift`) — pure, framework-free guards
  that scrub the model's output (strip tags/labels/echoed instruction blocks) and detect
  degenerate output (empty, ballooned, collapsed, echoed input) before it reaches the
  clipboard. Extracted so it's unit-testable without the FoundationModels framework.
- **`RuleBasedCleanup`** (`RuleBasedCleanup.swift`) — the deterministic fallback *and* the
  `ruleBased` provider, mirroring `voice-engine/src/cleanup/rule-based.ts`. The shared
  `CleanupText` transforms (fillers, repeats, normalization, sentence capitalization, map
  application) are reused by the Foundation Models post-pass.

**Knowledge as data — cleanup packs.** Prompts, fillers, command grammar, junk lists,
thresholds, and the domain lexicon load from JSON in
[`Resources/`](../Shared/Sources/DictationCore/Resources) via `CleanupPackLoader`:
- `dictation-cleanup-pack.json` — profile `"dictation"`, the default plain-dictation pack.
- `road-to-sale-cleanup-pack.json` — profile `"road-to-sale"`, adds a dealership **lexicon**
  (F&I, ACV, APR, trade-in, be-back, …) and spoken→canonical expansions. This is the bridge
  to the Road to Sale product: per-dealer catalog terms can be merged in at runtime via
  `CleanupPack.mergingLexiconTerms(_:)`.

(Canonical copies of these packs live under `voice-engine/cleanup-packs/`; a test asserts the
built-in `CleanupPack.fallback` stays in sync with the dictation JSON.)

### Persistence

**`RecordingStore`** (`RecordingStore.swift`) — a **cross-platform contract** that keeps the
last N raw recordings (mono PCM float samples + sample rate, never a platform audio type) so a
failed or garbled transcription can be re-run without re-speaking. `FileRecordingStore` is the
Apple implementation (Float32 mono WAV files, self-describing filenames). `AudioSampleBridge`
converts between the neutral `[Float]` samples and `AVAudioPCMBuffer`.

**`TelemetryStore`** (`TelemetryStore.swift`) — a Swift `actor` over a GRDB `DatabaseQueue`.
- Writes one `TranscriptRecord` per completed transcription: raw + cleaned text, confidence,
  latency, audio duration, model tier, frontmost app (macOS), cleanup level/provider, and a
  `wasCorrected` flag.
- Migrations `v1` (create) → `v2` (cleanup columns) → `v3` (index `recorded_at`).
- Reads: `fetchRecent`, `search`, `fetchWeeklyStats`, `fetchUsageTotals` (for cost
  projection). `markCorrected` flags a row when the user says the transcript was wrong.
- **Privacy retention:** `purge(olderThanDays:)` drops transcript text after 30 days (run on
  launch); audio is separately bounded to the last 5 recordings.
- DB locations: `~/Library/Application Support/com.trika.dictation/telemetry.sqlite` (macOS);
  the App Group shared container (iOS).

### Utilities

- **`Timeout.swift`** — `withTimeout(seconds:)`, an *unstructured* race: the operation and a
  timer run as independent tasks and the loser is abandoned (never awaited). Deliberate, so a
  hung `WhisperKit.transcribe` / `LanguageModelSession.respond` that ignores cooperative
  cancellation can't block the caller past the deadline.

### iOS Flow Session bridge — `DictationHandoff.swift`

The cross-process bridge for the iOS keyboard's **container-app handoff**. A keyboard extension
can't touch the microphone, so the keyboard hands recording off to the container app and reads the
result back. `DictationHandoff` is the single source of truth for every constant and channel that
handoff uses, backed by the shared App Group (`group.com.trika.dictation`):

- **URL scheme** — `recordURL` (`justtalk://record`): the keyboard opens this to launch a Flow
  Session in the container app.
- **Transcript store** — `write(_:)` (container app drops the finished text) / `consume(maxAgeSeconds:)`
  (keyboard reads + clears it). `consume` ignores entries older than ~120 s so an abandoned session
  can never inject text into an unrelated field later.
- **Darwin notifications** — `post(_:)` / `observe(_:)` over the Darwin notify center (the only
  cross-process signal that works keyboard ⇄ extension). Two names: `stopNotification`
  (keyboard → app: "stop recording now") and `doneNotification` (app → keyboard: "transcript is
  ready, come read it").

This type lives in `DictationCoreBase`, so both the keyboard and the container app share exactly
one definition of the protocol.

### System pre-flight & provider default — `SystemCapabilities.swift`

A launch-time check (`SystemPreflight.check()`) of whether **this Mac** can run Just Talk and which
STT provider to default to. It returns `SystemCapabilities { blockers, recommendedProvider,
cleanupIsFoundationModels, … }`:

- **Blockers** (vendor-sourced hard floor): not Apple Silicon, macOS below 14, < 2 GB free disk,
  < 8 GB RAM. Any blocker → `AppState` shows a clear requirements screen instead of a broken
  onboarding.
- **`recommendedProvider`** — the first-launch default when nothing is saved: **`appleSpeech`** on a
  capable machine (Apple Silicon + macOS 26), else **`whisperKit`**. This is why the effective default
  is Apple even though the `STTConfig.default` constant is WhisperKit (a safe fallback constant).
- The pure `decide(...)` function takes raw facts (arch, OS, disk, RAM, availability) so it is
  deterministically unit-testable; the environment reads (`sysctl`, disk) are separate.

---

## macOS subsystem (`JustTalk/` target)

This UI/permission/input layer is macOS-only and lives entirely in
[`JustTalk/`](../JustTalk).

- **`JustTalkApp.swift`** — `@main` SwiftUI `App`. A `MenuBarExtra` (window style) whose glyph
  changes with dictation state, plus a `Settings` scene and a "History" window.
- **`AppState.swift`** — the macOS coordinator (`@MainActor`, `ObservableObject`). Owns the
  `RecordingEngine`, transcriber, cleanup, stores, HUD, and `HotkeyManager`; drives the whole
  record → transcribe → clean → paste flow through a **single streaming path** (per-segment STT
  during speech, one cleanup pass at stop); manages settings, retries, the correction window, and
  resource release after a timeout. Captured buffers live in `CapturedAudioStream` (DictationCore),
  whose order-preserving audio-thread append is what fixed long recordings scrambling into garbage.
  `AppState` also exposes the derived `phase` / `availability` (see `DictationState.swift`) as the
  forward-looking state surface.
- **`PermissionsService.swift`** — single source of truth for the three required permissions:
  **Microphone**, **Accessibility** (to paste), **Input Monitoring** (for the keyboard event
  tap). All status reads are **non-prompting** (`AVCaptureDevice.authorizationStatus`,
  `AXIsProcessTrusted()`, `CGPreflightListenEventAccess()`) so the app can poll freely; system
  prompts fire only from explicit `request*` methods wired to onboarding buttons.
- **`HotkeyManager.swift` / `HotkeyConfig.swift` / `HotkeyConflict.swift`** — the activation-key
  subsystem. The user picks from a curated set (Fn, right ⌘/⌥/⌃, F5/F6/F13).
  `HotkeyManager` installs a `CGEventTap` at `.cghidEventTap`, **pumped on a dedicated
  background thread** so a busy main thread (transcription/cleanup) can never freeze the
  keyboard. Suppressing keys (Fn / function keys) use an active tap and return `nil` to swallow
  the key; real modifiers use a *listen-only* tap and pass through. A watchdog and a wake
  observer revive a tap that macOS silently disables. Synthetic paste events are tagged
  (`justTalkSyntheticEventUserData`) so the tap can't self-trigger. Conflict detection is
  best-effort (reads `AppleFnUsageType`, scans for competitors like Wispr Flow); the
  definitive check is the wizard's "press your key to test" step. See
  [macos-input-paste-audit.md](macos-input-paste-audit.md) for the full deep-dive.
- **`ClipboardPaster.swift`** — writes the transcript to the clipboard, re-activates the
  target app, polls until it's frontmost, posts a synthetic ⌘V, then **restores the user's
  prior clipboard** (guarded so back-to-back dictations and new user copies aren't clobbered).
  If the target never becomes frontmost it refuses to paste and leaves the text on the
  clipboard.
- **`RecordingHUD.swift`** — a floating, non-activating, click-through `NSPanel` near the
  bottom of the screen showing the live mic level, the **growing live pill** (raw STT from the
  single streaming session's own transcription — not a separate preview model), the "too quiet"
  warning, retry/dismiss on failure, and the post-insert "mark wrong" correction button. Floats above
  full-screen apps; remembers a dragged position. Fires open/close chimes (Wispr-style).
- **`OnboardingView.swift`** — the card-based setup wizard (live ticks), shown on first launch
  or whenever a required permission is missing; reopenable from the menu bar.
- **`LoginItem.swift`** — launch-at-login via `SMAppService`.
- **`SettingsView.swift` / `MenuBarView.swift` / `HistoryView.swift`** — settings, the menu-bar
  panel, and the transcript history/search window.

---

## iOS subsystem (`DictationKeyboard/` + `DictationContainerApp/`) — Flow Session handoff

The iOS line is a custom-keyboard product that reuses `DictationCoreBase`. Its defining constraint:
**an iOS keyboard extension cannot access the microphone.** There is no entitlement that grants it —
it is a hard sandbox limit, and every voice keyboard (Wispr Flow, Gboard, SwiftKey) works around it
the same way. Just Talk's workaround is the **container-app handoff ("Flow Session")**: the keyboard
bounces to the host app to record, and the host app bounces back. The app-switch is unavoidable and
Wispr pays the same cost.

### The Flow Session round-trip

1. **Keyboard, tap-to-start** — `KeyboardViewModel.toggleRecording()` sets state and calls
   `openApp?(DictationHandoff.recordURL)`. `KeyboardViewController.openURLFromKeyboard(_:)` walks the
   **responder chain past `self`** to find `UIApplication` and invokes its modern
   `open(_:options:completionHandler:)` through the IMP (the compiler blocks calling it directly from an
   extension; `extensionContext.open` doesn't launch the container app). This is the standard
   voice-keyboard technique.
2. **Container app records** — `DictationContainerApp.onOpenURL` routes `justtalk://record` to a
   full-screen `RecordSessionView`. `RecordSessionModel.begin()` requests mic permission and starts
   `RecordingEngine`. It does **not** self-suspend — like Wispr, the user swipes back to their app and
   keeps talking; recording continues in the background under **`UIBackgroundModes: audio`**.
3. **Keyboard, tap-to-stop** — the second tap posts `DictationHandoff.stopNotification` (Darwin).
   `RecordSessionModel` observes it, stops the engine, transcribes and runs cleanup, then
   `DictationHandoff.write(text)` + `post(doneNotification)`, and backgrounds itself so iOS returns to
   the original app.
4. **Keyboard inserts** — on `doneNotification` (or when the keyboard reappears via
   `viewWillAppear` → `checkForHandoff()`), `DictationHandoff.consume()` reads the transcript and
   `textDocumentProxy.insertText(...)` inserts it into the focused field. No clipboard, no paste.

### Files

- **`DictationContainerApp/`** — the host app the user installs.
  - `DictationContainerApp.swift` — the `@main` app; `onOpenURL` presents the record session (and a
    `justtalk://diag` diagnostics screen that dumps the keyboard's App-Group log).
  - `RecordSession.swift` — `RecordSessionModel` (the actual record → transcribe → clean engine, mirroring
    `AppState` but headless) + `RecordSessionView` (the "Recording… swipe back" UI). Picks the Apple
    transcriber **directly** (no factory in the base product): `AppleAnalyzerTranscriber` on iOS 26,
    else `AppleSpeechTranscriber`.
  - `ContentView.swift` — the Setup + Stats tabs (enable-the-keyboard instructions and telemetry).
- **`DictationKeyboard/`** — the `UIInputViewController` extension.
  - `KeyboardViewController.swift` — hosts `KeyboardView`, wires `insertText` to `textDocumentProxy`,
    and owns `openURLFromKeyboard` (the responder-chain launch).
  - `KeyboardViewModel.swift` — the `idle → recording → transcribing` state machine driving the handoff
    (open app, post stop, consume + insert on done). It builds an `AppleSpeechTranscriber` + rule-based
    cleanup and still carries an in-keyboard `performTranscription` path, but that path is **vestigial** —
    the mic can't run in the extension, so the live flow always goes through the container app.
  - `KeyboardView.swift` — the compact in-keyboard SwiftUI UI (mic button, level meter, status line,
    "Mark wrong" correction row, globe switcher).
- **Cleanup + telemetry** — both processes use the **same `TextCleanup` contract and cleanup pack** as
  macOS (rule-based in the extension's memory budget; Foundation Models upgrades automatically on iOS 26
  since it runs as an on-device system service outside the extension). Telemetry writes to the App Group
  SQLite DB (`platform: "ios-keyboard"`), parity with macOS.

The extension requires **Allow Full Access** (so the App Group and URL-open are reachable). The product
compiles and runs; final on-device validation is gated on a paid Apple Developer account — custom
keyboards can't run in the Simulator and need device provisioning.

---

## Isolation guarantees

- DictationCore never imports from any target-specific module, and never imports
  `voice-engine/` — it mirrors that contract instead.
- macOS-only code (Accessibility/CGEventTap, `NSWorkspace`, the floating HUD, `SMAppService`,
  the wizard, `PermissionsService`, hotkey subsystem) lives only in `JustTalk/`.
- iOS-only code (`UIInputViewController`, `textDocumentProxy`, the responder-chain URL-open) lives
  only in `DictationKeyboard/`; the container app's record engine lives only in
  `DictationContainerApp/`.
- The keyboard and container app communicate **only** through the App Group and Darwin
  notifications — all funnelled through the one `DictationHandoff` facade (URL scheme to launch,
  App-Group `UserDefaults` for the transcript, Darwin notifications for stop/done). No XPC, no
  shared memory, no direct linkage.

---

## Telemetry & dogfooding rationale

Every transcription is persisted (raw + cleaned text, confidence, latency, duration, model
tier, frontmost app on macOS). Corrections flag the row. This feeds **Road to Sale STT
calibration**: a high correction rate on a tier means pick a more accurate one; repeated
hallucinations in noisy environments inform dealership cue detection; latency regressions are
caught before they reach Road to Sale users. Daily personal use at the developer's desk is the
cheapest production STT test harness available.

**Telemetry findings to date: none yet** — the app is not yet in sustained daily use, so no
WhisperKit failure patterns have been catalogued. Findings will be recorded in
[whisperkit-failure-findings.md](whisperkit-failure-findings.md) (currently a placeholder with
its format defined) once daily-use telemetry accumulates.

---

## Build

```bash
cd dictation
brew install xcodegen        # one-time
./build.sh                   # Release .app into ./build (ad-hoc signed)
./build.sh install           # build + copy to /Applications
DEVELOPMENT_TEAM=XXXXXXXXXX ./build.sh install   # stable signing → permissions persist
```

`build.sh` regenerates the Xcode project with XcodeGen, then builds the `JustTalk` scheme.
See [README.md](../README.md) for the full setup, signing, and iOS-on-device steps.
