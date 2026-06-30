# voice-engine

Strategy-based audio + STT + cue-matching layer. Brand-agnostic and
product-agnostic. The Python `lab/` is the real, runnable comparison harness;
the TS `src/` is a reference skeleton the production apps mirror in native code.

> **What this is — read first.** `voice-engine` is the **contract, data, and
> research core — NOT a library the apps link against.** The Python `lab/` is
> the real, working comparison harness; `cleanup-packs/` and
> `docs/model-contracts.md` are the canonical data + contract. The TS `src/` is
> a **reference skeleton** (only the `mock` strategy runs) that the production
> apps *mirror in native code* — they do **not** import it. Production STT is
> implemented per platform: `road-to-sale-app/ios` (SFSpeechRecognizer) +
> `road-to-sale-app/android` (sherpa-onnx), and the macOS app in `dictation/`
> links WhisperKit directly via `DictationCore`. Nothing in this repo imports
> this TS package. (Former empty `ios/`/`android/` placeholder modules were
> removed for this reason.)

## Prerequisites

| Tool | Version | Install |
|---|---|---|
| `python3` | >= 3.10 | https://www.python.org/downloads/ — or `brew install python@3.12` |
| `node` | >= 20 | https://nodejs.org/ — or `brew install node` |
| `npm` | (ships with Node >= 20) | included with Node.js |

**Optional — only for the native STT CLIs** (`./build.sh --with-native`,
`./deploy-local.sh`; macOS only):

| Tool | Version | Install |
|---|---|---|
| macOS + Xcode / Swift | current | Xcode from the App Store, or `xcode-select --install` |
| JDK | Java 17+ | `brew install --cask temurin` — or https://adoptium.net/ |

Off-macOS the native CLIs are skipped gracefully (the scripts print why); the
Python lab + TS skeleton still build and test, and the `mock` strategy runs.

> Run from the **full monorepo**. The lab editable-installs the sibling
> `vehicle-feature-catalog` from `../vehicle-feature-catalog`; the build fails
> loudly if that sibling directory is missing.

## Quickstart

```bash
./build.sh        # Python lab (lab/.venv + editable install) + TS skeleton (dist/)
./test.sh         # run both suites
```

Expected result: **85 Python + 20 TS tests pass.**

```bash
./build.sh --with-native   # also build the native STT CLIs (macOS only)
./run.sh                   # run the Python comparison lab CLI
./run.sh run --strategies mock   # run a comparison (no native binaries needed)
```

Every script checks its prerequisites at the top and fails with an actionable
`ERROR: <tool> is required but not found. Install: <command>` message rather
than a deep, confusing failure.

## Docs

- [`docs/build.md`](docs/build.md) — full build + native CLI details
- [`docs/architecture.md`](docs/architecture.md) — components, facades, isolation
- [`docs/model-contracts.md`](docs/model-contracts.md) — canonical model/cleanup contract

Also: [`docs/api.md`](docs/api.md), [`docs/flows.md`](docs/flows.md).

## Two consumer shapes, one engine

| Facade | Consumer | Entry point |
|---|---|---|
| `VoiceEngineLab` (Python) | Offline comparison lab | `from voice_lab import VoiceEngineLab` |
| `VoiceEngine` (TS) | Reference skeleton (mirrored natively) | `import { VoiceEngine } from 'voice-engine'` |

## Layout

```
voice-engine/
├── lab/                  Python comparison lab (full implementation)
├── src/                  TS reference skeleton (mock strategy works; mirrored natively by the apps)
├── tests/                TS vitest tests
├── native/               Per-platform native STT CLIs (optional, --with-native)
├── cleanup-packs/        Canonical cleanup data packs
├── docs/                 binding spec (api, architecture, flows, build, model-contracts)
├── scripts/prereqs.sh    Shared prerequisite checks (sourced by all entry scripts)
├── build.sh  test.sh  run.sh  deploy-local.sh
├── package.json  tsconfig.json  vitest.config.ts
└── README.md
```

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

The mock strategy works in both languages. That is enough to land the facade
contract, the matcher, scoring, and reporting — i.e. enough to run drift tests
and round-trip evidence end-to-end.
