# PRD 0008 — Per-word roll-up pill (LocalAgreement streaming STT for the live HUD)

**Status:** in progress (2026-07-05)
**Branch:** `feat/rollup-pill-streaming`
**Supersedes:** the deferred items in [0007-prd-streaming-dictation.md](0007-prd-streaming-dictation.md) ("Full WhisperKit `AudioStreamTranscriber` + LocalAgreement-2 … a follow-up").

---

## 0. Prior-Art Findings (research gate — see `~/.claude/workflows/create-prd.md`)

The user's ask — *"every word I say grows the pill; it grows until it hits the pill's max width, then the oldest words drop off the front and newer words keep showing"* — decomposes into two well-trodden problems. Neither is novel.

**Standard names.**
- The UI is the broadcast-standard **roll-up caption** (CEA-608/708; [W3C RollupCaptions](https://www.w3.org/community/texttracks/wiki/RollupCaptions), [3Play Media](https://www.3playmedia.com/blog/roll-up-vs-pop-on-captions-whats-difference/)): newest text enters, oldest scrolls off when the window is full. Used by every live-caption system (Google/Samsung Live Caption, TV news).
- The per-word data cadence is **streaming / online ASR** with **LocalAgreement-2** (Macháček et al., 2023): a word is committed once it appears identically in two consecutive passes.

**What already exists — in a dep we already ship (WhisperKit):**
| Need | Ships in WhisperKit | File |
|---|---|---|
| Live confirmed/hypothesis stream + LocalAgreement confirm logic | `AudioStreamTranscriber` (`confirmedSegments`/`unconfirmedSegments`, `requiredSegmentsForConfirmation`, `clipTimestamps=[lastConfirmedEnd]`) | `AudioStreamTranscriber.swift:14-15,159-201` |
| Per-word timestamps | `WordTiming`, `TranscriptionSegment.words` | `Models.swift:608,644` |

**Procure-vs-build decision — the honest nuance the research surfaced:**
- `AudioStreamTranscriber` **owns the microphone itself** (`audioProcessor.startRecordingLive`, pulls `audioProcessor.audioSamples` — `:80,:125`). It cannot accept externally-fed buffers. Dropping it in would bypass our `RecordingEngine`, losing durable capture (`CapturedAudioStream`), retry/persistence, level metering, gain-normalization, and VAD. **Unacceptable.**
- Therefore: **procure the ALGORITHM, not the class.** We lift the ~30-line confirmed/unconfirmed split + `clipTimestamps` windowing (a settled algorithm, not a guess) and run it over buffers fed from our own `RecordingEngine`, reusing the already-loaded `WhisperKit` instance. This is not reinvention — it is the exact WhisperKit logic, decoupled from its mic ownership.
- **Layer B (roll-up render): BUILD, small.** It is app-specific SwiftUI and ~70% already exists — `RecordingHUD.swift:248-255` already does `lineLimit(1)` + `truncationMode(.head)` + `maxWidth`. We polish it (smooth growth, confirmed-vs-hypothesis styling), we do not design it fresh.

**Alternative considered (documented, not chosen):** Apple's `SFSpeechRecognizer` streams per-word partial hypotheses cheaply as a system service (outside our Neural-Engine budget) and is already a contract-ready-but-unimplemented provider (`STTProvider.appleSpeech`). It would be a lighter source for the *live pill* specifically. **Not chosen here** because it means implementing a second STT engine (separate unbuilt provider) and running two engines; we keep a single-engine design. Revisit if the WhisperKit streaming tick proves too heavy on live mic.

**Known pitfalls others already paid for (honored in the design):**
1. **Whisper revises the tail** — never "ink" unconfirmed words. Show `confirmed` solid, `hypothesis` dimmed. (This is the roll-up "captioner correction" case the W3C doc calls out; maps 1:1 to confirmed/unconfirmed.)
2. **Two models starve the Neural Engine and drop mic buffers** (our own audit F12 / architecture note — the deleted tiny-preview model). Mitigation: reuse the **single** loaded model; bound each pass with `clipTimestamps` so we only re-decode from the last confirmed point; run passes on a throttled tick, not per-buffer; and rely on `CapturedAudioStream`'s audio-thread-decoupled append so capture can't be starved.
3. **Reduced-motion** — the roll-up scroll animation must respect `accessibilityReduceMotion` (already a project design constraint).

---

## 1. Introduction / Overview

The live HUD pill today grows **per VAD segment** (per speech pause) using disjoint per-segment transcription. Between pauses it doesn't move, and disjoint segments drop boundary words / hallucinate on short scraps — tolerable only because the pill is *preview* and the pasted text comes from a separate accurate batch pass.

This feature makes the pill grow **per word**, as a smooth horizontal single-line **roll-up**: words stream in from the right, and once the text hits the pill's max width the oldest words scroll off the left. It does this by adding a **LocalAgreement streaming transcriber** (procured algorithm, our audio source, single model) that emits a stable **confirmed** prefix plus a tentative **hypothesis** tail.

## 2. Goals

1. The live pill grows visibly **as words are recognized**, not only on pauses.
2. When the text exceeds the pill's width cap, the oldest words drop off the front and the newest stay visible (horizontal single-line roll-up).
3. Confirmed words render solid; the tentative tail renders dimmed and may still change — never a jarring rewrite of already-solid text.
4. **Zero regression to pasted-text correctness**: the final pasted text remains the existing accurate full-audio batch pass. Streaming is the pill only, this pass.
5. No mic-buffer drops or keyboard-freeze under the added streaming load (verified on live mic).

## 3. User Stories

- *As a fast dictator,* I want to see my words appear one-by-one as I speak, so I trust the app is keeping up and can catch a misrecognition early.
- *As a user dictating a long sentence without pausing,* I want the pill to keep moving, so it never looks frozen.
- *As a user,* I want the pill to stay a calm, fixed-size capsule — old words gracefully leaving, not an ever-growing bar or a jarring text swap.

## 4. Functional Requirements

1. A new **`StreamingTranscriber`** contract in DictationCore exposes: feed audio, run one incremental pass, read current `confirmed` + `hypothesis` text, finish, reset.
2. A pure, model-free **`StreamingAgreement`** value type implements the LocalAgreement confirmed/unconfirmed split (`requiredSegmentsForConfirmation`, advance `lastConfirmedEnd`), lifted from `AudioStreamTranscriber`'s logic. **Unit-tested** deterministically with synthetic segments.
3. **`WhisperKitTranscriber`** gains a streaming session that reuses its already-loaded `WhisperKit` instance (no second model load), decoding with `clipTimestamps=[lastConfirmedEnd]` and the existing vocab-bias prompt, feeding fresh segments into `StreamingAgreement`.
4. During recording, `AppState` drives the streaming transcriber on a **throttled tick** (~0.6–1.0 s) over audio-so-far, and updates the HUD with `confirmed` (solid) + `hypothesis` (dim).
5. The HUD renders a **horizontal single-line roll-up**: `confirmed + hypothesis` on one line, `lineLimit(1)`, head-truncated to the width cap, growth animated, hypothesis dimmed; honors reduced-motion.
6. **Final pasted text is unchanged** — still `performTranscription` full-audio batch at stop. The streaming session is torn down at stop; its text is not pasted.
7. Behind a flag (`streamingPillEnabled`, default ON) with a **clean fallback**: if the streaming transcriber errors or is unavailable, the pill falls back to the current per-segment preview. Batch final output is untouched either way.
8. Capture integrity preserved: streaming passes must not block the audio render thread nor drop buffers (reuse `CapturedAudioStream`; passes run off the capture path).

## 5. Non-Goals (Out of Scope)

- **Using streaming `confirmed` text as the final pasted output** (the post-stop latency win from `dictation/dev/streaming-localagreement.md`). Designed-for but deferred to a follow-up PRD; batch stays authoritative here.
- Vertical multi-line TV-style roll-up (user chose horizontal single-line).
- Implementing the `appleSpeech` provider.
- iOS (macOS pill only; the contract is shared so iOS can adopt later).
- Per-word karaoke highlighting / word-level timestamps in the UI (the confirmed/hypothesis split is segment-granular; per-word timing is available but not surfaced this pass).

## 6. Design Considerations

- Confirmed = `Theme.Palette.textPrimary`; hypothesis = `textPrimary.opacity(~0.5)` (or `textTertiary`). Single `Text` with an `AttributedString` so head-truncation spans both.
- Keep the 360×60 pill and its content-hugging capsule; only the text run changes. Width cap stays ~300pt (tunable).
- Growth animation: ease-out on text change (already present); ensure it reads as "scrolling in", not "swapping".

## 7. Technical Considerations

- `StreamingAgreement` is pure `Sendable` value logic → fast unit tests, no model.
- The WhisperKit streaming session is `@MainActor` like the rest of the transcriber; the decode runs on WhisperKit's own queue (async), so the main actor suspends, not blocks.
- Throttle: skip a tick if the previous pass is still running (non-reentrant, like the existing `streamPump`).
- Cost control: `clipTimestamps` bounds each decode to audio after `lastConfirmedEnd`; a max-unconfirmed-window guard prevents unbounded re-decode on a long pause-free utterance.

## 8. Success Metrics

- Pill updates ≥1×/second while speaking continuously (not only on pauses).
- No increase in mic-buffer drops vs batch (capture-metrics CSV ratio stays ≈1.0) on a 60 s continuous-speech live test.
- Global typing stays responsive during dictation (no tap freeze) under the added load.
- Pasted-text output byte-identical to the pre-change batch path on the same fixtures.

## 9. Open Questions

- Tick cadence (0.6 s vs 1.0 s) and `requiredSegmentsForConfirmation` (1 vs 2) — tune on live mic for latency-vs-stability.
- Does `clipTimestamps` on `largeV3Turbo` behave as expected for the fed-buffer path? (Verify on device; batch remains the floor if not.)
- Default the flag ON, or ship OFF for a self-test week first? (Leaning ON since the pill is preview-only and batch output is untouched.)

## 10. Acceptance

- `StreamingAgreement` unit tests: prefix confirmation, hypothesis tail, `lastConfirmedEnd` monotonicity, no-regression when a later pass revises the tail.
- DictationCore builds; existing tests stay green; `./test.sh core` passes.
- App builds (`./build.sh`).
- **Live-mic behavior user-verified** (cannot be automated here): per-word growth, roll-up eviction, confirmed/hypothesis styling, no freeze, no buffer drop. Ship behind the flag for evaluation.

## 11. Verified vs Deferred

- **Verified here:** compile, `StreamingAgreement` unit tests, no regression to batch output/tests.
- **Deferred to user (live mic):** cadence/stability tuning, roll-up feel, capture-integrity under load. Then decide on the follow-up: promote confirmed streaming text to the final pasted output for the post-stop speed win.
