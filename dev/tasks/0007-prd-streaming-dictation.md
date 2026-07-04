# PRD 0007 — Streaming dictation pipeline (per-segment STT + incremental cleanup)

**Status:** in progress (autonomous build, 2026-07-04)
**Branch:** `feat/streaming-dictation`
**Motivation:** benchmark `dictation/dev/streaming-latency-report.md` — post-stop wait grows with clip length (3-min ≈ ~18s: batch STT ~11s + LLM cleanup ~7s). Streaming collapses it to ~1–1.5s regardless of length.

## Goal

Transcribe and clean **while the user speaks**, so at stop only the final segment + final sentence remain. Reuse the existing VAD (`RecordingEngine.recordingEngineDidDetectSilence`) to flush segments.

## Non-goals (this pass)

- Full WhisperKit `AudioStreamTranscriber` + LocalAgreement-2. This pass uses **VAD-segmented batch transcription** (transcribe each silence-delimited segment via the existing `SpeechTranscriber.transcribe`) — simpler, reuses tested code, delivers most of the win. LocalAgreement is a follow-up.
- HUD live-partial display polish (basic confirmed-text update only).

## Design

Default OFF, behind a `streamingEnabled` flag (Settings → "Streaming (beta)"). The existing batch path is untouched and remains the default, so this can't regress working dictation.

Pieces (all in `DictationCore`, pure/testable where possible):

1. **`SentenceBuffer`** — accumulates transcribed text fragments, emits *completed* sentences (split on `.!?` + space/end), holds the trailing partial. Max-length flush guard (~40 words) for runaway sentences. Pure logic, unit-tested.
2. **`CleanupRequest.priorContext`** (additive field) — the previously cleaned sentence, passed to the cleanup model as read-only rolling context. `FoundationModelsCleanup` injects it into the prompt; a fresh **prewarmed** session per sentence avoids the context-accumulation latency ramp measured in the report.
3. **`StreamingDictationSession`** — orchestrator: `ingest(segmentBuffers)` → transcribe → `SentenceBuffer` → clean each completed sentence (rolling context) → append to ordered assembled text. `finish()` → flush partial + last sentence → return final text. Unit-tested against mock STT + mock cleanup, asserting (a) correct assembled order and (b) post-stop work is only the tail.
4. **AppState integration** — when `streamingEnabled`: on `recordingEngineDidDetectSilence`, snapshot buffers-since-last-flush and `ingest` them; at stop, `finish()` and paste the assembled result. When off: current batch path.

## Acceptance

- Batch path unchanged when flag off (default).
- Unit tests: `SentenceBuffer` boundary/partial/runaway; `StreamingDictationSession` assembly order + tail-only post-stop.
- App builds; `DictationCore` tests pass.
- **Live mic behavior is user-verified** (can't be automated here) — ship behind the beta flag for evaluation.

## Verified vs deferred

- Verified autonomously: compile, unit tests, assembly correctness, no regression to batch path.
- Deferred to user: real-mic latency, seam quality, HUD feel. Tune `silenceDurationMs`, sentence max-length, rolling-context size after live use.
