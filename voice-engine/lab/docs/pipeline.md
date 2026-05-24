# Voice Lab — Test Pipeline

The lab runs a five-stage pipeline. Each stage is idempotent and independently re-runnable. You do not have to start from stage 1 every time — you can pick up from any stage whose inputs already exist.

```
Stage 1: Sources         sources/scripts/*.yaml   sources/youtube/*.yaml
                         ↓
Stage 2: Audio           data/audio/{type}/{id}/clean.wav
                         ↓
Stage 3: Noise           data/audio/{type}/{id}/snr{N}db.wav
                         ↓
Stage 4: Run (STT→cache→match→classify)
              data/transcripts/{strategy}/{audio_id}.jsonl    ← transcript cache
              runs/results/{run_id}/                          ← run output
                         ↓
Stage 5: Review          summary.md   noise_comparison.md   L1 CSV
```

**Note on noise suppression:** The lab tests raw audio only. On iPhone, `AUVoiceProcessingIO` is always active in the hardware capture pipeline — the STT engine never receives raw noisy frames. To measure the hardware noise suppressor's effect, record the noisy WAV fixtures through an iPhone's microphone (with `AVAudioSession` configured as the production app will configure it) and add those recordings as lab fixtures. The reporting layer picks them up automatically from the `_nr` (or any custom suffix) slot in the `_collect_audio_variants()` naming convention.

---

## Stage 1 — Sources

Two types of source scripts live under `sources/`:

### Hand-written scripts (`sources/scripts/*.yaml`)

Written by hand to cover specific scenarios: a walkaround positive case, a negative case (cues deliberately not said), edge cases. Treated as ground truth — the expected cues are manually curated.

Schema:
```yaml
id: script_001_crv_hybrid_walkaround
title: "Honda CRV Hybrid Walkaround — positive case"
source:
  type: hand_written

segments:
  - id: greet
    text: "Hi, welcome in! Can I grab you a coffee or water while we look around?"
    expected_cues:
      - cue_id: workflow.hospitality_offer
        approx_ms: 2000          # expected detection timestamp (optional)

  - id: feature_carplay
    text: "This has wireless Apple CarPlay built in — just drop your phone in the tray."
    expected_cues:
      - cue_id: universal.feature.wireless_apple_carplay
        approx_ms: 8500

negative_cues: []                # cue_ids that should NOT fire in this script
```

### YouTube-sourced scripts (`sources/youtube/youtube_{video_id}.yaml`)

Auto-generated from public Honda dealer walkaround YouTube captions using `voice-lab ingest-youtube`. The expected cues are populated by phrase-matching the YouTube captions against the cue pack — they are **caption-relative ground truth**, not manually verified. The `caption_stt_agreement_score` field in the L1 CSV measures how well the STT transcript matches the caption text.

```bash
voice-lab ingest-youtube --manifest sources/manifests/honda_walkarounds.yaml
```

---

## Stage 2 — Audio

### Synthesized audio (ElevenLabs TTS)

```bash
voice-lab synth
```

For each script in `sources/scripts/` and each voice profile in `cue-packs/accent_voices.yaml`, synthesizes a WAV file:

```
data/audio/synthesized/{script_id}__{voice_id}/clean.wav
```

Requires `ELEVENLABS_API_KEY` in your environment.

### YouTube audio

```bash
voice-lab fetch-youtube-audio --manifest sources/manifests/honda_walkarounds.yaml
```

Downloads the video with yt-dlp, converts to **16 kHz mono PCM-16 WAV**:

```
data/audio/youtube/{video_id}/clean.wav
```

---

## Stage 3 — Noise generation

```bash
voice-lab generate-noise
# options:
#   --snr-levels 15,5,0         SNR targets in dB (default: 15,5,0)
#   --source-types synthesized,youtube
#   --force                     re-generate even if file exists
```

Mixes white Gaussian noise into each `clean.wav` at three SNR levels (signal-to-noise ratio):

| File | SNR | Meaning |
|---|---|---|
| `snr15db.wav` | +15 dB | Quiet room, faint background — minimal impact on ASR |
| `snr5db.wav` | +5 dB | Showroom floor, general conversation — the production operating point |
| `snr0db.wav` | 0 dB | Very noisy — signal and noise at equal power — stress test |

Files live alongside `clean.wav` in the same directory:

```
data/audio/synthesized/script_001_crv_hybrid_walkaround__us_baseline_neutral/
├── clean.wav
├── snr15db.wav
├── snr5db.wav
└── snr0db.wav
```

**Implementation:** `voice_lab/synthesis/noise.py` — uses `soundfile` + `numpy`. SNR formula: `rms_noise = rms_signal / 10^(snr_db / 20)`. Seed=42 for reproducibility.

> **Note on noise realism:** White Gaussian noise is a controlled lab construct with a flat spectrum. Real dealership noise is non-stationary (music, HVAC, competing voices, engine sounds). Phase 2 will replace this with DNS Challenge noise clips for more realistic testing.

---

## Stage 4 — The Run (two-step architecture)

```bash
voice-lab run \
  --strategies apple_speech_transcriber,whisperkit \
  --semantic \
  --semantic-threshold 0.65
```

The run is **two decoupled steps** inside the orchestrator. This is the most important design decision in the pipeline.

### Step 1: Transcription → transcript cache

For each `(strategy, audio)` pair the orchestrator calls `_get_events()`:

```
cache miss → STT subprocess (Swift CLI) → list[TranscriptEvent] → saved to cache
cache hit  → load from disk                                      → list[TranscriptEvent]
```

Cache path: `data/transcripts/{strategy}/{audio_id}.jsonl`

One JSONL line per `TranscriptEvent`:
```json
{"text": "wireless Apple CarPlay", "stability": "final", "timestamp_ms": 8523,
 "latency_ms_from_audio_start": 8523, "confidence": 0.97, "engine_metadata": {}}
```

The cache survives across runs. Re-running after a strategy change uses cached transcripts — only the matching/classification step re-runs. Re-running after a cue pack change is instant (all cache hits, new matches).

### Step 2: Match + classify

For each cached event list:
1. **Exact matcher** — substring match on every event (partials + finals). Fires in ~280 ms (one partial window). Zero false positives.
2. **Semantic matcher** (optional, `--semantic`) — sentence embeddings (BAAI/bge-small-en-v1.5 via fastembed) on confirmed finals only. Cosine similarity threshold (default 0.65). Catches paraphrases the exact layer misses. Adds ~8 ms per final utterance.
3. **Classifier** — maps detections to expected cues, computes outcome (pass / partial / fail / false_positive), extracts investigation evidence.

### The audio_id

Every audio file has a canonical `audio_id` that identifies it in the transcript cache and L1 CSV:

```
synthesized/{script_id}__{voice}/{noise_level}
youtube/{video_id}/{noise_level}
```

Examples:
```
synthesized/script_001_crv_hybrid_walkaround__us_baseline_neutral/clean
synthesized/script_001_crv_hybrid_walkaround__us_baseline_neutral/snr5db
youtube/2FXQvvp9Blw/clean
youtube/2FXQvvp9Blw/snr5db
```

The `noise_level` segment at the end (`clean`, `snr15db`, `snr5db`, …) drives the noise comparison grouping in the report.

---

## Stage 5 — Run output

Each run writes to `runs/results/run-{YYYYMMDD-HHMM}/`:

### `summary.md`

Aggregate results across all noise levels and audio sources per strategy:
- Outcomes: detected / not said / FNR / FPR
- Match method breakdown: exact vs semantic (semantic lift)
- Engine performance: TTFT P50/P95, TTFinal, RTF
- Detection latency percentiles
- Timing breakdown: transcription / matching / classification wall time

### `noise_comparison.md`

Noise robustness breakdown per strategy:

| Noise level | Pass | Fail | FNR | Δ vs clean | FPR |
|---|---|---|---|---|---|
| clean | 217 | 3 | 1.4% | — | 0.0% |
| SNR +15 dB | 214 | 6 | 2.7% | +1.4pp | 0.0% |
| SNR +5 dB | 202 | 18 | 8.2% | +6.8pp | 0.0% |
| SNR 0 dB | 179 | 41 | 18.6% | +17.3pp | 0.0% |

Per-cue breakdown follows for each strategy. If `_nr` (or other processed) audio variants are present in `data/audio/`, those rows appear automatically with a "Δ vs noisy (recovery)" column showing the processing benefit.

### `{strategy}-results.csv` — L1 investigation rows

One row per `(strategy, script, audio_variant, expected_cue)`. Contains everything needed to investigate a false negative without re-running the engine:

| Column | Description |
|---|---|
| `cue_id` | The expected cue identifier |
| `outcome` | pass / partial / fail / false_positive |
| `reason` | Why this classification was assigned |
| `script_id` | Source script YAML id |
| `audio_id` | Canonical audio identifier |
| `noise_level` | clean / snrNdb |
| `expected_timestamp_ms` | When the cue was expected (from script YAML) |
| `detection_timestamp_ms` | When it was actually detected (null if miss) |
| `delta_ms` | detection - expected (negative = detected early) |
| `match_method` | exact / semantic / null |
| `match_score` | Cosine similarity (semantic only) |
| `engine_text_window_before` | STT finals in 5 s window before expected timestamp |
| `engine_text_window_at` | STT finals in ±1 s window at expected timestamp |
| `engine_text_window_after` | STT finals in 5 s window after expected timestamp |
| `reference_text` | Ground-truth segment text from script YAML |
| `caption_stt_agreement_score` | Jaccard word overlap: reference vs engine output |
| `miss_type` | stt_error / phrase_gap / annotation_error / not_said (filled post-hoc) |
| `miss_notes` | Human investigation notes |

The `engine_text_window_*` columns are the key debugging fields — they show exactly what the engine transcribed around the expected timestamp so you can understand every miss without listening to the audio.

---

## Re-running individual stages

All stages are idempotent. Common re-run patterns:

```bash
# Changed a cue pack? Re-run matching only — all transcripts already cached.
voice-lab run --strategies apple_speech_transcriber,whisperkit --semantic

# New script added? Synth + noise, then run.
voice-lab synth
voice-lab generate-noise
voice-lab run --strategies apple_speech_transcriber,whisperkit --semantic

# Test a new strategy without re-transcribing existing audio.
# (Clean files hit cache; new strategy's files will run fresh STT)
voice-lab run --strategies my_new_strategy --semantic
```
