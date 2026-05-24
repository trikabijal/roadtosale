# voice-lab

Offline STT strategy comparison harness for the Road to Sale voice engine.

Runs a matrix of transcription strategies (Apple SpeechTranscriber, WhisperKit, …) against audio fixtures, matches transcript output against cue packs, classifies results, and emits a markdown + CSV report — including noise robustness and denoiser recovery comparisons.

---

## Install

```bash
cd voice-engine/lab
./build.sh           # pip install -e . + runs pytest
```

Install extras as needed:

```bash
# Vehicle feature catalog (optional — adds 270+ feature cues)
pip install -e ../../vehicle-feature-catalog/src/python

# Semantic matching (optional — fastembed + bge-small-en-v1.5)
pip install -e ".[semantic]"

# DNS64 neural denoiser (optional — Facebook Research, MIT, pulls torch)
pip install -e ".[denoise]"
```

---

## Quick CLI reference

```bash
# 1. Synthesize clean audio from hand-written scripts (ElevenLabs)
voice-lab synth

# 2. Download Honda walkaround videos as 16 kHz mono WAV
voice-lab fetch-youtube-audio --manifest sources/manifests/honda_walkarounds.yaml

# 3. Pull YouTube captions as script YAMLs
voice-lab ingest-youtube --manifest sources/manifests/honda_walkarounds.yaml

# 4. Add white Gaussian noise at SNR +15 / +5 / 0 dB
voice-lab generate-noise

# 5. Apply DNS64 neural denoiser (trained on DNS Challenge real-world noise)
voice-lab denoise

# 6. Run the full comparison matrix
voice-lab run \
  --strategies apple_speech_transcriber,whisperkit \
  --semantic \
  --semantic-threshold 0.65

# Output lands in runs/results/run-YYYYMMDD-HHMM/
#   summary.md             — aggregate FNR / FPR / latency per strategy
#   noise_comparison.md    — FNR breakdown by noise level + denoiser recovery
#   {strategy}-results.csv — L1 investigation rows (one per cue × audio × strategy)
#   {strategy}-false-positives.csv
```

---

## Directory layout

```
lab/
├── sources/
│   ├── scripts/           Hand-written test scripts (YAML)
│   └── youtube/           Auto-generated scripts from YouTube captions (YAML)
│
├── data/                  Generated assets — gitignored for audio
│   ├── audio/
│   │   ├── synthesized/   ElevenLabs TTS output
│   │   │   └── {script_id}__{voice}/
│   │   │       ├── clean.wav
│   │   │       ├── snr15db.wav     white noise +15 dB
│   │   │       ├── snr15db_nr.wav  DNS64 denoised
│   │   │       ├── snr5db.wav
│   │   │       ├── snr5db_nr.wav
│   │   │       ├── snr0db.wav
│   │   │       └── snr0db_nr.wav
│   │   └── youtube/
│   │       └── {video_id}/   same layout as synthesized/
│   └── transcripts/       Transcript cache (JSONL per strategy × audio_id)
│       └── {strategy}/{audio_id}.jsonl
│
├── runs/
│   └── results/
│       └── run-YYYYMMDD-HHMM/
│           ├── summary.md
│           ├── noise_comparison.md
│           ├── {strategy}-results.csv
│           └── {strategy}-false-positives.csv
│
├── cue-packs/
│   ├── universal_workflow_cues.yaml   Workflow-step cues (greet, walkaround, …)
│   └── accent_voices.yaml             ElevenLabs voice profiles
│
├── src/voice_lab/
│   ├── facade.py          VoiceEngineLab — the public entry point
│   ├── types.py           TranscriptEvent / CueAtom / CueDetection
│   ├── orchestrator.py    Strategy × script matrix runner + transcript cache
│   ├── cli.py             All CLI subcommands
│   ├── strategies/        STT strategy registry + per-engine wrappers
│   ├── matcher/           Two-layer cue matcher (exact + semantic)
│   ├── scoring/           Classification + latency percentiles
│   ├── reporting/         Markdown + CSV report writers
│   ├── synthesis/         ElevenLabs TTS, noise generation, DNS64 denoiser
│   └── ingestion/         YouTube transcript + audio fetcher
│
└── tests/                 Unit tests
```

---

## Deep-dive docs

| Document | What it covers |
|---|---|
| [docs/pipeline.md](docs/pipeline.md) | Six-stage test pipeline, transcript cache, run output |
| [docs/data-layout.md](docs/data-layout.md) | Data model, naming conventions, L1 CSV schema |
| [docs/cues-and-strategies.md](docs/cues-and-strategies.md) | CueAtom schema, two-layer matching, STT strategies |
| [docs/architecture.md](docs/architecture.md) | Component diagram, design decisions, facade contract |

---

## Lab results at a glance (run-20260524-0829)

2 strategies × 64 audio fixtures (16 clean + 48 noisy). 220 expected cue firings per noise level.

| | Apple SpeechTranscriber | WhisperKit |
|---|---|---|
| **Clean FNR** | 4.5% | 1.4% |
| **SNR +15 dB FNR** | 4.5% | 2.7% |
| **SNR +5 dB FNR** | 8.6% | 20.0% |
| **SNR 0 dB FNR** | 18.6% | 27.3% |
| **FPR (all levels)** | 0.0% | 0.0% |

WhisperKit is sharper in clean conditions; Apple degrades more gracefully in showroom noise. Crossover is between SNR +15 dB and +5 dB. See `runs/results/run-20260524-0829/noise_comparison.md` for the full breakdown.
