# DictationEngine design — final Q&A (verbatim from session 0e3db6bd, 2026-07-04)

Good questions — let me take them in order.

## 1. Settings is a separate path, not the hot flow — agreed

`applySettings()` fires **only when the user changes a setting** (picks a model, toggles streaming, edits vocab). It's a configuration event, wholly separate from the per-dictation start/stop loop. I listed it with the commands only for completeness; it doesn't belong in the dictation flow diagram. Think of it as: *the flow* (start → stop, many times a day) vs *config* (rare, one-off). Keep them mentally separate — you're right.

## 2. Capture vs. storage vs. transcription — they're already separate; the engine just orchestrates them

These are **three distinct concerns, and today they're already three separate contracts** in DictationCore:

| Concern | Who does it |
|---|---|
| Capture (mic → 16kHz audio buffers, VAD) | `RecordingEngine` |
| Store (persist last 5 raw recordings) | `RecordingStore` |
| Transcribe (audio → text) | `SpeechTranscriber` |

So "should capture live in the same engine as transcription?" — **No, and it doesn't.** They stay separate services. The `DictationEngine` **facade** *orchestrates* them; it doesn't merge them. Behind the facade they're independent and independently testable.

**Where does audio storage happen? Internally, on `stop()` — the UI never calls "save."** The flow inside `engine.stop()` is:

```
stop()  →  RecordingEngine.stop()           (capture ends, hand over the buffers)
        →  RecordingStore.save(buffers)      (persist raw audio — for retryLast)
        →  SpeechTranscriber.transcribe(…)   (audio → text)
        →  TextCleanup.clean(…)              (polish)
        →  paste
```

The important seam you're sensing: **the audio store sits *between* capture and transcription.** That's exactly why `retryLast()` works — it re-runs *transcription on the stored audio* without re-capturing. So capture and transcription are already decoupled internally; the stored audio is the boundary. The facade doesn't erase that — it composes it.

So: **one facade to the UI, but internally: capture → store → transcribe → clean are four separate steps, and storage is an internal side-effect of stop.** If you'd prefer capture to be its own *named sub-facade* (a `VoiceRecorder` the engine composes and that could be reused headlessly), that's a clean option too — same seam, just given a name. Your call.

## 3. onReady / onState / onLiveTranscript — the three are different *axes*

They feel overlapping but answer different questions:

| Event | Question it answers | When it fires | What the UI does |
|---|---|---|---|
| **`onReady`** | "Can the engine work *at all* right now?" (system health — model warm? mic granted?) | Rarely — at launch, on permission change, after a settings reload | Sets the resting status dot + text: *Warming up… / Ready — hold Fn to talk / Microphone needed*. **This is what replaces the UI checking `model.isLoaded`.** |
| **`onState`** | "Where are we in *this one dictation*?" | During a dictation — `idle → listening → working → inserted/failed → idle` | Drives the HUD phase + glyph color (record pill / Finishing / Inserted). |
| **`onLiveTranscript`** | "What words do we have *so far*?" | Repeatedly while `listening` | Renders the growing pill text. Display-only; the authoritative text arrives via `onFinished`. |

The clean split: **`onReady` = engine capability (between dictations). `onState` = this dictation's progress. `onLiveTranscript` = the words during it.** Model-loading collapses into `onReady` ("not ready → engine emits nothing / 'Warming up…'") and never reaches the HUD as a model concept.

---

Two things I'd like your read on before any code:
- **Do you want capture as its own named sub-facade (`VoiceRecorder`)**, or just an internal step of the `DictationEngine`? (Both keep the store-between-capture-and-transcribe seam.)
- Does the **onReady / onState** split above match how you think about it, or would you rather fold "idle/ready" into one signal?
