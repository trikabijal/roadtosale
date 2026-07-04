# DictationEngine design — Q&A part 2 (failure isolation + streaming capture)

## Q1. If transcription fails, does the recording save also fail? (Does "one facade" mean one failure?)

**No. One facade ≠ one failure.** A facade is a single *entry point* for the UI, not a single try/catch. Internally each step stays isolated, and the **ordering guarantees the audio is already committed before transcription is even attempted.**

Recall the `stop()` order:

```
stop() → RecordingEngine.stop()          (capture ends)
       → RecordingStore.save(buffers)     ← audio persisted HERE, first
       → SpeechTranscriber.transcribe(…)  ← if THIS throws, audio is already on disk
       → TextCleanup.clean(…)
       → paste
```

So:
- **Store succeeds and commits before transcribe runs.** Transcription throwing cannot un-save the audio.
- On transcribe failure the engine catches it, emits `onState(.failed)`, keeps the saved audio, and offers **retry** — which re-runs *transcription on the stored audio* (`retryLast()`), no re-recording.
- Cleanup failure is even softer: it already falls back (FoundationModels → RuleBased → raw text), so a cleanup error still pastes the raw transcript.

**Design rule to lock in:** the facade owns *orchestration and error surfacing*, not a shared fate. Each internal step has its own success/failure, and the persist-audio step is ordered first precisely so a later failure never costs you the recording. This is a property to protect in the rewrite, not something the facade weakens.

## Q2. How does recording keep working while streaming?

Capture and transcription run at **different cadences and never block each other.** The mic stream fans out to two independent consumers:

```
                         ┌─→ full-session buffer ──→ RecordingStore.save() at stop
mic → RecordingEngine ──┤     (the complete audio, always — retryLast source)
                         └─→ segment window ───────→ StreamingSession → transcriber
                               (flushed on VAD pause, transcribed DURING speech)
```

- **Capture writes to one continuous buffer for the whole dictation, regardless of streaming.** Streaming does not change what gets recorded — the master buffer still accumulates every sample start-to-stop.
- **Streaming only changes when transcription reads.** The streaming transcriber consumes *copies* of flushed segments incrementally while you speak; it never drains or truncates the master buffer.
- At `stop()`, the same `RecordingStore.save(fullBuffer)` runs → **complete audio is still saved**, so `retryLast()` has the entire recording even in streaming mode.
- Because capture is decoupled from transcription cadence, a slow/failing transcriber cannot stall the mic. The recording keeps filling; the store still gets the whole thing.

**One-line model:** streaming = "transcription reads the audio in slices as it arrives"; recording = "one buffer captures all of it and is saved whole at stop." Two consumers of one stream, neither blocking the other. This is also why streaming makes the "middle dropped" bug structurally harder — each segment is decoded independently, and the full audio is retained for after-the-fact comparison.
