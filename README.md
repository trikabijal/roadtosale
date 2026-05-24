# Road to Sale

NADA-aligned dealership sales coaching, built on the AuditPro / SmartComply platform.

A sales rep walks a customer through a vehicle. The voice engine listens, detects workflow steps and feature demonstrations in real time, and builds the audit trail automatically.

---

## Repository layout

```
roadtosale/
│
├── vehicle-feature-catalog/   Brand-extensible vehicle catalog (YAML + Python + TS)
│                              What features exist, which trims carry them, how to detect them.
│
├── voice-engine/              Strategy-based STT + cue-matching engine (Python lab + TS library)
│   ├── lab/                   Offline comparison lab — runs Apple vs WhisperKit across test fixtures
│   ├── src/                   TS library skeleton — mobile-ready facade
│   ├── native/                Swift + JVM CLIs used by lab strategies (identical to iOS/Android production code)
│   └── ios/ android/          Native module stubs for the mobile app
│
├── road-to-sale-app/          Mobile app — future PRD
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

### Module boundaries

```
vehicle-feature-catalog   ←──── no cross-imports ────→   voice-engine
         ↑                                                      ↑
         └──────────── consumer (lab CLI, future app) ─────────┘
```

Neither module imports the other. The consumer (lab orchestrator, future mobile app) is the only place both are composed. `scripts/check_imports.py` enforces this in CI.

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

Latest results: `voice-engine/lab/runs/results/run-20260524-1142/`
- Apple SpeechTranscriber: FNR 4.5% clean → 8.6% at SNR +5 dB (showroom) · TTFC P95 330 ms
- WhisperKit: FNR 1.4% clean → 8.2% at SNR +5 dB · TTFC P95 610 ms

**Production recommendation:** Apple SpeechTranscriber for iOS. Lower TTFC (P95 330 ms vs 610 ms), near-parity FNR at the production operating point, no external dependency.

### `voice-engine/` (TS library)

Mobile-ready facade. Skeleton with mock strategy working; Apple native wiring deferred to the mobile app PRD.

| | |
|---|---|
| Language | TypeScript 5+ |
| API entry | `import { VoiceEngine } from 'voice-engine'` |
| Docs | `voice-engine/docs/{api,architecture,flows}.md` |

```bash
cd voice-engine
./build.sh          # npm install + vitest + tsc
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

Key decisions: D1–D18 (PRD-locked), DC1–DC44 (build-time), DC45+ (voice lab run results + architecture).

---

## Branch + commit conventions

See `~/.claude/CLAUDE.md` (global standards). Short version:
- **Never commit to `main`** — always branch
- Branch names: `feat/`, `fix/`, `refactor/`, `docs/`, `chore/`
- Conventional commits: `feat:`, `fix:`, `refactor:`, `BREAKING:`

Current implementation branch: `feat/voice-engine-catalog-v1`
