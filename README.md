# Road to Sale

NADA-aligned dealership sales coaching, built on the AuditPro / SmartComply platform.

A sales rep walks a customer through a vehicle. The voice engine listens, detects workflow steps and feature demonstrations in real time, and builds the audit trail automatically.

The repo also hosts a second product, **JustTalk** (`dictation/`) — a macOS dictation app
that shares no runtime code with Road to Sale but **shares the voice cleanup contract and
data**, so its daily-driver learnings tune Road to Sale's cleanup for free.

> **Start here:** whole-system docs live in [`docs/`](docs/) —
> [`architecture.md`](docs/architecture.md), [`api.md`](docs/api.md),
> [`flows.md`](docs/flows.md). Each module also has its own `docs/`.

---

## Repository layout

```
roadtosale/
│
├── docs/                      Whole-system docs (architecture, api, flows + PRD/audio deep-dives)
│
├── vehicle-feature-catalog/   Brand-extensible vehicle catalog (YAML + Python + TS)
│                              What features exist, which trims carry them, how to detect them.
│
├── voice-engine/              Contract + data + research core for STT, cue-matching, and cleanup.
│   │                          NOT a library the apps link — apps mirror its contract natively.
│   ├── lab/                   Offline comparison lab — benchmarks STT strategies on fixtures
│   ├── src/                   TS reference skeleton (only `mock` runs; mirrored by native code)
│   ├── cleanup-packs/         Canonical cleanup data packs (dictation + road-to-sale)
│   ├── native/                Swift + JVM CLIs used by lab strategies
│   └── docs/                  Binding spec + model-contracts.md
│
├── road-to-sale-app/          Mobile app — Expo / React Native + native iOS (Swift) & Android (Kotlin)
│                              voice modules. Talks to the external SmartComply backend over HTTP.
│
├── dictation/                 JustTalk — macOS menu-bar dictation app (+ paused iOS keyboard).
│                              Shared DictationCore Swift package; mirrors the voice cleanup contract.
│
├── demo/                      Outreach / showcase material only (not product code)
│
├── dev/                       Development-only: PRDs, task lists, decisions log, CI/CD playbook
│   └── tasks/
│       ├── 0001-prd-voice-engine.md
│       ├── tasks-0001-prd-voice-engine.md
│       └── decisions-log.md
│
└── scripts/
    └── check_imports.py       CI boundary guard — fails if voice-engine ↔ catalog import each other
```

> The **SmartComply / AuditPro backend** (Java / Spring) used to live here but is now a
> **separate repository**. Road to Sale integrates with it strictly over HTTP at `:8089`
> via `SmartComplyClient`. The consumer-side contract is documented in
> [`road-to-sale-app/docs/smartcomply-contract.md`](road-to-sale-app/docs/smartcomply-contract.md).

### Module boundaries

```
vehicle-feature-catalog   ←──── no cross-imports ────→   voice-engine
         ↑                                                      ↑
         └──────────── consumer (lab CLI, mobile app) ─────────┘
```

Neither module imports the other. The consumer (lab orchestrator, mobile app) is the only place both are composed. `scripts/check_imports.py` enforces this in CI. The apps never import the `voice-engine` TS package — they re-implement its contract natively (see the banner in [`voice-engine/README.md`](voice-engine/README.md)).

---

## Modules

### `vehicle-feature-catalog/`

Brand-extensible catalog of vehicle Makes, Models, Trims, Features, and the sparse trim × feature availability matrix. YAML files in git, no database.

| | |
|---|---|
| Languages | Python 3.11+ · TypeScript 5+ |
| API entry | `from vehicle_feature_catalog import VehicleFeatureCatalog` |
| Data | `vehicle-feature-catalog/data/` (Honda 2026 full lineup + toy Toyota fixture) |
| Docs | `vehicle-feature-catalog/docs/{api,architecture,flows}.md` |

```bash
cd vehicle-feature-catalog
./build.sh          # install deps + run all tests
python3 -m pytest tests/python -q
npm test
```

### `voice-engine/lab/`

Offline STT comparison lab. Transcribes pre-recorded audio through multiple STT strategies, matches cue phrases, classifies hits/misses, and writes structured run reports.

| | |
|---|---|
| Language | Python 3.11+ |
| Strategies | `apple_speech_transcriber` · `whisperkit` · `apple_sfspeechrecognizer` · `argmax` · `sherpa_onnx` · `mock` |
| API entry | `from voice_lab import VoiceEngineLab` |
| CLI | `voice-lab run`, `voice-lab synth`, `voice-lab generate-noise`, `voice-lab ingest-youtube` |
| Docs | `voice-engine/lab/docs/{architecture,pipeline,data-layout,cues-and-strategies}.md` |

```bash
cd voice-engine/lab
./build.sh          # install deps + run all tests

# Run the lab (requires Apple Silicon Mac with macOS Sequoia+)
voice-lab run --strategies apple_speech_transcriber,whisperkit --semantic
```

Latest iOS results: `voice-engine/lab/runs/results/run-20260524-1142/`
- Apple SpeechTranscriber: FNR 4.5% clean → **8.6% at SNR +5 dB** (showroom) · TTFC P95 **330 ms**
- WhisperKit: FNR 1.4% clean → **8.2% at SNR +5 dB** · TTFC P95 **610 ms**

Latest Android results: `voice-engine/lab/runs/results/run-20260524-1342/` (Silero VAD mode)
- sherpa-onnx + Silero VAD: FNR 2.3% clean → **14.1% at SNR +5 dB** · TTFC P50 **534 ms** · Semantic lift **+23%**

**Production recommendation:** Apple SpeechTranscriber for iOS (TTFC P95 330 ms, on-device, no external dependency). sherpa-onnx + Silero VAD for Android simulation — VAD mode produces sentence-level events that align with semantic matching granularity (23% lift vs 17% in batch mode).

### `voice-engine/` (contract + data core)

Canonical STT + cleanup **contract**, the cleanup **data packs**, and the TS **reference
skeleton** the apps mirror in native code. Nothing in this repo imports it as a library —
the `mock` strategy is the only one that runs. See the README banner for why the former
`ios/`/`android/` stubs were removed.

| | |
|---|---|
| Language | TypeScript 5+ (skeleton) |
| Contract | `voice-engine/docs/model-contracts.md` (STT + cleanup `{provider, model}`) |
| Data | `voice-engine/cleanup-packs/{dictation,road-to-sale}.json` |
| Docs | `voice-engine/docs/{api,architecture,flows,model-contracts}.md` |

```bash
cd voice-engine
./build.sh          # npm install + vitest + tsc
```

### `road-to-sale-app/`

The Road to Sale mobile app: Expo / React Native (TypeScript) with native iOS (Swift) and
Android (Kotlin) voice modules that mirror the `voice-engine` contract. Talks to the
external SmartComply backend over HTTP.

| | |
|---|---|
| Languages | TypeScript · Swift · Kotlin |
| Backend | External SmartComply REST API at `:8089` (separate repo) |
| Docs | `road-to-sale-app/docs/smartcomply-contract.md` |

```bash
cd road-to-sale-app
./build.sh          # install deps
./run.sh            # launch in Expo
./test.sh           # run tests
```

### `dictation/` (JustTalk)

A macOS menu-bar dictation app (Wispr Flow replacement) built on WhisperKit, plus a paused
iOS keyboard extension. All targets share the `DictationCore` Swift package, which
implements the same STT/cleanup contract as `voice-engine` and emits telemetry on the shared
schema — so its daily-driver learnings tune Road to Sale's cleanup.

| | |
|---|---|
| Language | Swift (DictationCore package + apps) |
| Targets | JustTalk (macOS, ships) · DictationKeyboard + container (iOS, paused) |
| Build | XcodeGen — `cd dictation && xcodegen generate` |
| Docs | `dictation/docs/architecture.md` · `dictation/README.md` |

```bash
cd dictation
xcodegen generate   # one-time; needs `brew install xcodegen`
./build.sh          # Release build into ./build
./run.sh            # build + launch (macOS)
```

---

## Running the boundary check

```bash
python3 scripts/check_imports.py
```

Exits `0` if clean, `1` with violation details if any cross-boundary import is found.

---

## Decisions log

All non-trivial design decisions, open-question resolutions, and build-time choices are logged in:

```
dev/tasks/decisions-log.md
```

Key decisions: D1–D18 (PRD-locked), DC1–DC44 (build-time), DC45–DC53 (voice lab run results + architecture).

---

## Branch + commit conventions

See `~/.claude/CLAUDE.md` (global standards). Short version:
- **Never commit to `main`** — always branch
- Branch names: `feat/`, `fix/`, `refactor/`, `docs/`, `chore/`
- Conventional commits: `feat:`, `fix:`, `refactor:`, `BREAKING:`
