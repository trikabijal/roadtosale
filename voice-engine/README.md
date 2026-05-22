# voice-engine

Strategy-based audio + STT + cue-matching layer. Brand-agnostic and
product-agnostic. See `docs/api.md`, `docs/architecture.md`, `docs/flows.md`.

## Two consumer shapes, one engine

| Facade | Consumer | Entry point |
|---|---|---|
| `VoiceEngineLab` (Python) | Offline comparison lab | `from voice_lab import VoiceEngineLab` |
| `VoiceEngine` (TS) | Live mobile library | `import { VoiceEngine } from 'voice-engine'` |

## Layout

```
voice-engine/
├── lab/                  Python comparison lab (full implementation)
├── src/                  TS library (skeleton; mock strategy works, Apple stub)
├── ios/                  Swift native module — placeholder
├── android/              Kotlin stub — placeholder
├── tests/                TS vitest tests
├── docs/                 binding spec (api, architecture, flows)
├── package.json
├── tsconfig.json
├── vitest.config.ts
├── build.sh
└── README.md
```

## Build

```bash
# Python lab
cd lab && ./build.sh

# TS library
./build.sh
```

The root `build.sh` runs `npm install`, `npm test`, `npm run build`,
and prints reminders that the native iOS / Android wiring is deferred
to a future PRD (see OQ1).

## What's stubbed in v1

| Surface | Why |
|---|---|
| `AppleSpeechTranscriberStrategy` (lab + TS) | PRD OQ1 — macOS binding mechanism unresolved |
| `ArgmaxStrategy` (lab) | PRD OQ2 — Python bindings vs CLI unresolved |
| `ElevenLabsSynthesisProvider` (lab) | PRD OQ3 — API key + voice IDs unresolved |
| `NoiseOverlayProvider` (lab) | PRD OQ7 — noise track unsourced |
| iOS Swift native module | Deferred to future PRD |
| Android Kotlin module | Deferred to future PRD |
| `src/capture/` audio capture | Sketched interface only |

The mock strategy works in both languages. That is enough to land the
facade contract, the matcher, scoring, and reporting — i.e. enough to
run drift tests and round-trip evidence end-to-end.
