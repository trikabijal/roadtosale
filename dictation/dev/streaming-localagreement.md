# Proper streaming STT — LocalAgreement-2 + WhisperKit `AudioStreamTranscriber`

Reference for the next effort: reclaim streaming's speed **without** the word-drops/hallucinations
that our hand-rolled chunk-and-concatenate produced. Current committed state (`c6f405f`) uses a
correct **batch** output at stop; this doc is the plan to make it correct **and** fast.

## Why our first streaming attempt failed

Whisper transcribes a **fixed window of audio → text** in one shot. Two naive ways to make it "live",
both broken:

- **Disjoint chunks** (what we built): cut audio into pieces, transcribe each alone, glue the text.
  → boundary words dropped, short pieces **hallucinate** ("random words"). Fast, wrong.
- **Re-transcribe the whole growing buffer every second:** the **tail keeps changing** as Whisper
  revises the last words with more context — you can't safely show or commit them. Correct-ish but
  unstable and expensive.

The missing piece both lack: **knowing which words are STABLE (safe to finalize) vs still in flux.**

## LocalAgreement-2 — the idea

> A word is only **committed** once it appears **identically in 2 consecutive transcription passes.**

As audio streams in:
1. Every ~1s, transcribe the audio-so-far (full left-context, sliding window).
2. Compare this pass's text to the previous pass's.
3. The **longest agreed prefix** beyond what's already committed is now stable → **commit it.**
4. Trailing words that don't yet agree = **hypothesis** — held tentatively, not final.
5. More audio arrives → the hypothesis stabilizes → gets committed next round.
6. At stop, commit whatever's left.

**Why it fixes everything:**
- **No boundary drops** — every pass has full left-context, never a blind isolated chunk.
- **No short-segment hallucination** — always transcribing a meaningful window, not a 0.5s scrap.
- **Live + stable** — committed words are shown/kept and won't change under you (the "agree twice"
  guarantee).
- **Low post-stop wait** — at stop almost everything is already committed; only the last fragment
  remains. That is the speed win, done correctly.

Analogy: don't ink a word until you've heard enough after it to be sure it won't change. Heard it the
same way twice → ink it.

## `AudioStreamTranscriber` — WhisperKit's built-in version

We don't hand-roll the above. WhisperKit ships `AudioStreamTranscriber`, which:
- takes a **continuous audio stream** (we feed it `RecordingEngine`'s 16k buffers),
- runs the model on a **sliding window** internally,
- applies the confirmation logic (LocalAgreement / eager mode),
- emits a callback with **`confirmedSegments` + `unconfirmedSegments`** as it goes.

Wiring:
- **`confirmedSegments`** → authoritative output text (assembled progressively) → cleaned once at
  stop → pasted. **Accurate.**
- **`unconfirmedSegments`** (hypothesis) → the live pill (tentative, fine to be rough).

This replaces `StreamingDictationSession`'s chop-and-concatenate entirely.

## How it lands in our architecture

- New `StreamingSpeechTranscriber` behind the existing `SpeechTranscriber` contract, wrapping
  `AudioStreamTranscriber`, emitting `onConfirmed` / `onHypothesis`.
- Engine consumes: confirmed → output; hypothesis → pill.
- Cleanup still runs **once at stop** on the full confirmed text (keeps the repeat-bug fix).
- **Fallback:** if streaming errors / is unavailable → the batch path we just committed. Always a
  correct floor.

## Cost / tradeoff

- More model runs during speech (overlapping windows) → a bit more compute/battery than batch.
- ~1% WER vs batch (negligible).
- In exchange: correct **and** fast **and** a real live pill.

## Decisions to make when building

1. WhisperKit confirmation params (window size, agreement count / eager mode) — tune latency vs
   stability.
2. Trust `confirmedSegments` as final, or do a light final pass at stop for safety (tradeoff).
3. Keep batch as the fallback **and** the short-clip path — streaming overhead isn't worth it for a
   3-word clip.

## Next step

Spec this into a short PRD/plan: `AudioStreamTranscriber` integration, the `SpeechTranscriber`
contract change, confirmed/hypothesis wiring into the engine + pill, fallback + short-clip routing,
and a test plan. Then build. The batch output stays as the safe default throughout.
