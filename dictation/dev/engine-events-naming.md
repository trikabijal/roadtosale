# DictationEngine — event & state naming (final spec before implementation)

## The problem with `onState`

`onState` was a placeholder from my first facade sketch — no deeper meaning. It's bad for two reasons: "state" is generic (says nothing about *which* state), and it collides with SwiftUI `@State`. Worse, it lumped **two different questions** under one name:

- "Can the engine work at all right now?" (capability — between dictations)
- "What is this one dictation doing?" (progress — during a dictation)

Those are different axes. Keep them as **two small, clearly-named enums**, not one big one. (A single merged enum — `warmingUp | ready | capturing | finishing | inserted | failed | blocked` — was tempting but it re-mixes capability and progress, which is exactly the confusion we're removing. Rejected.)

## Two state machines, renamed

### 1. `EngineAvailability` — capability (changes rarely; between dictations)

Answers: "is the engine *able* to start a dictation?"

| Case | Meaning | HUD resting state |
|---|---|---|
| `.warmingUp` | model loading | amber dot · "Warming up…" |
| `.ready` | good to go | green dot · "Ready — hold Fn to talk" |
| `.blocked(Reason)` | can't run | red dot · reason text |

`Reason = .microphoneDenied · .accessibilityDenied · .modelUnavailable`

**This is what replaces the UI ever reading `model.isLoaded`.** Model-not-loaded becomes `.warmingUp` — a capability signal, never a model reference in the HUD.

### 2. `DictationPhase` — progress (per dictation; renamed from `onState`)

Answers: "what is *this* dictation doing right now?" "Phase" reads clearly and doesn't collide with `@State`.

| Case | Meaning | HUD |
|---|---|---|
| `.idle` | nothing active (HUD shows availability text) | resting pill |
| `.capturing` | mic hot, recording + live partial text streaming in | record pill + living waveform |
| `.finishing` | hotkey released; final transcribe + clean + paste running | "Finishing…" |
| `.inserted` | text pasted | brief green "Inserted" → back to `.idle` |
| `.failed(Reason)` | something broke; raw audio safe on disk | failed pill + Retry / ✕ |

Note `.idle` (phase) + `.ready` (availability) coexist = "ready and waiting." Different questions, both true. Resting HUD text is driven by **availability**; active HUD visuals by **phase**. Not folded — deliberately.

## The full renamed event surface

Keep the `on…` prefix (standard event-subscription convention) but make each self-describing. Note **audio level comes from `CaptureService`, not the engine** (decided in the capture/engine split) — so it is NOT an engine event.

| Old | New | Payload | Fires |
|---|---|---|---|
| `onReady` | `onAvailability` | `EngineAvailability` | launch, permission change, settings reload |
| `onState` | `onPhase` | `DictationPhase` | per-dictation transitions |
| `onLiveTranscript` | `onPartialText` | `String` (provisional) | repeatedly while `.capturing` |
| `onFinal` | `onFinalText` | `String` (authoritative) | once, at `.finishing → .inserted` |
| `onLevel` | *(moves out)* → `CaptureService.onAudioLevel` | `Float 0…1` | while capturing |

### Swift shape

```swift
enum EngineAvailability: Equatable {
    case warmingUp
    case ready
    case blocked(BlockReason)
    enum BlockReason { case microphoneDenied, accessibilityDenied, modelUnavailable }
}

enum DictationPhase: Equatable {
    case idle
    case capturing
    case finishing
    case inserted
    case failed(FailReason)
    enum FailReason { case transcription, cleanup, paste, noSpeech }
}

protocol DictationEngineDelegate: AnyObject {
    func engine(_ e: DictationEngine, availabilityDidChange a: EngineAvailability)
    func engine(_ e: DictationEngine, phaseDidChange p: DictationPhase)
    func engine(_ e: DictationEngine, didEmitPartialText text: String)
    func engine(_ e: DictationEngine, didFinishWithText text: String)
}

// CaptureService is separate; the HUD waveform binds to this, not the engine:
protocol CaptureServiceDelegate: AnyObject {
    func capture(_ c: CaptureService, audioLevelDidChange level: Float)   // 0…1
}
```

## Naming summary (one line each)

- **`EngineAvailability`** = can it work? (warmingUp / ready / blocked)
- **`DictationPhase`** = what's this dictation doing? (idle / capturing / finishing / inserted / failed)
- **`onPartialText`** = words so far, provisional.
- **`onFinalText`** = the authoritative result.
- **`onAudioLevel`** = mic amplitude for the waveform — from CaptureService, not the engine.

That's the vocabulary. Implementation starts from these.
