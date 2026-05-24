# Voice Lab — Data Layout

This document describes the three-layer data model, naming conventions, and the full schema for every file format the lab reads or writes.

---

## The three-layer model

```
Layer 1 — SOURCES    sources/
  Raw inputs. Never auto-generated. Edited by hand or by the ingest CLI.
  Always committed to git.

Layer 2 — DATA       data/
  Generated from sources. Generated once, treated as durable assets.
  Audio is large — will move to S3 when disk space is a concern.
  The transcript cache also lives here.

Layer 3 — RUNS       runs/
  Experiment configuration + results. Always local.
  Generated on demand by `voice-lab run`. Committed to git so results
  are reproducible and reviewable via PR diff.
```

---

## Layer 1 — Sources (`sources/`)

### `sources/scripts/*.yaml` — Hand-written test scripts

```yaml
id: script_001_crv_hybrid_walkaround      # kebab_case, unique across all scripts
title: "Honda CRV Hybrid — positive walkaround"
source:
  type: hand_written

segments:
  - id: greet
    text: >
      Hi, welcome in! Can I grab you a coffee or water while we look around?
      We've got the CRV Hybrid right here — let me walk you through it.
    expected_cues:
      - cue_id: workflow.hospitality_offer
        approx_ms: 2000        # expected detection time (ms from audio start)
      - cue_id: workflow.walkaround_opening
        approx_ms: 5500

negative_cues:
  - workflow.trial_close        # should NOT appear in this script
```

- `id` — used as the script identifier throughout the pipeline
- `segments[].text` — the reference text (spoken as-is in TTS, used as ground truth for caption_stt_agreement_score)
- `expected_cues[].approx_ms` — optional expected detection timestamp. Used in L1 CSV for delta_ms and engine_text_window_* extraction
- `negative_cues` — cue IDs that must not fire; a detection counts as a false positive

### `sources/youtube/youtube_{video_id}.yaml` — Auto-generated from YouTube captions

Same schema as hand-written scripts, with:
```yaml
source:
  type: youtube_transcript
  url: https://www.youtube.com/watch?v={video_id}
  video_id: 2FXQvvp9Blw
  fetched_at: "2026-05-24T07:00:00Z"
  fair_use_note: "Public dealer walkaround used for internal STT evaluation under fair use."
```

Expected cues are auto-populated by phrase-matching captions against the cue pack. They are **caption-relative** — the ground truth is what the dealer actually said (as captured by YouTube auto-captions), not a verified human transcript.

### `sources/manifests/*.yaml` — YouTube manifest

```yaml
sources:
  - video_id: 2FXQvvp9Blw
    title: "Honda CRV Hybrid Walkaround — Dealer Demo"
    url: https://www.youtube.com/watch?v=2FXQvvp9Blw
    target_trim_id: honda.crv_hybrid.2024.ex_l
    notes: "Good example of Honda Sensing demo"
    fair_use_note: "Public dealer walkaround used for internal STT evaluation under fair use."
```

---

## Layer 2 — Data (`data/`)

### Audio (`data/audio/`)

#### Synthesized audio

```
data/audio/synthesized/{script_id}__{voice_id}/
├── clean.wav          16 kHz mono PCM-16 — ElevenLabs TTS output
├── snr15db.wav        white Gaussian noise mixed at SNR +15 dB
├── snr15db_nr.wav     DNS64 denoised from snr15db.wav
├── snr5db.wav         white Gaussian noise mixed at SNR +5 dB
├── snr5db_nr.wav      DNS64 denoised from snr5db.wav
├── snr0db.wav         white Gaussian noise mixed at SNR 0 dB
└── snr0db_nr.wav      DNS64 denoised from snr0db.wav
```

The `{voice_id}` is the ElevenLabs voice profile id from `cue-packs/accent_voices.yaml`:
- `us_baseline_neutral` — standard US accent
- `us_female_professional` — female professional
- `us_male_casual` — casual male
- `us_male_deep` — deep male
- `us_male_fast_proxy` — fast speech

#### YouTube audio

```
data/audio/youtube/{video_id}/
├── clean.wav          16 kHz mono PCM-16 — yt-dlp download + ffmpeg convert
├── snr15db.wav        (same pattern as synthesized)
├── snr15db_nr.wav
├── snr5db.wav
├── snr5db_nr.wav
├── snr0db.wav
└── snr0db_nr.wav
```

#### Audio format requirements

All audio files must be **16 kHz, mono, PCM-16 (signed 16-bit integer)** WAV. The STT strategy CLIs (Apple SpeechTranscriber, WhisperKit) expect this format. The denoiser output is saved at model.sample_rate (16 kHz for DNS64).

#### Noise level definitions

| `noise_level` | SNR | Context |
|---|---|---|
| `clean` | ∞ | No added noise — baseline |
| `snr15db` | +15 dB | Quiet room, faint background noise |
| `snr15db_nr` | +15 dB → denoised | DNS64 applied to snr15db |
| `snr5db` | +5 dB | Showroom floor, general conversation — **production operating point** |
| `snr5db_nr` | +5 dB → denoised | DNS64 applied to snr5db |
| `snr0db` | 0 dB | Very noisy — signal and noise at equal power — stress test |
| `snr0db_nr` | 0 dB → denoised | DNS64 applied to snr0db |

SNR formula: `rms_noise = rms_signal / 10^(snr_db / 20)`. Seed 42 for reproducibility.

---

### The `audio_id` — canonical asset identifier

Every audio file has a canonical `audio_id` string that encodes its lineage:

```
{source_type}/{identifier}/{noise_level}
```

| File path | audio_id |
|---|---|
| `data/audio/synthesized/script_001__us_neutral/clean.wav` | `synthesized/script_001__us_neutral/clean` |
| `data/audio/synthesized/script_001__us_neutral/snr5db.wav` | `synthesized/script_001__us_neutral/snr5db` |
| `data/audio/synthesized/script_001__us_neutral/snr5db_nr.wav` | `synthesized/script_001__us_neutral/snr5db_nr` |
| `data/audio/youtube/2FXQvvp9Blw/clean.wav` | `youtube/2FXQvvp9Blw/clean` |
| `data/audio/youtube/2FXQvvp9Blw/snr5db_nr.wav` | `youtube/2FXQvvp9Blw/snr5db_nr` |

The `audio_id` is the key in the transcript cache and the `audio_id` column in the L1 CSV. It maps directly to a file path under `data/audio/`.

---

### Transcript cache (`data/transcripts/`)

```
data/transcripts/{strategy}/{audio_id}.jsonl
```

Examples:
```
data/transcripts/apple_speech_transcriber/synthesized/script_001__us_neutral/clean.jsonl
data/transcripts/whisperkit/youtube/2FXQvvp9Blw/snr5db.jsonl
```

Each file is one JSONL line per `TranscriptEvent`:

```json
{"text": "wireless Apple CarPlay just drop your phone",
 "stability": "final",
 "timestamp_ms": 8523,
 "latency_ms_from_audio_start": 8523,
 "confidence": 0.97,
 "engine_metadata": {"model": "base", "chunk_idx": 3}}
```

Field definitions:
- `text` — the transcript text for this event
- `stability` — `"partial"` (hypothesis, may change) or `"final"` (confirmed)
- `timestamp_ms` — position in the audio stream (ms from start)
- `latency_ms_from_audio_start` — same as timestamp_ms for file-based STT (differs in live mode)
- `confidence` — engine confidence [0, 1] if available; null otherwise
- `engine_metadata` — engine-specific fields (model name, chunk index, etc.)

**Cache semantics:** If a JSONL file exists for a `(strategy, audio_id)` pair, the orchestrator loads it and skips the STT subprocess entirely. Deleting the file forces re-transcription. The cache format is readable by both Python (via `_load_transcript`) and can be inspected/edited by hand.

**Format compatibility:** Two field names are accepted for `stability`:
- `"stability": "final"` — Python cache format (written by the orchestrator)
- `"type": "final"` — Swift CLI format (used by Apple/WhisperKit CLI output)

Both are read correctly by `_load_transcript`.

---

## Layer 3 — Runs (`runs/`)

### Run directory

```
runs/results/run-{YYYYMMDD-HHMM}/
├── summary.md
├── noise_comparison.md
├── {strategy}-results.csv
└── {strategy}-false-positives.csv
```

Each run gets a timestamped directory. Run IDs are deterministic (based on start time) so re-running the same matrix produces a new directory — old results are preserved.

### L1 CSV schema (`{strategy}-results.csv`)

One row per `(script, audio_variant, expected_cue)` triple. This is the investigation-level dataset — every miss and every hit has a row with the raw evidence needed to understand it.

| Column | Type | Description |
|---|---|---|
| `cue_id` | str | e.g. `universal.feature.wireless_apple_carplay` |
| `outcome` | str | `pass` / `partial` / `fail` / `false_positive` |
| `reason` | str | Human-readable explanation of the outcome |
| `script_id` | str | Source script YAML id |
| `audio_id` | str | Canonical audio identifier (see above) |
| `noise_level` | str | `clean` / `snr15db` / `snr5db_nr` / … |
| `expected_timestamp_ms` | int/null | From script YAML `approx_ms` field |
| `detection_timestamp_ms` | int/null | When the cue actually fired (null if miss) |
| `delta_ms` | int/null | detection - expected (negative = detected early) |
| `match_method` | str/null | `exact` / `semantic` / null |
| `match_score` | float/null | Cosine similarity (semantic only) |
| `engine_text_window_before` | str | STT finals in 5 s window before expected_timestamp |
| `engine_text_window_at` | str | STT finals in ±1 s window at expected_timestamp |
| `engine_text_window_after` | str | STT finals in 5 s window after expected_timestamp |
| `reference_text` | str | Ground-truth segment text from script YAML |
| `caption_stt_agreement_score` | float | Jaccard word overlap: reference vs engine output |
| `miss_type` | str | `stt_error` / `phrase_gap` / `annotation_error` / `not_said` (post-hoc) |
| `miss_notes` | str | Investigation notes (filled manually) |

#### Outcome definitions

| Outcome | Meaning |
|---|---|
| `pass` | Cue detected with exact or semantic match |
| `partial` | Detected but not cleanly (implementation currently maps to pass) |
| `fail` | Expected cue not detected — false negative |
| `false_positive` | Cue fired but was listed in `negative_cues` |

#### Using the window fields to investigate misses

For every `fail` row:
- `engine_text_window_at` shows what the engine transcribed around the expected timestamp. If this is empty or very wrong, it's an `stt_error`.
- `engine_text_window_before`/`after` shows surrounding context. If the right words appear but shifted, it's a timestamp annotation issue (`annotation_error`).
- `caption_stt_agreement_score < 0.3` means the engine transcript diverged significantly from the reference — likely `stt_error` or difficult audio.
- If all windows are empty and agreement is 0, the cue was likely never said (`not_said`).

#### `miss_type` values

| Value | Meaning |
|---|---|
| `stt_error` | Engine failed to transcribe the phrase correctly |
| `phrase_gap` | Phrase was transcribed but didn't match any cue phrase — add synonym |
| `annotation_error` | Expected_timestamp_ms was wrong — cue appeared at a different time |
| `not_said` | Dealer genuinely didn't say this cue in this recording |
| *(empty)* | Not yet investigated |

`miss_type` and `miss_notes` are never written by the orchestrator — they are filled in by a human (or an automated post-processing pass) to classify each false negative.

---

## Future: asset registry

When audio assets grow beyond local disk capacity, a SQLite registry will track each asset:

```sql
CREATE TABLE assets (
    audio_id        TEXT PRIMARY KEY,
    local_path      TEXT,          -- relative to lab root (null if remote-only)
    remote_uri      TEXT,          -- e.g. s3://rts-lab-audio/youtube/2FXQvvp9Blw/clean.wav
    is_local        BOOLEAN,
    is_remote       BOOLEAN,
    size_bytes      INTEGER,
    duration_sec    FLOAT,
    sample_rate     INTEGER,
    created_at      TEXT
);
```

Commands planned: `voice-lab data push`, `voice-lab data pull`, `voice-lab data status`.
