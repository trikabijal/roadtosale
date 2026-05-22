# voice-lab

Python lab for comparing STT strategies offline.

## What it is

A strategy-pattern test harness for the voice engine. It loads audio
fixtures, runs each registered transcription strategy over them, matches
transcript events against caller-supplied cue atoms, classifies the
results (pass / partial / fail / false_positive), and emits a markdown +
CSV report.

The lab is brand-agnostic. It does not import the vehicle catalog
directly. The `voice-lab` CLI is the only file that knows about both the
catalog and the engine.

## Install

```bash
cd voice-engine/lab
./build.sh
```

`build.sh` runs `pip install -e .` then `pytest tests -q`.

## CLI

```bash
voice-lab synth                                  # synthesize fixtures (stub)
voice-lab run --strategies mock                  # run comparison
voice-lab validate-catalog --data-dir <path>     # validate the vehicle catalog
```

### Catalog wiring

The `run` subcommand best-effort imports
`vehicle_feature_catalog`. If that package is not installed in the same
virtualenv, install it first:

```bash
pip install -e ../../vehicle-feature-catalog/src/python
```

## Strategies in v1

| Strategy | Status |
|---|---|
| `mock` | Works — replays JSONL events |
| `apple_speech_transcriber` | Stub. Resolve PRD OQ1 first |
| `argmax` | Stub. Resolve PRD OQ2 first |

## Layout

```
lab/
├── src/voice_lab/
│   ├── facade.py             VoiceEngineLab — the public entry point
│   ├── types.py              TranscriptEvent / CueAtom / CueDetection
│   ├── orchestrator.py       runs strategy x script matrix
│   ├── cli.py                voice-lab entry point
│   ├── strategies/           registry + per-engine wrappers
│   ├── matcher/              cue matcher (phrase + synonym, normalized)
│   ├── scoring/              classification + latency percentiles
│   ├── reporting/            markdown + CSV writers
│   └── synthesis/            ElevenLabs + noise (stubs)
├── tests/                    unit + cross-language drift tests
├── fixtures/                 (managed by another agent)
├── cue-packs/                (managed by another agent)
└── reports/                  (gitignored — generated)
```
