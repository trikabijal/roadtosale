# Tasks for PRD 0001 — Voice Engine v1 + Vehicle Feature Catalog v1

Source PRD: `dev/tasks/0001-prd-voice-engine.md`
Module docs:
- `vehicle-feature-catalog/docs/{api,architecture,flows}.md`
- `voice-engine/docs/{api,architecture,flows}.md`

Decisions log: `dev/tasks/decisions-log.md`

## Relevant Files

### vehicle-feature-catalog/
- `vehicle-feature-catalog/src/python/vehicle_feature_catalog/__init__.py` — Python facade entry point (re-exports `VehicleFeatureCatalog`).
- `vehicle-feature-catalog/src/python/vehicle_feature_catalog/facade.py` — `VehicleFeatureCatalog` class.
- `vehicle-feature-catalog/src/python/vehicle_feature_catalog/entities.py` — Dataclasses for `Make`, `Model`, `Trim`, `Feature`, `TrimFeature`.
- `vehicle-feature-catalog/src/python/vehicle_feature_catalog/loader.py` — YAML loader.
- `vehicle-feature-catalog/src/python/vehicle_feature_catalog/indexes.py` — In-memory indexes built post-load.
- `vehicle-feature-catalog/src/python/vehicle_feature_catalog/validator.py` — Integrity validator.
- `vehicle-feature-catalog/src/python/vehicle_feature_catalog/errors.py` — Error classes.
- `vehicle-feature-catalog/src/ts/index.ts` — TS facade entry point.
- `vehicle-feature-catalog/src/ts/facade.ts` — `VehicleFeatureCatalog` class.
- `vehicle-feature-catalog/src/ts/entities.ts` — TS interfaces matching the Python dataclasses.
- `vehicle-feature-catalog/src/ts/loader.ts` — YAML loader.
- `vehicle-feature-catalog/src/ts/indexes.ts` — In-memory indexes.
- `vehicle-feature-catalog/src/ts/validator.ts` — Integrity validator.
- `vehicle-feature-catalog/src/ts/errors.ts` — Error classes.
- `vehicle-feature-catalog/data/makes/honda.yaml` — Honda make.
- `vehicle-feature-catalog/data/makes/toyota.yaml` — Toy second-brand fixture make.
- `vehicle-feature-catalog/data/models/honda/cr-v-hybrid-awd.yaml` — Seed model.
- `vehicle-feature-catalog/data/models/toyota/*.yaml` — Toyota fixture model.
- `vehicle-feature-catalog/data/trims/honda/cr-v-hybrid-awd/2026/*.yaml` — Seed trims.
- `vehicle-feature-catalog/data/trims/toyota/**/*.yaml` — Toyota fixture trim.
- `vehicle-feature-catalog/data/features/universal/*.yaml` — Universal feature atoms.
- `vehicle-feature-catalog/data/features/honda/*.yaml` — Honda-branded feature atoms.
- `vehicle-feature-catalog/data/features/toyota/*.yaml` — Toyota fixture brand-specific feature.
- `vehicle-feature-catalog/data/matrix/honda.yaml` — Honda trim×feature ticks.
- `vehicle-feature-catalog/data/matrix/toyota.yaml` — Toyota fixture ticks.
- `vehicle-feature-catalog/scripts/validate.py` — CLI wrapper for catalog validation.
- `vehicle-feature-catalog/tests/python/test_loader.py`, `test_facade.py`, `test_validator.py` — Unit tests.
- `vehicle-feature-catalog/tests/ts/loader.test.ts`, `facade.test.ts`, `validator.test.ts` — Unit tests.
- `vehicle-feature-catalog/pyproject.toml`, `package.json`, `build.sh`, `README.md`.

### voice-engine/
- `voice-engine/src/index.ts` — TS library facade.
- `voice-engine/src/facade.ts` — `VoiceEngine` class.
- `voice-engine/src/types/index.ts` — Hand-written shared types (`TranscriptEvent`, `CueAtom`, `CueDetection`, `SessionContext`).
- `voice-engine/src/strategies/registry.ts`, `base.ts`, `apple-speech-transcriber.ts`, `mock.ts` — Strategy interface, registry, implementations.
- `voice-engine/src/matcher/cue-matcher.ts` — Phrase + synonym matcher.
- `voice-engine/src/capture/index.ts` — Audio capture placeholder.
- `voice-engine/ios/Sources/AppleSpeechTranscriberModule.swift` — Swift native module placeholder.
- `voice-engine/android/src/main/kotlin/.../Stub.kt` — Android stub.
- `voice-engine/lab/src/voice_lab/__init__.py` — Python lab facade entry point.
- `voice-engine/lab/src/voice_lab/facade.py` — `VoiceEngineLab` class.
- `voice-engine/lab/src/voice_lab/types.py` — Hand-written shared types.
- `voice-engine/lab/src/voice_lab/strategies/{registry,base,apple_speech_transcriber,argmax,mock}.py`.
- `voice-engine/lab/src/voice_lab/matcher/cue_matcher.py`.
- `voice-engine/lab/src/voice_lab/synthesis/{base,elevenlabs,noise}.py`.
- `voice-engine/lab/src/voice_lab/scoring/{classify,latency}.py`.
- `voice-engine/lab/src/voice_lab/reporting/{markdown,csv_writer}.py`.
- `voice-engine/lab/src/voice_lab/orchestrator.py`.
- `voice-engine/lab/src/voice_lab/cli.py`.
- `voice-engine/lab/cue-packs/universal_workflow_cues.yaml` — Workflow cues outside the catalog.
- `voice-engine/lab/fixtures/scripts/*.yaml` — Drafted scripts (≥ 2 in v1 build).
- `voice-engine/lab/tests/test_facade.py`, `test_matcher.py`, `test_orchestrator.py`, `test_drift.py`, ...
- `voice-engine/tests/facade.test.ts`, `matcher.test.ts`, ...
- `voice-engine/lab/pyproject.toml`, `voice-engine/package.json`, `voice-engine/build.sh`, `voice-engine/lab/build.sh`.

### dev/tasks/
- `dev/tasks/0001-prd-voice-engine.md` — PRD (already written).
- `dev/tasks/tasks-0001-prd-voice-engine.md` — this file.
- `dev/tasks/decisions-log.md` — Running decisions log.

### Notes

- Python: `pyproject.toml` per package; tests run via `pytest`.
- TS: `package.json` per package; tests run via `vitest` (or `jest` if simpler — pick at scaffold).
- Static import check (CI) enforces: no imports between `voice-engine/` and `vehicle-feature-catalog/` except shared type names (and even those are duplicated, not imported across modules).
- Cross-language type drift test: a YAML fixture is loaded by both Python and TS; both produce the same JSON output; mismatch fails CI.

## Tasks

- [x] 1.0 **Repo + commit hygiene baseline**
  - [x] 1.1 Commit the repo split (move auditpro/, design/ → demo/) on `chore/repo-split-demo-product`.
  - [x] 1.2 Commit PRD + module docs on the same branch.
  - [x] 1.3 Create new branch `feat/voice-engine-catalog-v1` for implementation.
  - [x] 1.4 Create `dev/tasks/decisions-log.md` capturing all v1 decisions and the deferred-open-question status.

- [x] 2.0 **Shared contracts (sequential prereq for everything downstream)**
  - [x] 2.1 Define Python dataclasses for `TranscriptEvent`, `CueAtom`, `CueDetection` in `voice-engine/lab/src/voice_lab/types.py`.
  - [x] 2.2 Define TS interfaces for the same in `voice-engine/src/types/index.ts` (`SessionContext` is TS-only).
  - [x] 2.3 Define Python dataclasses for `Make`, `Model`, `Trim`, `Feature`, `TrimFeature`, `ValidationResult` in `vehicle-feature-catalog/src/python/vehicle_feature_catalog/entities.py`.
  - [x] 2.4 Define TS interfaces for the same in `vehicle-feature-catalog/src/ts/entities.ts`.
  - [x] 2.5 Commit shared contracts.

- [x] 3.0 **Vehicle Feature Catalog implementation**
  - [x] 3.1 Python: YAML loader walking `data/{makes,models,trims,features,matrix}/`.
  - [x] 3.2 Python: in-memory indexes (`by_id`, `features_by_trim`, `trims_by_feature`).
  - [x] 3.3 Python: `VehicleFeatureCatalog` facade with all methods spec'd in `api.md`.
  - [x] 3.4 Python: `Validator` with the 5 integrity checks.
  - [x] 3.5 Python: error classes (`NotFoundError`, `LoadError`, `DuplicateIdError`).
  - [x] 3.6 Python: `scripts/validate.py` CLI wrapper.
  - [x] 3.7 Python: unit tests for loader, facade, validator.
  - [x] 3.8 TS: mirror of 3.1–3.6 in TypeScript.
  - [x] 3.9 TS: unit tests for loader, facade, validator.
  - [x] 3.10 `pyproject.toml`, `package.json`, `build.sh`, `README.md`.
  - [x] 3.11 Cross-language drift test: load a known fixture with both loaders, assert identical JSON output.
  - [x] 3.12 Commit catalog implementation.

- [x] 4.0 **Vehicle Feature Catalog — seed data**
  - [x] 4.1 Honda Make file.
  - [x] 4.2 Honda CR-V Hybrid AWD 2026 Model file.
  - [x] 4.3 Honda CR-V Hybrid AWD 2026 trims (Sport, Sport-L, Sport Touring per Honda US brochure). _(Full 2026 Honda lineup extracted from hondanews press releases — all 9 models.)_
  - [x] 4.4 Universal feature files for the features the CR-V Hybrid exposes (Wireless Apple CarPlay, Wireless Android Auto, Heated Front Seats, Panoramic Moonroof, Hands-Free Power Tailgate, etc.).
  - [x] 4.5 Honda brand-specific feature files (Honda Sensing 360+, Real-Time AWD with Intelligent Control, Bose Premium Audio if Honda-branded, HondaLink, etc.).
  - [x] 4.6 `matrix/honda.yaml` — trim × feature ticks per the brochure.
  - [x] 4.7 Toy Toyota fixture: 1 Make, 1 Model, 1 Trim, 1 brand-specific feature, shared universal features.
  - [x] 4.8 `matrix/toyota.yaml`.
  - [x] 4.9 Run `voice-lab validate-catalog` (after catalog impl lands) — must pass.
  - [x] 4.10 Commit seed data.

- [x] 5.0 **Voice Engine core — strategy + matcher + facade (Python + TS)**
  - [x] 5.1 Python: `TranscriptionStrategy` ABC in `strategies/base.py`.
  - [x] 5.2 Python: strategy registry in `strategies/registry.py`.
  - [x] 5.3 Python: `cue_matcher.py` — phrase + synonym matching against transcript stream.
  - [x] 5.4 Python: `VoiceEngineLab` facade with `list_strategies`, `get_strategy`, `transcribe_file`, `match_cues`.
  - [x] 5.5 Python: `MockTranscriptionStrategy` (replays a JSONL event file — used by tests).
  - [x] 5.6 Python: stub `AppleSpeechTranscriberStrategy` and `ArgmaxStrategy` — registered but raise `NotImplementedError` with a pointer to OQ1 / OQ2.
  - [x] 5.7 Python: unit tests for matcher, facade, mock strategy.
  - [x] 5.8 TS: `TranscriptionStrategy` interface in `strategies/base.ts`.
  - [x] 5.9 TS: strategy registry in `strategies/registry.ts`.
  - [x] 5.10 TS: `cue-matcher.ts`.
  - [x] 5.11 TS: `VoiceEngine` facade with `listStrategies`, `getStrategy`, `startSession`, `matchCues`.
  - [x] 5.12 TS: `MockTranscriptionStrategy` (live event replay).
  - [x] 5.13 TS: stub `AppleSpeechTranscriberStrategy` — registered but throws `NotImplementedError` with pointer to OQ1.
  - [x] 5.14 TS: unit tests for matcher, facade, mock strategy.
  - [x] 5.15 `voice-engine/package.json`, `voice-engine/build.sh`, `voice-engine/lab/build.sh`, `voice-engine/lab/pyproject.toml`.
  - [x] 5.16 iOS native module placeholder (`voice-engine/ios/Sources/AppleSpeechTranscriberModule.swift`).
  - [x] 5.17 Android stub directory.
  - [x] 5.18 Cross-language drift test (Python `TranscriptEvent` round-trips through TS loader, fields identical).
  - [x] 5.19 Commit voice engine core.

- [x] 6.0 **Universal workflow cues + drafted scripts**
  - [x] 6.1 Draft `voice-engine/lab/cue-packs/universal_workflow_cues.yaml` from the audio script in `demo/auditpro-rn-showcase/src/services/audio/audioScript.ts` + the test matrix doc.
  - [x] 6.2 Draft 2 example scripts targeting Honda CR-V Hybrid AWD 2026 Sport Touring (one positive, one with a deliberately omitted cue → negative). _(16 scripts total across 8 voice profiles.)_
  - [x] 6.3 Commit cue inventory + sample scripts.

- [x] 7.0 **Lab orchestrator + scoring + reporting + CLI (Python only)**
  - [x] 7.1 `orchestrator.py` — composes catalog + engine, runs the per-script loop, projects `Feature → CueAtom`, merges with universal atoms.
  - [x] 7.2 `scoring/classify.py` — pass/partial/fail rules per `dev/docs/ROAD_TO_SALE_AUDIO_TEST_MATRIX.md`.
  - [x] 7.3 `scoring/latency.py` — P50 / P95 / P99 computation per strategy.
  - [x] 7.4 `synthesis/base.py`, `synthesis/elevenlabs.py` (stub — flagged for OQ3 voice IDs + API key), `synthesis/noise.py` (stub — flagged for OQ7 noise track). _(ElevenLabs fully wired; noise overlay implemented.)_
  - [x] 7.5 `reporting/markdown.py` — `summary.md` writer.
  - [x] 7.6 `reporting/csv_writer.py` — per-strategy CSV + false-positives CSV.
  - [x] 7.7 `cli.py` — `synth`, `run`, `validate-catalog` subcommands.
  - [x] 7.8 Unit tests for orchestrator (with mock strategy), scoring, reporting.
  - [x] 7.9 Smoke test: end-to-end `voice-lab run --strategies mock` produces a valid report against the 2 drafted scripts using the seed catalog.
  - [x] 7.10 Commit lab orchestrator.

- [x] 8.0 **Cross-cutting glue**
  - [x] 8.1 Static import check script (`scripts/check_imports.py` at repo root or per-module) — fails if `voice-engine/` imports from `vehicle-feature-catalog/` or vice versa.
  - [x] 8.2 Top-level README updated with the new layout.
  - [x] 8.3 `voice-engine/README.md`, `vehicle-feature-catalog/README.md`, `voice-engine/lab/README.md` — one-line purpose + how to build / test.
  - [x] 8.4 Final decisions-log update for anything decided during build.
  - [x] 8.5 Commit glue + final docs.

- [ ] 9.0 **Deferred / blocked work (NOT in v1 build — captured for future PRDs)**
  - 9.1 Real Apple SpeechTranscriber wiring — blocked on OQ1 (verify macOS + iOS API parity).
  - 9.2 Real Argmax wiring — blocked on OQ2 (Python bindings? CLI?) + license.
  - 9.3 Real ElevenLabs synthesis — blocked on OQ3 (voice IDs + API key).
  - 9.4 Dealership noise track sourcing — blocked on OQ7.
  - 9.5 Remaining 8 Honda models (Civic, Accord, HR-V, Pilot, Passport, Odyssey, Ridgeline, Prologue) data extraction — blocked on OQ5 (extraction owner).
  - 9.6 Additional script variants (10–13 more) — blocked on OQ6 cue inventory acceptance + script-sourcing split per OQ12 + OQ13 fair-use sign-off.
  - 9.7 Human calibration anchor recording — blocked on OQ9 protocol.
  - 9.8 Telemetry exporter — separate PRD.
  - 9.9 CI / release pipeline — comes after library is past skeleton.
