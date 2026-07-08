# Just Talk — End-to-End Flows

> **Module docs:** [architecture.md](architecture.md) · [api.md](api.md) · [flows.md](flows.md) (this file)
> **Related deep-dive:** [macos-input-paste-audit.md](macos-input-paste-audit.md) (the hotkey/paste subsystem)

These are the real code paths, with the files and methods involved. The macOS flow is
the shipping product; the iOS flow is fully built and device-signing gated (see
[architecture.md](architecture.md)).

---

## Flow 1 — macOS dictation: hotkey → record → transcribe → clean → paste

The main flow. All orchestration lives in `JustTalk/AppState.swift` unless noted.

### Sequence

1. **Key press.** `HotkeyManager` (`JustTalk/HotkeyManager.swift`), pumped on its dedicated
   tap thread, sees the configured activation key (`CGEventTap` at `.cghidEventTap`, matched by
   `HotkeyConfig.match`). A press/release edge fires `hotkeyDidPress()` / `hotkeyDidRelease()`
   on the main queue. Suppressing keys (Fn / F-keys) are swallowed; real modifiers pass through.
2. **Mode → record.** `AppState.hotkeyDidPress()` routes by `HotkeyMode`:
   `hold` → `startRecording()`; `toggle` → `toggleRecording()` (start, then stop on the next tap).
3. **`startRecording()`:**
   - Gates on `dictationState == .idle && engineLoaded`, and on **mic permission** (never
     starts the audio engine without it — that was the "mic prompt on every key press" bug).
   - Resets the `CapturedAudioStream`, records the start time, captures the **frontmost app**
     (`NSWorkspace.frontmostApplication`) as the paste target.
   - Calls `cleanup.prewarm()` — warms the on-device LLM *while the user talks* (~355 ms warm
     vs ~1.3 s cold).
   - `recordingEngine.start()` (`DictationCore/RecordingEngine.swift`) installs the audio tap.
   - Shows the `RecordingHUD` ("Listening…") and opens the streaming session
     (`startStreamingSession` → `StreamingDictationSession`). There is **one** capture→transcribe
     path — every dictation streams STT; the old batch-vs-streaming split and its Settings toggle
     are gone.
4. **Audio capture.** `RecordingEngine` converts each native buffer to 16 kHz mono Float32 and
   calls back on the audio thread:
   - `recordingEngine(_:didReceiveBuffer:)` → `audio.append(buffer)` **synchronously on the
     audio thread** (order-preserving — this is what fixed long recordings scrambling).
     `CapturedAudioStream` appends under an `os_unfair_lock` with an O(1) critical section, so the
     audio render thread is never meaningfully blocked (it replaced the `NSLock`-based accumulator).
   - `recordingEngine(_:didUpdateLevel:)` → updates the HUD meter and the "too quiet" warning,
     and enforces the **10-minute hard safety stop**.
   - `recordingEngineDidDetectSilence` → `flushStreamingSegment`. VAD silence still does **not**
     auto-stop; it marks a segment boundary. Each flush hands the buffers captured since the last
     flush to the session, which transcribes that segment with the **single main `transcriber`**
     and appends the raw words, growing the live HUD pill. There is **no** second (preview) model —
     the old tiny `tinyEn` live-preview loop was deleted, because running two models starved the
     Neural Engine and dropped mic buffers. The session assembles **raw STT only** during speech;
     no cleanup runs yet.
   - **Per-word roll-up pill (PRD 0008, when `streamingPillEnabled`).** Instead of the per-segment
     preview above, a `StreamingTranscriber` (vended by the loaded transcriber, reusing its one
     model) runs on a ~100 ms tick and drives the pill's confirmed text via a constant-rate reveal.
     The HUD renders it on one head-truncated line, so words stream in and the oldest scroll off the
     front. This is the **live pill only** — the pasted text is still the batch pass at stop. Falls
     back to the per-segment preview if the provider can't stream.
   - **Provider selection (PRD 0008 §Outcome).** The transcriber is chosen in Settings by dictation
     language: **`AppleSpeechTranscriber`** (macOS 26, English — fast batch + native
     volatile/finalized streaming) or **`WhisperKitTranscriber`** (multilingual/Hinglish/Gujarati —
     LocalAgreement-2 window streaming). Both sit behind the same `SpeechTranscriber` /
     `StreamingTranscriber` contracts, so this flow is identical either way; only the adapter changes.
5. **Key release / second tap → stop.** `stopRecordingAndTranscribe()` routes to `stopStreaming()`:
   stops the engine, sets state `.transcribing`, snapshots the buffers, **persists the raw audio**
   (`persistRecording` → `FileRecordingStore`, last 5 kept), ingests the final tail (a no-pause
   recording simply ingests the whole buffer here), then awaits `session.finish()` and hands off to
   `finalizeStreaming`.
6. **Transcription** happens incrementally during capture (step 4) plus the final tail at stop —
   all through the single `transcriber`, assembling raw text only. `WhisperKitTranscriber` flattens
   each segment's buffers, rejects silence/quiet/hallucinated output, and returns a
   `TranscriptionResult`. `finalizeStreaming` preserves the batch path's safety net: an
   **empty-but-substantial-audio** result routes through `handleEmpty` → `failWithRetry` (audio
   preserved, HUD Retry shown). **Retry** re-runs the batch `performTranscription` →
   `transcribeWithRetry` — `withTimeout(60s)`, up to 3 attempts, `emptyResult`/`noAudioData`
   rethrown immediately, `TimeoutError` fast-failed.
7. **Cleanup — once, at stop, over the full transcript** (inside `session.finish()`):
   - The effective `CleanupLevel` is resolved against the record-start app
     (`effectiveLevel(forBundleId:)` — per-app overrides, e.g. Off in a terminal), captured when
     the session was created.
   - If level ≠ `.off`, `cleanup.clean(CleanupRequest)` runs **one** pass over the whole assembled
     raw transcript. `FoundationModelsCleanup` calls the on-device model under a length-scaled
     timeout, runs the output through `CleanupOutputSanitizer`, and applies the deterministic
     command/vocab/lexicon post-pass. **It never throws** — any failure falls back to
     `RuleBasedCleanup` with `usedFallback = true`.
   - Cleanup is deliberately **not** per-sentence. The previous per-sentence pass fed the prior
     cleaned sentence back as `priorContext`, which the small on-device model echoed — snowballing
     into 3–4× repeated sentences (`qc/bugs/streaming/repeated-sentence.md`). The one-pass model has
     no `priorContext`, so there is nothing to compound.
   - `finalText = cleanedText` (falls back to the raw transcript if cleanup returns empty).
8. **Paste** (`ClipboardPaster.writeAndPaste`, `JustTalk/ClipboardPaster.swift`):
   - Snapshots the user's clipboard, writes `finalText`, re-activates the target app, and
     `pasteWhenFocused` polls until the target is frontmost (up to ~0.6 s) before posting a
     **tagged** synthetic ⌘V (the tag stops the hotkey tap from self-triggering).
   - 700 ms later it **restores the user's prior clipboard** (guarded so newer copies /
     back-to-back dictations aren't clobbered). If the target never becomes frontmost it
     refuses to paste and leaves the text on the clipboard, surfacing "couldn't paste".
9. **Telemetry + correction window:**
   - Builds a `TranscriptRecord` (raw + final text, confidence, latency, model tier, frontmost
     app, cleanup level/provider) and `telemetryStore.save(record)`.
   - Opens a 5-second **correction window**; the HUD shows "Inserted" with a **"Mark wrong"**
     button → `markLastTranscriptCorrected()` → `telemetryStore.markCorrected`.
   - State → `.idle`, status → ready.

### Diagram

```mermaid
sequenceDiagram
    participant U as User
    participant HK as HotkeyManager
    participant AS as AppState
    participant RE as RecordingEngine
    participant WK as WhisperKitTranscriber
    participant CL as TextCleanup (FoundationModels→ruleBased)
    participant CP as ClipboardPaster
    participant TS as TelemetryStore

    U->>HK: press activation key
    HK->>AS: hotkeyDidPress()
    AS->>AS: startRecording() (gate mic perm, capture target app)
    AS->>CL: prewarm()
    AS->>RE: start() + startStreamingSession()
    loop while recording
        RE-->>AS: didReceiveBuffer (16kHz mono) → CapturedAudioStream
        RE-->>AS: didUpdateLevel → HUD + 10-min safety stop
        RE-->>AS: didDetectSilence → flushStreamingSegment
        AS->>WK: transcribe(segment) [single model]
        WK-->>AS: raw segment text → grow HUD pill
    end
    U->>HK: release / second tap
    HK->>AS: hotkeyDidRelease() / toggle
    AS->>RE: stop()
    AS->>AS: stopStreaming(): persistRecording (last 5) + ingest final tail
    AS->>WK: transcribe(final tail)
    WK-->>AS: raw text → assemble full transcript
    AS->>CL: clean(full transcript, ONE pass) [never throws]
    CL-->>AS: CleanupResult (cleaned, usedFallback?)
    Note over AS,WK: empty + substantial audio → handleEmpty → failWithRetry<br/>(Retry re-runs batch performTranscription: withTimeout 60s, ≤3 tries)
    AS->>CP: writeAndPaste(finalText, targetApp)
    CP->>U: synthetic ⌘V into target, restore clipboard
    AS->>TS: save(TranscriptRecord)
    AS->>U: HUD "Inserted" + 5s "Mark wrong" window
```

---

## Flow 2 — Timeout & resource release

A stuck model call must never hang the app or leave orphaned work holding resources.

1. `withTimeout(seconds:)` (`DictationCore/Timeout.swift`) races the operation against a timer
   as **independent unstructured tasks**. At the deadline the caller is freed even if the
   operation ignores cancellation; the stuck task is abandoned (OS reclaims it).
2. **Transcription timeout** — `performTranscription` catches `TimeoutError` →
   `recoverAfterTimeout()`: `transcriber.reset()` + `cleanup.reset()` drop the (possibly stuck)
   model/session, `engineLoaded = false`, then the transcriber is reloaded fresh in the
   background. The audio is preserved and a **Retry** affordance is shown.
3. **Cleanup timeout** — `FoundationModelsCleanup.clean` wraps the LLM call in
   `withTimeout(responseTimeout(wordCount:))` (15 s floor → 90 s ceiling, ~50 ms/word). On
   timeout it `reset()`s the held session and returns the rule-based result with
   `usedFallback = true` — paste still happens.
4. **Failure preservation** — `failWithRetry` keeps the audio in `pendingAudio` and shows the
   HUD's Retry / dismiss buttons (`retryPendingDictation` / `discardPendingDictation`), so a
   dictation is never silently lost.

---

## Flow 3 — Re-transcribe a saved recording

Recovers a junk/failed dictation without re-speaking.

1. Menu action → `AppState.reTranscribeLastRecording()`.
2. `recordingStore.recent().first` → `loadSamples(id:)` (off the main actor) →
   `AudioSampleBridge.makeBuffer(...)`.
3. Re-enters `performTranscription` with the loaded buffer — same clean/paste/telemetry path as
   Flow 1.

---

## Flow 4 — Startup, permissions & model load

1. `JustTalkApp` (`@main`) creates `AppState`; `AppState.init` runs `SystemPreflight.check()`
   (Apple Silicon / OS / disk / RAM), picks the STT provider — the saved one, else
   `SystemCapabilities.recommendedProvider` (**Apple Speech** on Apple Silicon + macOS 26, else
   WhisperKit) — builds the transcriber via `SpeechTranscriberFactory` + cleanup from config, then
   kicks off `setup()`.
2. `setup()`:
   - **Hard-requirements gate:** if `systemCapabilities.canRun` is false (Intel, macOS too old, no
     disk), show the requirements screen and stop — never a broken onboarding.
   - `refreshPermissions()` reads **non-prompting** status (mic, Accessibility, Input
     Monitoring) via `PermissionsService`.
   - Builds `HotkeyManager`; installs the event tap only once both tap permissions are present.
   - Starts a 1.2 s permission poll + `didBecomeActive` observer so the wizard's ticks update
     live.
   - Shows the onboarding wizard (`OnboardingView`/`OnboardingWindow`) on first launch or when
     a required permission is missing.
   - `transcriber.load(onProgress:)` loads the model. **Apple Speech** needs no download (just Speech
     authorization + a one-time language-asset install); **WhisperKit** downloads the model on first
     run, streaming progress to the status line. If Apple Speech load fails (Speech Recognition denied
     or locale unsupported), `setup()` **falls back to WhisperKit** and persists the switch, so the
     denied prompt doesn't return next launch.
   - Opens `TelemetryStore`, runs the **30-day privacy purge**, and loads recent transcripts.
3. The wizard's "press your key to test" step routes a press to
   `hotkeyDidReceiveConfiguredKey()` only (`HotkeyManager.isTesting`) to prove the key reaches
   the app — the definitive hotkey-conflict check.

---

## Flow 5 — iOS keyboard dictation: the Flow Session handoff

An iOS keyboard extension **cannot access the microphone** (a hard sandbox limit — no entitlement
exists; Wispr/Gboard/SwiftKey all hit it). So dictation is a **container-app handoff**: the keyboard
launches the host app to record, the app records + transcribes in the background, and the keyboard
inserts the result. The app-switch is unavoidable and Wispr pays the same cost. Every constant and
signal on this path goes through the one `DictationHandoff` facade (`DictationCore/DictationHandoff.swift`).

### Sequence

1. **Enable.** The user adds the **Just Talk** keyboard and grants **Allow Full Access** (needed so
   the App Group and URL-open are reachable), then switches to it with the globe key.
2. **Tap-to-start (keyboard).** `KeyboardViewModel.toggleRecording()` (state `idle → recording`) calls
   `openApp?(DictationHandoff.recordURL)`. `KeyboardViewController.openURLFromKeyboard(_:)`
   (`DictationKeyboard/KeyboardViewController.swift`) **walks the responder chain past `self`** to find
   `UIApplication` and invokes its modern `open(_:options:completionHandler:)` through the IMP — the
   compiler blocks calling it directly from an extension, and `extensionContext.open` won't launch the
   container app.
3. **App launches + records.** `DictationContainerApp.onOpenURL` (`DictationContainerApp.swift`) routes
   `justtalk://record` to a full-screen `RecordSessionView`. `RecordSessionModel.begin()`
   (`DictationContainerApp/RecordSession.swift`) loads the Apple transcriber directly
   (`AppleAnalyzerTranscriber` on iOS 26, else `AppleSpeechTranscriber` — the base product has no
   factory), requests mic permission, and starts `RecordingEngine`. It **does not self-suspend**: iOS
   would drop it to the home screen, not the caller, so — like Wispr — the user swipes back to their app
   and keeps talking. Recording continues in the background under **`UIBackgroundModes: audio`**.
   `RecordSessionModel` registers a Darwin observer for `stopNotification`.
4. **Tap-to-stop (keyboard).** The second tap (`state recording → transcribing`) posts
   `DictationHandoff.stopNotification`. `RecordSessionModel.stop()` fires: stop the engine, snapshot the
   buffers, `transcriber.transcribe(...)` → raw text, then one `cleanup.clean(...)` pass (same
   `TextCleanup` contract + cleanup pack as macOS, so cleanup fixes are inherited for free).
5. **Hand back.** `RecordSessionModel.finish()` writes the text with `DictationHandoff.write(text)` and
   posts `DictationHandoff.doneNotification`; `RecordSessionView` then backgrounds itself (`onClose`) so
   iOS returns to the app the user was in.
6. **Insert (keyboard).** `KeyboardViewModel` observes `doneNotification` (and also re-checks in
   `KeyboardViewController.viewWillAppear`) → `checkForHandoff()` → `DictationHandoff.consume()` reads +
   clears the pending transcript (ignoring entries older than ~120 s) → `insertText?(text)` →
   `textDocumentProxy.insertText(...)`. **No clipboard, no paste.** Status shows "Inserted ✓" with a
   "Mark wrong" correction affordance.

### Diagram

```mermaid
sequenceDiagram
    participant U as User
    participant KV as KeyboardViewModel
    participant KC as KeyboardViewController
    participant APP as ContainerApp (RecordSessionModel)
    participant H as DictationHandoff (App Group + Darwin)

    U->>KV: tap mic (start)
    KV->>KC: openApp(justtalk://record)
    KC->>APP: UIApplication.open via responder chain
    APP->>APP: begin() — mic + RecordingEngine (UIBackgroundModes: audio)
    U-->>APP: swipe back to original app, keep talking
    U->>KV: tap mic (stop)
    KV->>H: post(stopNotification)
    H-->>APP: stop()
    APP->>APP: transcribe (Apple) + cleanup (one pass)
    APP->>H: write(text) + post(doneNotification)
    H-->>KV: doneNotification
    KV->>H: consume() → text
    KV->>U: textDocumentProxy.insertText(text) — "Inserted ✓"
```

### Key macOS-vs-iOS differences

| Aspect | macOS (`AppState`) | iOS (keyboard + container app) |
|--------|--------------------|--------------------------------|
| Trigger | Global hotkey (`CGEventTap`) | In-keyboard mic button |
| Mic owner | The app itself | **Container app** (keyboard can't touch the mic) → Flow Session handoff |
| Cross-process | n/a | `DictationHandoff`: `justtalk://` URL + App Group + Darwin notifications |
| STT provider | Apple Speech (default) / WhisperKit (multilingual) | Apple only — `AppleAnalyzerTranscriber` (26) / `AppleSpeechTranscriber` |
| Package | Full `DictationCore` (+ WhisperKit) | Light `DictationCoreBase` (no WhisperKit) |
| Output | Synthetic ⌘V paste + clipboard restore | `textDocumentProxy` insert |
| Telemetry DB | App Support SQLite | App Group SQLite (`platform: "ios-keyboard"`) |
| Cleanup | Same contract + pack | Same contract + pack |
| Status | Shipping | Built; device-signing gated |
