# Test Coverage & Follow-up Backlog

_Created 2026-06-22. This is the consolidated work list of tests still owed and
related follow-ups, produced from the per-module test plans. **Source of truth
for the detailed, tier-grouped list = each module's `docs/e2e-tests.md`
"Pending backlog" section** (linked below). Nothing here has been implemented
yet — these are work items, deliberately deferred._

## Priority 1 — components with ZERO tests

These have real, load-bearing logic and no test coverage at all:

| # | Module | Component | Why it matters |
|---|--------|-----------|----------------|
| 1 | road-to-sale-app | `src/db/RetryQueueConsumer.ts` | The offline-first drain / `[1s,2s,4s,8s,16s]` backoff / **5-retry circuit breaker** the whole app's reliability rests on. **Highest-value gap.** |
| 2 | voice-engine | `sherpa_onnx` strategy | The same STT library Android ships. Apple/WhisperKit have full fake-binary suites; this has none. |
| 3 | vehicle-feature-catalog | `scripts/derive_vocab.py` | The catalog→STT-vocab bridge that feeds voice-engine cleanup packs. No schema/dedupe/scope tests. |
| 4 | vehicle-feature-catalog | `scripts/validate.py` CLI | The CI boundary gate (exit codes / stderr) is unasserted. |
| 5 | vehicle-feature-catalog | `scrapers/honda_us/download.py` + `cli.py main()` | Scraper units are tested; the end-to-end CLI and download layer are not. |

## Priority 2 — pending test suites (counts from the plans)

| Module | Pending | Headline |
|--------|---------|----------|
| [road-to-sale-app](../../road-to-sale-app/docs/e2e-tests.md) | ~20 tests | The **entire black-box E2E journey suite (J1–J11)** — `road-to-sale-app/tests/` is an empty reserved dir; all 106 existing tests are single-facade unit tests. |
| [voice-engine](../../voice-engine/docs/e2e-tests.md) | 13 tests + manual benchmark lane | TS↔Python cue-matcher parity unverified; cleanup-pack JSON loading unguarded. |
| [vehicle-feature-catalog](../../vehicle-feature-catalog/docs/e2e-tests.md) | 23 tests | Python↔TS parity is convention not assertion; real `data/` never loaded by a test. |
| [dictation](../../dictation/docs/e2e-tests.md) | 22 tests | Contracts well-covered (49 tests); the end-to-end hotkey→record→transcribe→clean→paste journey + platform adapters are manual. |

## Investigations (not test-writing)

- **Possible backend mismatch:** `SessionEngine.startSession` sends `submissionVersion: 0` / `shift: 'First'`, but `road-to-sale-app/docs/smartcomply-contract.md` shows `submissionVersion: 1` / `shift: 'MORNING'`. Confirm against the live SmartComply backend — may be a real bug, not doc drift.
- **iOS keyboard is stubbed on `development`:** `dictation/DictationKeyboard/*.swift` are currently diagnostic stubs; the real implementation is in git history at commit `1554186` (side effect of the in-flight `perf/dictation-speed` work). iOS-adapter tests are blocked until it's restored.

## Deferred infrastructure (separate workstream)

- **Build/deploy scripts** standardization across all sub-engines + optional CI workflow — tracked separately, not part of this test backlog.
