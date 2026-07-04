# BUG: transcription output repeats the same sentence 3–4×

**Filed:** 2026-07-04 · **Reporter:** Bijal (dogfooding) · **Status:** ✅ FIXED 2026-07-05 (branch `feat/dictation-engine-facade`) · **Severity:** High (corrupts output text)
**Component:** dictation / JustTalk · streaming per-sentence cleanup

## Resolution (2026-07-05)

Fixed by making streaming cleanup a **single pass at stop** with no rolling context — `StreamingDictationSession.ingest` now only assembles raw STT; `finish()` runs one `cleanup.clean(...)` over the whole transcript; `FoundationModelsCleanup.taskPrompt` no longer takes/embeds `priorContext`. The echo has nothing to compound.
- **Pinning tests** (`Shared/Tests/DictationCoreTests/StreamingDictationTests.swift`): `testAssemblesRawThenCleansOnceAtFinish`, `testNeverPassesPriorContext`, `testNoCleanupDuringSpeechOnlyAtFinish`.
- **Verified end-to-end** on real 60s/180s audio via the bench harness — zero duplicated sentences.
- **Related fix (same effort):** the disjoint-segment "middle dropped" path — a thrown mid-recording segment now flags `StreamingResult.incomplete` and forces a full-audio re-transcribe (regression test `testThrownSegmentMarksResultIncomplete`) instead of pasting a truncated result.

## ROOT CAUSE (confirmed by code read, streaming ON)

**Incremental cleanup feeds the previous cleaned sentence as `priorContext` into the on-device model, relying on a *negative* instruction it doesn't reliably obey → the model echoes that preceding text into its output, and the echoed output is then stored as the next `priorContext`, so it snowballs into 3–4× repeats.**

Chain:
- `StreamingDictationSession.cleanAndAppend` passes `priorContext` (the prior cleaned sentence) into `CleanupRequest` (`StreamingDictationSession.swift:104`), then sets `priorContext = text` to the **full cleaned output** (`:109`).
- `FoundationModelsCleanup.taskPrompt` embeds it as a "Preceding text … do NOT repeat it" block (`FoundationModelsCleanup.swift:123-139`).
- Small on-device models are unreliable at negation ("do NOT repeat") — this is the **same echo failure mode the file already documents and fought for vocab** (`FoundationModelsCleanup.swift:64-69`). The model copies the context block into its answer.
- Because `priorContext` is then reassigned to that echoed output (`:109`), each sentence's context carries the prior echo → **geometric repetition** → one early sentence appears 3–4× by the end. Matches the symptom exactly.

**Why streaming-only:** batch cleanup runs with `priorContext = ""` (default) — no context block, no echo. This is why it only appears with streaming ON.

## Fix options (not yet applied — audit discipline)

1. **Drop `priorContext` entirely — clean each sentence in isolation.** Simplest, removes the whole failure class. Cross-sentence continuity is low value for a punctuation/filler pass; the streaming-latency report already prescribes a fresh warm session per sentence. **Recommended.**
2. Keep context but **deterministically strip a prior-context prefix** from the output (belt-and-suspenders, like the vocab-echo lesson).
3. Detect prefix-echo in `CleanupOutputSanitizer.isDegenerate` and fall back.

Recommend #1 (delete the context block + the `priorContext` field), optionally #2 as a guard.

---

### Original triage (kept for record)

## Symptom

A dictation produced output where **the same sentence is repeated 3 or 4 times**. First observed occurrence. Cause unknown. Raw audio is (per the new capture design intent) the source of truth for reproduction — check the retained `.wav` + telemetry `rawText` vs `transcriptText`.

## First triage question (bisects it instantly)

**Was streaming ON when this happened?** (`streamingEnabled`, Settings → default `false`, `AppState.swift:87,237`.)
- Streaming ON → almost certainly the streaming accumulation/ingest path below.
- Streaming OFF (batch) → different bug; batch calls `performTranscription` once, so a repeat would point at WhisperKit long-audio windowing or the preview loop leaking into the final text.

## Suspects (streaming path) — file:line, NOT yet confirmed

1. **Double-ingest of the tail at stop (race).** `stopStreaming()` captures `flushed = streamFlushedCount` (`AppState.swift:599`), then awaits the in-flight `prev` pump (`:606`) and re-ingests `all[flushed..<all.count]` (`:607-608`). If the last in-flight `flushStreamingSegment` task advanced the session's internal confirmed text for a segment that overlaps `[flushed..<all.count]`, the tail gets transcribed twice → trailing sentence duplicated. Count-based dedup (`streamFlushedCount`) guards buffer slices but not the session's internal confirmed-text assembly.

2. **`confirmedText` accumulation in `StreamingDictationSession`.** The HUD shows `[session.confirmedText, session.partialText]` joined (`AppState.swift:577-579`), and `finish()` returns `result.cleanedText` (`:610,618`). If the session **appends each `ingest()`'s full decode to `confirmedText` instead of only the newly-agreed delta** (LocalAgreement should commit only words that agree across overlapping windows), overlapping VAD segments re-emit the same words → N repeats. *This is the most likely single cause and lives in the session file (not shown here — `Shared/.../StreamingDictationSession`).* Needs read.

3. **Per-sentence cleanup re-emitting context.** If incremental cleanup passes prior cleaned sentences as context AND the model echoes the context back into its output (the small on-device model's known "converse instead of transform" failure mode we already fought), a sentence could be re-emitted each cleanup turn. Check whether `rawText` already repeats (STT-side) or only `cleanedText` repeats (cleanup-side) — that isolates suspect 2 vs 3.

## Decisive diagnostic (next occurrence)

Compare the three artifacts for the bad dictation:
- retained raw `.wav` (what was actually said),
- telemetry `rawText` (STT output, `TranscriptRecord.rawText`),
- telemetry `transcriptText` (final cleaned).

Mapping:
- `rawText` already repeats → STT/streaming accumulation (suspect 1 or 2).
- `rawText` clean, `transcriptText` repeats → cleanup echo (suspect 3).

## Notes / relation to in-flight design

- This is **streaming-specific behavior** and reinforces the capture/engine rewrite already designed (`dev/capture-vs-engine-boundary.md`): with the file-as-interface, streaming becomes an engine-internal policy on one input, and confirmed-text assembly gets one owner + is unit-testable against a `.wav` fixture — which is exactly how this class of repeat bug should be pinned.
- **No fix applied** (audit discipline, CLAUDE.md §11). Recommend: read `StreamingDictationSession` confirmed-text logic first (suspect 2), then reproduce against the saved audio.
- Quick user mitigation until fixed: **Settings → streaming OFF** (batch path) if repeats recur.
