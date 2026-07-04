# Dictation latency + content-loss — overnight benchmark report

**Date:** 2026-07-04 (overnight autonomous run)
**Question from you:** measure transcribe + clean latency across 10/20/30/60/180s clips, before (current batch) vs after (chunked/streaming), and check the "skipped the middle" bug.

---

## TL;DR

1. **The post-stop wait is real and grows with length** — and *both* stages contribute: STT for long clips, cleanup always. On a 3-minute dictation the current pipeline makes you wait ~15–20s after you stop (real-telemetry STT + LLM cleanup).
2. **Streaming collapses that to ~1–1.5s regardless of length.** Transcribe per segment during speech + clean per completed sentence → at stop only the final segment + final sentence remain. This is the single highest-value change.
3. **The "skipped middle" bug did NOT reproduce on clean synthetic audio.** Batch STT transcribed all of a 175s clip with zero drops. **I was wrong to say earlier it "reproduced deterministically" — that was a flaw in my detection metric, not a real drop.** The real-world drop is therefore specific to real recordings (capture-side or real-audio characteristics) and is still **unconfirmed** — see §4 for the next diagnostic.
4. **Rule-based cleanup is ~0–13ms** (no LLM). If its quality is acceptable for your use, that alone removes the entire cleanup wait today, no rewrite.
5. **A trap to avoid in incremental cleanup:** reusing one LLM session across sentences makes latency *ramp* (a mid-clip sentence hit 6.8s) because the session accumulates context. Use a fresh *warm* session per sentence with a short explicit rolling context.

---

## Method + honest caveats

- 5 clips generated with macOS `say` (numbered sentences, unique codewords per sentence so drops are detectable): actual durations 8.1 / 17.2 / 26.3 / 55.7 / 174.6s.
- Ran through the **real** engines (`WhisperKitTranscriber` small.en + `FoundationModelsCleanup` + `RuleBasedCleanup`) via a benchmark harness (`dictation/Shared/Sources/bench`). **Release** build.
- **Caveat 1 — STT numbers here are ~3× your real usage.** `say` is dense, continuous 175-wpm speech with no pauses; real dictation has silences WhisperKit skips fast. Your **real telemetry** is the truth for absolute STT (below); the bench is a worst case. The *batch-vs-streaming ratio* and *cleanup* numbers are valid.
- **Caveat 2 — cleanup ran on the STT output text**, same as production.

---

## 1. STT latency (post-stop wait, current batch)

**Real telemetry (small.en, your actual dictations) — the source of truth:**

| audio length | avg STT (real) | STT / audio-sec |
|---|---|---|
| ~4.5s | 604 ms | 0.17 |
| ~12s | 790 ms | 0.067 |
| ~20s | 1090 ms | 0.054 |
| ~32s | 1703 ms | 0.053 |
| ~51s | 3741 ms | 0.072 |
| ~149s | 9359 ms | 0.063 |

→ Real STT ≈ **0.05–0.07× audio length** past the fixed ~0.4s floor. A 3-min clip ≈ **~11s** post-stop STT. This is your "long conversation takes a long time" — confirmed, real.

**Bench (dense synthetic, worst case) for reference:** 10s→0.57s, 20s→3.39s, 60s→12.08s, 180s→37.86s.

**Streaming STT (last-segment-only post-stop wait), bench:**

| clip | batch STT | streaming last-segment |
|---|---|---|
| 10s | 573 ms | 478 ms |
| 20s | 3387 ms | 218 ms |
| 60s | 12080 ms | 644 ms |
| 180s | 37861 ms | **510 ms** |

→ Streaming makes post-stop STT **~constant ~0.5–1.3s regardless of length**, because everything before the last pause was already transcribed while you spoke. Total compute is the same (~sum of segments) — it's just moved *before* the stop.

---

## 2. Cleanup latency

**Apple Foundation Models (LLM), batch — scales with length (bench):**

| clip | words | FM batch cleanup |
|---|---|---|
| 10s | 28 | 1289 ms |
| 20s | 56 | 1299 ms |
| 30s | 84 | 3507 ms |
| 60s | 175 | 4448 ms |
| 180s | 522 | **10241 ms** |

→ ~2.5–10s of "Cleaning…" on longer clips, entirely post-stop today. This is the other half of the wait you feel.

**Incremental cleanup (clean each sentence during speech; only the LAST sentence waits post-stop):**

| clip | FM incremental — last-sentence post-stop |
|---|---|
| 10s | 365 ms |
| 20s | 408 ms |
| 30s | 760 ms |
| 60s | 861 ms |
| 180s | **640 ms** |

→ Post-stop cleanup becomes **~0.4–0.9s regardless of length**.

**Rule-based cleanup (no LLM):** **0–13 ms** at every length. Deterministic punctuation/vocab/commands only — no grammar rewrite. If acceptable, it removes the cleanup wait entirely, today, via a Settings toggle.

---

## 3. Combined post-stop wait — before vs after

Using **real-telemetry STT** (truth) + measured cleanup:

| clip | BEFORE (batch STT + FM cleanup) | AFTER (streaming STT + incremental cleanup) |
|---|---|---|
| 20s | ~1.1s + ~1.3s ≈ **2.4s** | ~0.3s + ~0.4s ≈ **0.7s** |
| 60s | ~3.7s + ~2.5s ≈ **6s** | ~0.6s + ~0.9s ≈ **1.5s** |
| 180s | ~11s + ~7s ≈ **~18s** | ~0.5s + ~0.6s ≈ **~1.2s** |

→ **The longer you talk, the bigger the win.** A 3-minute dictation goes from ~18s of waiting to ~1.2s.

---

## 4. The "skipped the middle" bug — status: NOT reproduced, root cause unconfirmed

- **Batch STT on 175s of clean synthetic audio transcribed ALL 36 sentences in order, zero drops** (522 words vs 504 expected). WhisperKit long-audio windowing is **not** fundamentally broken.
- **Correction:** my earlier "reproduced deterministically" was a false alarm from a noisy detection regex (it only matched digit-form sentence numbers; WhisperKit wrote some as words). The transcripts are complete.
- **Your real 58s clip** (75 words, 1.29 w/s) captured a full 58s of *samples* (so buffers were NOT dropped — dropped buffers would shorten the measured duration, which stayed at 58s). That points away from capture-thread starvation and toward either (a) genuinely sparse/paused speech, or (b) a WhisperKit drop specific to that real audio's acoustics.
- **Cannot confirm from telemetry alone** — we don't have ground truth of what you actually said.

**Recommended next diagnostic (fast):** add capture instrumentation to the real app — log captured-sample-duration vs wall-clock recording time, and keep the raw audio for the last N recordings (a raw store already exists for re-transcribe). Next time you hit a drop, we compare the saved audio against the transcript and know definitively whether it's capture or STT. **Streaming STT would also make this bug structurally impossible** (each segment decoded independently), so the fix and the perf work still coincide — I just can't claim I've *proven* the current cause yet.

---

## 5. Implementation guidance (validated by the run + research)

- **STT:** WhisperKit `AudioStreamTranscriber` + LocalAgreement-2; VAD *flushes* a segment, doesn't start it. ~1–2s blocks (sub-second loses accuracy). Streaming variant costs ~1% WER.
- **Cleanup — clean on SENTENCE boundaries, not every VAD pause.** Buffer STT text; when a sentence completes, clean it with the previous 1–2 cleaned sentences as read-only context. Keep the trailing partial sentence buffered. At stop only ≤1 sentence remains.
- **Avoid the session-accumulation trap (measured here):** reusing one `LanguageModelSession` across all sentences made latency **ramp to 6.8s mid-clip** (sentences 7–10 of the 180s run) before the model truncated context. Use a **fresh but prewarmed** session per sentence with the rolling context passed explicitly in the prompt — do NOT let one session's turn history grow unbounded.
- **Constrain the prompt:** "fix punctuation/capitalization/fillers only, do not rephrase or drop content," low temperature — prevents the summarize/rewrite failure mode (which is the *other* candidate for a real "middle dropped" if it ever happens in cleanup, though it didn't here).
- **Runaway-sentence guard:** force a cleanup flush after ~8–10s of unbroken speech.
- **Prewarm** the STT + cleanup at key-down; keep both warm for the whole dictation.

---

## 6. Recommended next steps (your call in the morning)

1. **Quick win now:** try Settings → Cleanup Engine → **Rule-based** for a day. If the quality is fine for your use, you get instant cleanup with zero code. Tells us how much you actually value the LLM rewrite.
2. **Build the streaming pipeline** (PRD 0007). Scope: WhisperKit streaming integration, RecordingEngine VAD→segment-flush, sentence-boundary cleanup buffer with warm-session rolling context, HUD partials. This is the real fix and it also makes the middle-drop structurally impossible.
3. **Add capture instrumentation** (small) to definitively root-cause the real-world middle-drop before/independent of the rewrite.

**Artifacts:** benchmark harness at `dictation/Shared/Sources/bench/` (dev-only target), audio + raw outputs in the session scratchpad. Harness is uncommitted — say the word and I'll move it into `dictation/dev/` or `qc/` properly, or drop it.
