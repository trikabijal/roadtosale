# Durable raw-audio capture — the real fix (2026-07-04)

## The problem you're actually reporting

Three times today: **transcription looked fine, but the raw voice recording was lost / gappy.** That means the failure is *below* transcription — in capture/persistence — and no amount of streaming or cleanup design matters if the raw material isn't guaranteed on disk. You're right to stop me here.

The current design has a real weakness that explains it:

- Capture accumulates audio **in an in-memory buffer** for the whole dictation.
- `RecordingStore.save(buffers)` runs **once, at `stop()`**, writing the whole in-RAM buffer.
- So between `start()` and `stop()` the ONLY copy of your audio lives in RAM. Anything that drops a buffer or resets that array mid-recording (a thread stall, an error path, memory pressure) loses audio **before it's ever written to disk** — and the transcript can still look plausible because it transcribed whatever survived.
- Streaming made this worse: running a second model during capture created Neural-Engine / CPU contention that can starve the audio tap thread → dropped mic callbacks → missing middle. Same root cause as your "middle got eaten" reports.

**Your instinct is the correct architecture:** the moment the mic produces samples, they must be persisted, with **zero dependency** on transcription, cleanup, streaming, or anything downstream.

## First — how the streaming transcribe loop actually works

Streaming is "buffer → transcribe → repeat," yes. Concretely:

1. The mic (AVAudioEngine tap) delivers audio in tiny callback buffers — ~100 ms PCM frames — on a real-time audio render thread.
2. Those frames append to a rolling window.
3. A **segmenter (VAD)** watches the window. When a segment boundary hits — a ~1–2 s block or a silence/pause — it **flushes**: hands that *slice* to the transcriber.
4. The transcriber decodes the slice and applies **LocalAgreement-2** (compares overlapping decodes of successive windows, only commits words that agree twice) → emits stable partial text. The trailing unstable words stay provisional.
5. Window advances; repeat. At `stop()`, only the last unflushed slice remains to decode.

Key point: the slice handed to the transcriber is a **copy**. The capture callback keeps appending on its own thread in parallel — transcription never reads from or drains the capture path. That separation is what we must make *bulletproof and durable*, not just in-memory.

## The fix: VoiceRecorder writes to disk continuously, dependency-free

Make raw capture the **innermost layer**, with one job and no downstream coupling:

```
mic tap (real-time audio thread)
      │  push frames to a lock-free ring buffer   (cheap: memcpy only, no I/O, no ANE)
      ▼
dedicated writer thread
      │  drain ring → append PCM frames to an OPEN file  (streaming WAV writer)
      ▼
recording-<id>.wav   ← grows continuously from start(); source of truth; the raw material
```

Rules that give you the guarantee:

- **The file is opened at `start()` and appended to as frames arrive** — not written once at stop. At every instant, everything captured so far is already on disk.
- **The audio render thread never does I/O.** It only pushes frames to a lock-free ring (bounded, no locks, no allocation). A separate writer thread does the file append. This is the standard real-time-audio pattern — I/O on the render thread is exactly what causes glitches/drops.
- **Capture has no reference to the transcriber, cleanup, or streaming session.** It cannot be starved or blocked by them. Even if every downstream stage crashes, the `.wav` on disk holds every frame up to the failure.
- `stop()` becomes: stop the tap → flush the ring → **close/finalize the file header**. Not "now serialize the whole buffer."
- Downstream (batch transcribe, streaming transcriber, `retryLast`) all **read from the file** (or a tap copy). Capture is never gated on them.

This directly implements what you said: "as soon as the voice is captured by the mic, it has to be saved… no dependency on transcribing or cleaning."

## Where it sits in the facade

This is the answer to the earlier open question too — **capture becomes its own named sub-facade, `VoiceRecorder`**, because it needs an independent lifecycle and durability guarantee that an "internal step of stop()" can't provide:

```
DictationEngine (facade to UI)
  ├─ VoiceRecorder        ← owns mic + ring + writer thread + the .wav file. Durable. Standalone.
  │     start() opens file, streams frames to disk
  │     stop()  finalizes file, returns its URL
  ├─ SpeechTranscriber    ← reads the file / receives streamed slices
  ├─ TextCleanup
  └─ RecordingStore       ← now just indexes/retains the finalized files (rotation of last N)
```

`RecordingStore.save()` stops being "write the audio" (too late, in RAM) and becomes "register the already-on-disk file + rotate old ones." The write already happened, continuously, inside `VoiceRecorder`.

## Why this fixes the three lost recordings

- No in-RAM-only window → a mid-recording stall can at worst lose the few frames still in the ring at the instant of the crash, not the whole/middle of the take.
- Capture thread is isolated from ANE/CPU contention → streaming's second model can't starve the mic tap.
- The transcript can no longer "look fine while the recording failed," because the recording is the durable artifact and the transcript is derived from it — if the file is short, we see it immediately (captured-duration vs wall-clock, already instrumented).

## Immediate diagnostic to confirm root cause (cheap, do first)

Add to the real app: on each mic callback, count frames and log **captured-sample-duration vs wall-clock elapsed**, and keep the last N raw files. Next drop, compare the saved `.wav` against the transcript:
- File short vs wall-clock → **capture drop** (this design fixes it).
- File full, transcript short → **STT drop** (streaming's independent-segment decode fixes that).

Either way the durable-capture rewrite is the correct move; the log just tells us which arrow we cut first.
