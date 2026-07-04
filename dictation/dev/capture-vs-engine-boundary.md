# The capture/engine boundary — thinking through your model (2026-07-04)

## Your proposal

> The front-end's only job is: start capturing voice, stop capturing voice, continuously buffer it to disk. It signals the engine "a voice is being captured, here's its location." The engine orchestrates everything about transcription — streaming or not — off that.

**Verdict: this is the right seam. Adopt it.** One refinement (what "UI" really means here) and one correction (hand the location at *start*, not stop). Details below.

## Why it's right

Three independent reasons converge on it:

1. **It makes the durability guarantee structural.** The one thing that must never fail — raw audio — is owned by the simplest component and has *zero* reference to the engine. Transcription can't starve or block what it can't touch. This is the fix for today's "middle got eaten": the contention was between capture and the models; put them in different layers and the contention is gone by construction.

2. **The engine becomes pure and testable.** Input = an audio file (possibly still growing). Output = text + events. No mic needed to test it — feed it a `.wav` fixture and assert. Batch vs streaming becomes an *internal policy on the same input*, invisible to everyone else.

3. **It matches the cross-platform principle already in the plan.** Capture is inherently platform glue (AVAudioEngine on Apple, AudioRecord on Android). The brain — orchestration, streaming policy, cleanup, telemetry — is the portable part. Putting capture at the edge is exactly the "contract + engine ports, platform code doesn't" split.

## Refinement 1 — "UI" is not the SwiftUI views. It's a thin CaptureService at the app edge.

The SwiftUI views must stay dumb (render + fire intents). They should NOT own an `AVAudioEngine`, a ring buffer, and a writer thread — that's not view code. So the layer that owns capture is a **`CaptureService`** sitting at the front-end/app boundary, *beside* the views, not inside them:

```
┌─ Front-end (app, platform-specific) ─────────────────────┐
│  Views (SwiftUI)   — pure render; hotkey/buttons → intents │
│  CaptureService    — owns mic tap + ring + writer + .wav   │
│                      start/stop, buffer→disk, emit level    │
└───────────────────────────────────────────────────────────┘
                         │ hands {url, id} at start; .finish at stop
                         ▼
┌─ DictationEngine (DictationCore, portable brain) ─────────┐
│  reads the file; owns VAD, transcribe (stream|batch),     │
│  cleanup, paste, telemetry, history                       │
│  emits onReady / onState / onLiveTranscript / onFinal     │
└───────────────────────────────────────────────────────────┘
```

So capture *is* front-end (your instinct), but it's a service, not the views. Two things flow back to the HUD from two different places, which is correct because they answer different questions:
- **mic level** (for the living waveform) → straight from **CaptureService** (it's a property of the audio signal).
- **live transcript text + state** → from the **Engine**.

## Refinement 2 — hand the location at START, not stop (so streaming works)

If the engine only learns the file location at *stop*, it can't transcribe during speech — no streaming. So the contract is:

```
hotkey down → View fires .start
   → CaptureService.start()  opens recording-<id>.wav, streams frames to disk, starts level meter
   → CaptureService gives DictationEngine.begin(source: url)      ← location handed HERE, at start
   → Engine starts TAILING the growing file (if streaming) or just notes the url (if batch)

hotkey up  → View fires .stop
   → CaptureService.stop()   finalizes the wav header, returns final url
   → CaptureService calls Engine.finish()
   → Engine decodes the remaining/whole audio, cleans, pastes, emits onFinal
```

The engine reads a **growing file** — the "tail -f" pattern. It reads only up to the last *fully committed* frame (the writer commits whole frames, the reader stops at the last frame boundary — never reads a half-written frame). This one interface — a file that grows — is the entire coupling between capture and engine.

## The payoff: streaming vs batch is now invisible to the UI

Because the interface is "a file that grows," **batch and streaming are the same input, differing only in when the engine reads it:**
- **Streaming** = engine tails the file during speech, emits `onLiveTranscript` as segments complete.
- **Batch** = engine ignores the growth, reads the whole file at `finish()`.

The UI, the CaptureService, and the file are *identical* in both modes. "Whether using streaming or not" becomes one engine-internal flag on one code path — which is exactly the entanglement we're trying to kill (today streaming branches the HUD source and runs a second model; here it can't, because the HUD only ever renders `onLiveTranscript` and capture is untouched).

## Ownership split that falls out of this

| Artifact | Owner | Why |
|---|---|---|
| Raw `.wav` files (+ rotation of last N) | **CaptureService** | it creates them; it owns their lifecycle |
| Mic level / waveform | **CaptureService** | property of the live signal |
| VAD / segment boundaries | **Engine** | a transcription concern, run on the tailed stream |
| Transcript, cleanup, history, telemetry | **Engine** | derived data |
| `retryLast` | **Engine** re-reads the same url | audio already durable on disk |

Note `RecordingStore` splits: the *audio-file* retention moves to CaptureService (owns the artifact); the *transcript/telemetry* store stays in the engine. Capture owns raw material; engine owns derived material. Clean.

## Honest caveats

- **Half-written frame:** reader must stop at the last committed frame boundary. Trivial with a fixed frame size; call it out so it's not missed.
- **Disk as interface adds ~a frame of latency** (OS page cache, not a real disk seek) — negligible next to STT time. If it ever mattered, capture could *also* tee frames in-memory to the engine as an optimization, but that reintroduces coupling — don't do it unless measured. Default: file only.
- **File path** must be a stable app-container location (survives sandbox, survives crash). Minor.

## Bottom line

Your model is correct and it's the same conclusion the facade work was heading toward, sharpened by the durability bug:

- **Front-end** = dumb views + a `CaptureService` that does exactly three things: start, stop, buffer-to-disk continuously — plus emit level and hand the engine a file URL at start.
- **Engine** = everything else, reading a growing file, choosing streaming or batch internally, emitting semantic events.
- **The file is the interface.** That single seam is what guarantees your raw material and dissolves the streaming/UI entanglement at the same time.
