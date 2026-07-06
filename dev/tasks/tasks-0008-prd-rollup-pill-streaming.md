# Tasks — PRD 0008 Per-word roll-up pill (LocalAgreement streaming STT)

Branch: `feat/rollup-pill-streaming`. Batch final-output path is untouched throughout; streaming feeds the pill only.

## Relevant Files

- `dictation/Shared/Sources/DictationCore/StreamingAgreement.swift` — **NEW.** Pure, model-free LocalAgreement confirmed/hypothesis split (procured from WhisperKit `AudioStreamTranscriber`).
- `dictation/Shared/Sources/DictationCore/StreamingTranscriber.swift` — **NEW.** The streaming contract (`StreamingTranscriber`, `StreamingTranscript`) — the streaming sibling of `SpeechTranscriber`.
- `dictation/Shared/Sources/DictationCore/WhisperKitTranscriber.swift` — add a streaming session reusing the loaded `WhisperKit` instance + `clipTimestamps`.
- `dictation/Shared/Sources/DictationCore/SpeechTranscriber.swift` — factory/contract hook to vend a streaming session (+ mock for tests).
- `dictation/Shared/Tests/DictationCoreTests/StreamingAgreementTests.swift` — **NEW.** Unit tests for the pure agreement logic.
- `dictation/JustTalk/AppState.swift` — throttled streaming tick during recording; feed HUD confirmed+hypothesis; teardown at stop.
- `dictation/JustTalk/RecordingHUD.swift` — roll-up render: confirmed (solid) + hypothesis (dim), head-truncated single line, animated, reduced-motion-safe.
- `dictation/JustTalk/SettingsView.swift` — `streamingPillEnabled` flag (default ON).
- `dictation/docs/architecture.md`, `dictation/docs/flows.md`, `dictation/docs/api.md` — document the streaming pill path.

## Tasks

- [x] 1.0 **Pure LocalAgreement logic (`StreamingAgreement`) + tests**
  - [x] 1.1 Define `AgreedSegment { text, start, end }` (Sendable) and `StreamingAgreement` value type with `requiredSegmentsForConfirmation`, `confirmedSegments`, `lastConfirmedEnd`.
  - [x] 1.2 Implement `integrate(freshSegments:)` mirroring `AudioStreamTranscriber.swift:166-189`: confirm all but the last N segments when `end > lastConfirmedEnd`; rest = hypothesis. Expose `confirmedText` / `hypothesisText`.
  - [x] 1.3 Unit tests: prefix confirmation, hypothesis tail, `lastConfirmedEnd` monotonic, tail-revision does not rewrite confirmed, empty/short inputs. Run `./test.sh core`.

- [x] 2.0 **Streaming contract + WhisperKit implementation**
  - [x] 2.1 `StreamingTranscriber` protocol + `StreamingTranscript { confirmed, hypothesis, confidence, latencyMs }` in DictationCore.
  - [x] 2.2 `WhisperKitTranscriber.makeStreamingSession()` → a session that reuses `self.whisperKit` + `biasPrompt`, holds a `StreamingAgreement`, and on `step(samples:)` decodes with `clipTimestamps=[lastConfirmedEnd]`, maps `TranscriptionSegment`→`AgreedSegment`, integrates, returns `StreamingTranscript`. Max-unconfirmed-window guard.
  - [x] 2.3 Mock streaming session (deterministic) so pipeline/tests don't need a model; factory/vend hook on the contract (nil when unsupported).

- [x] 3.0 **AppState wiring (pill only; batch output untouched)**
  - [x] 3.1 Behind `streamingPillEnabled`: on record start, create a streaming session from the transcriber (fallback to current per-segment preview if nil).
  - [x] 3.2 Add a throttled, non-reentrant tick (~0.8 s) that snapshots audio-so-far and calls `step`; update `recordingHUD` with confirmed+hypothesis.
  - [x] 3.3 Tear the session down at stop; **do not** alter `stopStreaming → performTranscription` (final batch output unchanged).
  - [x] 3.4 Confirm capture integrity: passes off the audio thread, non-reentrant, no per-buffer actor hop.

- [x] 4.0 **HUD roll-up render**
  - [x] 4.1 Extend `RecordingHUDModel` with a hypothesis field (or a combined `AttributedString`); render confirmed solid + hypothesis dim in one `Text`, `lineLimit(1)`, `.truncationMode(.head)`, width-capped.
  - [x] 4.2 Smooth growth animation reads as scroll-in; honor `accessibilityReduceMotion`.
  - [x] 4.3 Keep low-input warning + all other pill states intact.

- [x] 5.0 **Flag, build, docs**
  - [x] 5.1 `streamingPillEnabled` in SettingsView (default ON) + persistence.
  - [x] 5.2 `./build.sh` + `./test.sh core` green.
  - [x] 5.3 Update architecture/flows/api docs; note the deferred final-output speed win.
  - [x] 5.4 Pre-checkin checklist; commit.

- [x] 6.0 **Apple SpeechAnalyzer provider (live-test pivot — see PRD §Outcome)**
  - [x] 6.1 `AppleSpeechTranscriber` (batch `transcribe` + `makeStreamingSession`) on macOS 26 `SpeechAnalyzer`/`SpeechTranscriber`; `AppleStreamingSession` (finalized+volatile → pill); `AppleAudioConverter` (16k→Apple format).
  - [x] 6.2 `STTProvider.appleSpeech.isAvailable` OS-gated; factory builds it; Settings locale picker; `NSSpeechRecognitionUsageDescription`.
  - [x] 6.3 Pill fixes from live use: show Apple volatile (kill finalized-only lag); continuous 100ms feed + WhisperKit internal re-decode throttle.
  - [x] 6.4 Pill visual: flowing voice-tracking wave, shimmering gold border, richer background (bg color being finalized).

## Deferred (follow-up PRD)

- Promote streaming `confirmed` text to the **final pasted output** for the post-stop latency win (`dictation/dev/streaming-localagreement.md`).
- `appleSpeech` provider as a lighter live-pill source if the WhisperKit tick is too heavy on live mic.
