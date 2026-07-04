# Dictation engine architecture (macOS "Just Talk")

Consolidates the design agreed in the 2026-07-04 session. Supersedes the scattered notes
(`capture-vs-engine-boundary.md`, `durable-capture-design.md`, `engine-events-naming.md`,
`engine-design-qa*.md`) as the single reference.

## Why this change

Three dogfooding failures, one root architecture flaw — `AppState` (the UI coordinator) owns and
drives every backend piece, and capture, transcription, and cleanup share one coordinator and one
machine's Neural Engine:

1. **Lost / gappy recordings** — capture and transcription contend; a stalled/starved audio thread or
   an error path loses audio, and the transcript still looks plausible.
2. **"Middle dropped"** — running a second (preview) model during streaming starves the Neural Engine
   and can drop mic-tap buffers. Capture and models contend because they share the coordinator.
3. **Same sentence repeated 3–4× (streaming ON)** — CONFIRMED: per-sentence cleanup feeds the prior
   cleaned sentence as `priorContext` with a *negative* instruction ("do NOT repeat it") the small
   on-device model ignores; the echoed output becomes the next `priorContext` → snowball. See
   `qc/bugs/streaming/repeated-sentence.md`.

## The principle

The front-end's only job: **start capture, stop capture, hand a non-blocking buffer stream to the
brain.** A `DictationEngine` owns transcribe → clean off that stream and emits semantic events. The
recorder and the engine share nothing but the in-memory stream.

**Why RAM, not a file, is the interface** (settled after challenging the first draft): disk write is
negligible (64 KB/s, page-cached) so it wouldn't slow us — but a file only buys crash-durability,
which is NOT the failure we saw (the app stayed alive; and if the tap is starved there is no buffer to
write to disk *or* RAM). The real fix is **decoupling capture from transcription + deleting the second
model**, fully achieved in RAM. The file is kept only as the at-stop retry sink.

```
CaptureService (recorder)                         DictationEngine (brain)
 owns mic + non-blocking buffer stream             owns transcribe + clean + phase/availability
 output = a buffer stream + level                  input  = a buffer stream
 persists last-5 WAV once at stop (retry sink)      knows NOTHING about the mic
 knows NOTHING about transcription   ──stream──▶
```

## Hot-path flow

```
HOTKEY DOWN
  View → AppState.startDictation()             (thin adapter, no logic)
       → CaptureService.start()
            • RecordingEngine tap starts (16k/mono/Float32, serial audio thread)
            • each didReceiveBuffer → push to a NON-BLOCKING stream (lock-free ring / double-buffer;
                                      the audio render thread NEVER blocks on the consumer)
                                    → onAudioLevel(rms) → adapter → HUD meter
       → DictationEngine.begin(source: bufferStream, mode)
            • onPhase(.capturing)
            • streaming: drain the stream live, transcribe each VAD-flushed segment,
                         onPartialText(RAW words) → live pill   (ONE model; preview transcriber deleted)
            • batch: hold the stream; no model runs during capture
HOTKEY UP
  View → AppState.stopDictation()
       → CaptureService.stop()   stop tap • close the stream • persist captured audio ONCE to the
                                 last-5 WAV store (off the hot path) — for retry / drop diagnostics
       → DictationEngine.finish()
            • onPhase(.finishing)
            • assemble FULL raw transcript (drained segments + final tail)
            • ONE cleanup pass on the whole text   ← no priorContext → repeat bug gone
            • onFinalText(cleaned, raw)   OR   onPhase(.failed(reason))
  AppState adapter, on onFinalText:
       ClipboardPaster.writeAndPaste(targetApp:) → TranscriptRecord → telemetryStore.save
       → recentTranscripts → HUD .inserted → correction window
```

Paste / History / correction-window stay in the adapter (AppKit / GRDB / clipboard concerns). The
engine is purely the transcribe→clean brain over a buffer stream — portable, testable with a synthetic
buffer stream (or a `.wav` decoded to buffers).

**The one reliability invariant:** the audio render thread must never block and never be starved —
minimal non-blocking enqueue + no heavy compute (the deleted 2nd model) competing with CoreAudio.

## State model — the `onState` rename

Today one tangled surface: `dictationState {idle,recording,transcribing}` (`AppState.swift:70`) +
`engineLoaded: Bool` (`:74`) + a free-floating `statusMessage` mutated from ~15 sites, conflating two
unrelated questions. Split into two enums, each its own event:

| Event | Enum | Replaces | Answers |
|---|---|---|---|
| `onPhase` | `DictationPhase { idle, capturing, finishing, inserted, failed(FailReason) }` | `dictationState` | what is THIS dictation doing right now |
| `onAvailability` | `EngineAvailability { warmingUp, ready, blocked(BlockReason) }` | `engineLoaded` + load-related `statusMessage` | can the system work AT ALL right now |

- `FailReason = transcription \| cleanup \| noSpeech`. `BlockReason = microphoneDenied \| accessibilityDenied \| modelUnavailable`.
- `recording → capturing`, `transcribing → finishing`; new `inserted` (success flash) and `failed(reason)`.
- **Availability aggregates two components** (a consequence of separation): mic-permission is a
  CaptureService fact, model-warm is an Engine fact. The adapter composes them — `warmingUp` until the
  model loads, `blocked(.microphoneDenied)` from the recorder, `ready` only when both are go. A single
  `Bool` cannot express this; the enum can.
- `statusMessage` stops being a stored field and becomes a pure function of `(availability, phase)`.

## How separating the recorder from the engine changes the design (8 concrete effects)

1. **Interface = a non-blocking in-RAM buffer stream** (lock-free ring / double-buffer), replacing the
   shared `NSLock` `BufferAccumulator`. The engine drains the stream (all at stop for batch, live for
   streaming). The file is NOT the interface. Core change.
2. **Persistence stays at-stop (one write), ownership moves** `AppState.persistRecording` →
   `CaptureService`. `RecordingStore` unchanged in role: last-5 WAV retry sink. Continuous disk write
   is optional future crash-insurance, not the mechanism.
3. **Second model deleted.** The preview transcriber existed only to show live text off the RAM buffer;
   the engine's own streaming STT feeds `onPartialText`. One model total → ANE contention gone.
4. **Threading decouples.** Capture pushes to its own stream on its own thread; transcription drains it
   elsewhere. No shared mutable buffer, no shared lock → no contention-induced middle-drop.
5. **Failure isolation via decoupling** (not a file): a transcribe/cleanup failure downstream cannot
   corrupt or stall capture; the at-stop WAV persist is a separate sink, so a failed transcription
   still leaves the audio for retry.
6. **Event direction inverts.** Today transcription pokes the HUD from inside itself; after, the engine
   emits events and the adapter forwards. HUD **level** from CaptureService, **transcript** from engine.
7. **Retry unifies on the stored WAV.** `pendingAudio` RAM cache + `retryPendingDictation` collapse
   into "re-read the last WAV" — same path as `reTranscribeLastRecording`.
8. **Portability falls out.** CaptureService is the only platform-specific piece (AVAudioEngine →
   Android AudioRecord); engine + contracts port unchanged.

## Locked decisions

- **Cleanup runs ONCE at stop** on the full raw transcript (same proven path as batch). No
  `priorContext`, no per-sentence session, no small-model special-casing → repeat bug cannot recur.
  STT still streams live for the pill. Accept post-stop cleanup wait on long clips; add incremental
  cleanup later only if measured painful.
- **In-RAM buffer stream is the interface**, NOT a file. File = at-stop retry sink only.
- **Keep the audio: last-5 WAV, written once at stop** from the RAM buffer (unchanged from today) — for
  retry, drop diagnostics, future learnings. Trivial cost (~20 MB for 5 clips).
- **"UI" = a thin CaptureService, not the SwiftUI views.** Views stay dumb.

## Reuse (do not rebuild)

- `RecordingEngine.didReceiveBuffer` — already 16k/mono/Float32 on a serial audio thread; the enqueue point.
- `FileRecordingStore` (WAV format, `rec-<millis>-<dur>.wav` scheme, `recent()`/`prune()`, `loadSamples`).
- `AudioSampleBridge.flatten/makeBuffer` — sample↔buffer bridging.
- Batch `cleanup.clean(...)` path for the single final cleanup pass.
- `AppDelegate.$dictationState` Combine subscription (`JustTalkApp.swift:89-91`) — the precedent for the
  event-subscribing thin adapter `AppState` becomes.

## Build phases (relief-first)

- **Phase 1 — Kill the repeat bug** (failure #3). `StreamingDictationSession` assembles raw only; one
  cleanup pass at stop; delete `priorContext` + the prompt context block. Small, contained.
- **Phase 2 — Non-blocking capture handoff** (hardens #1/#2). Replace the `NSLock` `BufferAccumulator`
  with a lock-free ring / double-buffer.
- **Phase 3 — DictationEngine facade + CaptureService + thin adapter** (failure #2). New enums +
  delegate + engine over the buffer stream; delete the second model; `AppState` → thin adapter.
- **Phase 4 — Views + status cleanup.** Model-load peeks → availability; HUD live text only from
  `onPartialText`; optional `@Published` renames.

## Verification

- `cd dictation && ./build.sh` → BUILD SUCCEEDED (macOS 26.5 / Xcode 26.5 / M4).
- `cd dictation/Shared && swift test` + `JustTalkTests` green.
- Phase 1: streaming ON → multi-sentence dictation with pauses → NO repeated sentences; one clean
  History record.
- Phase 2: sustained dictation under load → captured-sample-duration ≈ wall-clock (no dropped buffers).
- Phase 3: batch + streaming both show live pill from one model; retry re-reads the last WAV.
- On-device: hotkey after sleep, HUD live text, retry, long-clip behavior.
