# Road to Sale Audio Architecture

## Purpose

This document defines the recommended audio architecture for `Road to Sale by AuditPro`.

It is intentionally short and decision-oriented. The detailed external research and source-by-source notes live in:
- `dev/docs/ROAD_TO_SALE_AUDIO_LEARNINGS.md`

At the system level, this document should be treated as the `voice engine` architecture, not the full Road to Sale system architecture. The broader system split lives in:
- `docs/ROAD_TO_SALE_TECHNICAL_ARCHITECTURE.md`

That learnings document should be read alongside this one during development. It exists to show that the architecture here was not chosen in a vacuum and that we explicitly reviewed parallel products, engineering writeups, and implementation references before choosing this shape.

This document answers one question:

`What should we build, and why is that the right shape for this product?`

## Product Constraints

These are the constraints that matter most:

- This is a `mobile app` for the dealership sales floor.
- The rep should not manually start and stop audio on each step.
- The rep-facing experience must feel near-live.
- Roughly `500 ms` perceived responsiveness is the target for live guidance.
- Full transcript can be slower than the live UI.
- The product must produce defensible audit evidence.
- We do not need perfect live transcription.
- We do need reliable detection of a small set of dealership cues and intents.
- iOS and Android may require different underlying speech engines.
- We should avoid locking the entire product to one speech vendor.

## Architecture Decision

We should build a `two-lane audio architecture` with a `strategy-based speech layer`.

That means:
- one shared mobile capture and orchestration layer
- one fast live lane for rep-facing cues
- one slower evidence lane for audit-grade transcript and event storage
- one pluggable strategy layer for speech engines and related processing

This lets us keep the product behavior consistent while allowing different implementations underneath for:
- iOS vs Android
- native vs vendor STT
- future provider changes

It also keeps the voice stack reusable beyond Road to Sale if later needed by other AuditPro workflows.

## High-Level Blocks

The architecture should have these top-level blocks:

1. `RoadToSaleAudioOrchestrator`
2. `AudioCaptureStrategy`
3. `TranscriptionStrategy`
4. `CueDetectionStrategy`
5. `SummarizationStrategy`
6. `Fast Live Cue Lane`
7. `Evidence Lane`
8. `Decision Layer`
9. `Telemetry Layer`
10. `Audit and Analytics Layer`

## 1. RoadToSaleAudioOrchestrator

This is the coordinating layer inside the app.

It should:
- start and stop session-level audio automatically
- know which deal and workflow step is active
- route audio and transcript events to the right downstream layers
- activate the current cue pack
- keep UI updates, evidence storage, and telemetry aligned

This is the layer that gives the product one consistent behavior even if the underlying strategy changes.

## 2. AudioCaptureStrategy

This layer owns microphone capture on the device.

Responsibilities:
- request microphone permission
- configure the session for speech capture
- keep a rolling buffer
- chunk audio into frames
- expose audio frames to downstream consumers
- handle interruptions, pauses, and resume behavior
- support speech-activity detection or silence gating

Important principle:

Audio capture should be separate from speech recognition.

Why:
- it gives us control over buffering
- it keeps us from over-coupling the app to one SDK
- it allows multiple downstream consumers

## 3. TranscriptionStrategy

This is the key strategy layer.

It allows different speech engines to sit behind the same interface.

Examples:
- Apple native transcription on modern iOS
- Argmax on iOS and/or Android
- another vendor later if needed

This strategy should return a normalized shape, regardless of provider:
- `partial_text`
- `final_text`
- `timestamp`
- `speaker` if available
- `confidence` if available
- `engine_metadata`
- `latency_ms`

This is important because we are likely to test multiple engines across:
- iOS
- Android
- native and vendor options

We should not hardcode one speech stack into the product.

## 4. CueDetectionStrategy

This layer turns transcript output into product events.

V1 should be:
- deterministic
- step-specific
- phrase and synonym driven

Each step should activate a small cue pack, not a giant classifier.

Examples:

`Greet`
- coffee
- water
- anything to drink
- grab you a drink

`Vehicle logic`
- because you mentioned
- best fit
- that’s why I picked this one

`Static feature demo`
- Apple CarPlay
- power tailgate
- heated seats

`Drive feature demo`
- Lane Assist right now
- blind spot warning
- adaptive cruise

Later, if needed, we can add a semantic assist layer for ambiguous cases.
That should not be the default for v1.

## 5. SummarizationStrategy

This should be treated as a separate strategy from transcription.

It is not part of the critical live path.

It will matter later for:
- coaching summaries
- rep recap
- manager summaries
- session notes

It should not drive the first live audio architecture decision.

## 6. Fast Live Cue Lane

This is the layer the rep feels in the moment.

Its purpose is:
- near-live snippet display
- cue detection for the current step
- green/red updates
- light nudges about what has been missed

This lane should use:
- the active `TranscriptionStrategy`
- the current `CueDetectionStrategy`

Design rules:
- optimize for speed
- accept that output may be unstable
- do not wait for full stable transcript
- do not try to understand the whole conversation

## 7. Evidence Lane

This is the audit layer.

Its purpose is:
- stable transcript accumulation
- transcript segments around events
- timestamps
- link between events and transcript evidence
- later review when the system is challenged

This lane can be slower than the live lane.

It should optimize for:
- completeness
- traceability
- defensibility

## 8. Decision Layer

This layer converts detected cues into workflow actions.

Its job is to decide whether to:
- auto-mark a step green
- show a nudge
- wait for more evidence
- require manual confirmation

Suggested policy:
- `high confidence`: auto-mark
- `medium confidence`: soft nudge or confirm
- `low confidence`: do nothing or leave manual

This is where product trust is protected.

## 9. Telemetry Layer

Telemetry is mandatory.

Without it, we will not know:
- which speech strategy is actually better
- whether iOS native beats a vendor path
- where false positives come from
- which cues are weak
- which devices are problematic

Minimum telemetry should include:
- `session_id`
- `deal_id`
- `rep_id`
- `platform`
- `device_model`
- `os_version`
- `audio_capture_strategy`
- `transcription_strategy`
- `cue_detection_strategy`
- `step_id`
- `cue_id`
- `event_time`
- `time_to_first_partial_ms`
- `time_to_cue_detection_ms`
- `partial_text`
- `final_text`
- `confidence`
- `auto_marked`
- `manual_override_used`
- `manual_override_direction`

This is what will let us run real comparisons across strategies instead of arguing from intuition.

## 10. Audit and Analytics Layer

This is the manager-facing persistence and rollup layer.

It should store:
- cue events
- transcript evidence
- timestamps
- confidence
- manual overrides
- step completion state
- coaching gaps

This is also the input layer for the BI dashboard.

## Why This Shape

This architecture builds directly on the strongest external learnings:

- fast and stable outputs should be separate
- audio capture should be separate from recognition
- the speech layer should be swappable
- live guidance and audit evidence are related outputs, not the same output
- platform-specific strengths should be allowed behind one product interface

The detailed reasoning and source references are in:
- `dev/docs/ROAD_TO_SALE_AUDIO_LEARNINGS.md`

If this document is being reviewed by a new engineer, PM, or AI agent, they should read the learnings document as well before proposing major changes to the speech architecture.

## What We Are Explicitly Not Doing In V1

We are not building:
- one giant full-transcript live system that drives everything
- one hardcoded STT provider path for all platforms forever
- an all-LLM live understanding layer from day one
- a summary-first architecture

Those all seem intuitive, but they are the wrong abstractions for this product.

## Likely V1 Direction

The most likely v1 shape is:
- one orchestrator
- one capture layer
- one normalized STT interface
- platform/provider-specific `TranscriptionStrategy` implementations
- deterministic cue packs
- separate live and evidence lanes
- telemetry from day one

Likely candidate strategies to test:
- Apple native transcription on modern iOS
- Argmax on Android
- Argmax on iOS if it materially outperforms or simplifies the native path

That should be decided by telemetry and cue-test results, not assumption.

---

## Platform Strategy Decision (2026-05-24)

Updated based on lab results (10 Honda walkaround videos, 114 cue firings) and literature review. See `dev/docs/ROAD_TO_SALE_AUDIO_RESEARCH_LITERATURE.md` for all paper references.

### iOS

**Primary: Apple SpeechTranscriber** (iOS 26+)  
- TTFT P50: 54 ms, TTFinal P50: 147 ms, RTF: 0.01  
- Utterance-streaming: seals finals at natural pauses, not 30-second walls  
- Free, on-device, Neural Engine accelerated  

**Fallback: WhisperKit** (iOS 17–25, open-source MIT)  
- Uses `AudioStreamTranscriber` with LocalAgreement-2 confirmation (Macháček et al. 2023)  
- `unconfirmedSegments` → partial stream, `confirmedSegments` → final stream  
- `chunkingStrategy: .vad` — cuts on silence midpoints, not fixed windows  
- TTFT P50: 100 ms; finals seal at utterance boundary via built-in EnergyVAD  

**Silence threshold:** 300 ms utterance boundary (IPU), 800 ms–1.2 s end-of-turn (NaturalTurn 2025, Silero docs)

### Android — TO BE IMPLEMENTED

Two tiers. Implement both behind the same `TranscriptionStrategy` interface.

**Tier 1 — Free path: sherpa-onnx + Whisper-tiny-en**  
- Runtime: `k2-fsa/sherpa-onnx` (ONNX, MIT, JVM + Android AAR)  
- Model: `sherpa-onnx-whisper-tiny.en` (~39 MB, downloads from HuggingFace on first run)  
- VAD: Silero VAD v5 (built into sherpa-onnx) — threshold 0.5, tune to 0.6–0.7 for noisy floors  
- Lab CLI: `voice-engine/native/android/SherpaOnnxSTT/` (JVM, same JSONL contract as Apple CLIs)  
- Status: **lab CLI built, lab strategy registered — production Android integration TO BE IMPLEMENTED**

**Tier 2 — Paid path: Argmax Pro SDK** (`argmax-sdk-kotlin`, Google LiteRT)  
- Real-time Parakeet streaming, Nvidia Sortformer speaker attribution, 3,000-keyword custom vocab  
- Same `TranscriptionStrategy` interface, higher accuracy  
- Status: **TO BE IMPLEMENTED — evaluate after Tier 1 is live and telemetry is running**

### Cue Detection (both platforms)

Two-layer matcher behind `CueDetectionStrategy`:
1. **Exact layer** — substring match on every event (partials + finals). Fast, zero false positives. Fires in ~280 ms (one partial window).
2. **Semantic layer** — sentence embedding (BAAI/bge-small-en-v1.5, ONNX) on confirmed finals only. Cosine similarity threshold 0.65 (empirically validated). Catches paraphrases the exact layer misses. Adds ~8 ms per final utterance.

Lab results at threshold 0.65 (Apple + semantic): FNR 8.8%, 0 false positives.  
Lab results at threshold 0.65 (WhisperKit + semantic): FNR 2.6%, 0 false positives.

Full literature references: `dev/docs/ROAD_TO_SALE_AUDIO_RESEARCH_LITERATURE.md`

This recommendation is time-sensitive. Speech infrastructure is evolving quickly. Before making large changes, re-check:
- `dev/docs/ROAD_TO_SALE_AUDIO_LEARNINGS.md`
- publication dates of the cited sources
- whether Apple, Argmax, or Android-native options have materially changed since the last review

---

## Lab-to-iOS Production Gap (2026-05-24)

The lab runs on macOS. iOS production is in-process Swift inside the app. This section documents what is portable and what needs to be built.

### STT Layer — portable, ~90% identical

| Component | Lab (macOS) | iOS Production | Delta |
|---|---|---|---|
| Apple SpeechTranscriber | Same Swift API | Same Swift API | Audio source only |
| WhisperKit AudioStreamTranscriber | Same Swift package | Same Swift package | Audio source only |
| CoreML models | macOS Apple Silicon | iPhone Neural Engine | None — same .mlpackage |

**Audio source** is the only real difference. Lab CLIs feed audio from a file path. iOS production feeds audio from `AVAudioSession` → live microphone tap. The `WhisperKitLiveSTT` binary demonstrates this exact pattern (live mic + `AudioStreamTranscriber`) — that same Swift code compiles and runs on iOS unchanged.

The key thing the lab CLIs cannot test is **real microphone conditions**: background noise, competing voices, Bluetooth audio, interruptions (phone calls, Siri). Those require testing on a real device.

### Matching Layer — NOT portable, needs Swift port

The Python cue matcher does not run on iOS. This is the production gap.

| Component | Lab | iOS Production (TO BE BUILT) | Effort |
|---|---|---|---|
| **Exact matcher** | `voice_lab/matcher/cue_matcher.py` | Swift: `transcript.localizedCaseInsensitiveContains(phrase)` | Trivial (~50 LOC) |
| **Cue pack loading** | `CueAtom` YAML → Python dataclass | `CueAtom` JSON → Swift `Codable` struct | Small |
| **Semantic matcher** | `fastembed` + `bge-small-en-v1.5` (ONNX) | CoreML embedding model + dot-product in Swift | Medium |

#### Semantic matcher — iOS path

```
bge-small-en-v1.5 (HuggingFace ONNX, ~130 MB)
       ↓  coremltools.convert()   [~30-line Python script]
bge-small-en-v1.5.mlpackage  [~90 MB, ships in app bundle]
       ↓  CoreML inference
CueMatcher.swift
  - at app launch: embed all cue phrases → Float32 matrix (one row per cue)
  - per final event: embed transcript text → vector
  - cosine similarity vs each row → fire on any cue above 0.65
```

Neural Engine inference for a 384-dim embedding model is fast — comparable to or faster than the 8 ms measured in the lab.

#### Implementation sequence for iOS production

1. Convert `bge-small-en-v1.5` to CoreML (`coremltools`) — one-time script, output committed to repo
2. Build `CueAtom.swift` + `CuePack.swift` — Swift equivalents of the Python types
3. Build `ExactCueMatcher.swift` — substring matching on partials
4. Build `SemanticCueMatcher.swift` — CoreML embedding + cosine similarity on finals
5. Build `CueDetectionStrategy` protocol + `TwoLayerCueMatcher` implementation
6. Wire `TranscriptionStrategy` output → `CueDetectionStrategy` input inside `RoadToSaleAudioOrchestrator`
7. Test on device with the Honda walkaround cue pack

Steps 1–4 are independent and can be parallelized. Step 6 is the integration point that requires the orchestrator to exist.

## Recommended Implementation Sequence

1. Build the orchestrator and capture layer.
2. Define the normalized transcript/event schema.
3. Implement the `TranscriptionStrategy` interface.
4. Add at least two real strategy implementations for comparison.
5. Build deterministic cue packs for the highest-value steps.
6. Add telemetry before broad testing.
7. Run the audio test matrix in `dev/docs/ROAD_TO_SALE_AUDIO_TEST_MATRIX.md`.
8. Use real data to select platform defaults.
