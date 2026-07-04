# Just Talk — QC index

The QA control center for the dictation deployable (per global CLAUDE.md §13). This indexes the
test **plans**; the executable test **code** lives where the build forces it and is referenced here.

## Master test plan

The full cross-platform plan (tiers, Facade Coverage Ledger, journey suite, coverage ledger vs the
real test files, and the pending backlog) lives at:

- **[../docs/e2e-tests.md](../docs/e2e-tests.md)** — the authoritative plan + coverage ledger.

## Executable tests

- **Contract / unit (fast, model-free):** `../Shared/Tests/DictationCoreTests/` — 72 methods.
  Run: `cd dictation && ./test.sh core`.
- **macOS adapter (hotkey config):** `../JustTalkTests/` — 9 methods. Run: `./test.sh app`.

## Feature coverage

| Feature | Plan IDs | Status | Tests |
|---|---|---|---|
| Cleanup (rule-based + packs + sanitizer) | T-CLN-* | ✅ well covered | `CleanupTests`, `FoundationModelsCleanupTests` |
| Capture buffer ordering | T-CAP-1 (unit) | ✅ | `CapturedAudioStreamTests` |
| Recording store + audio bridge | T-PERSIST-* | ✅ | `RecordingStoreTests` |
| Telemetry retention | T-TEL (partial) | ⚠️ | `TelemetryRetentionTests` |
| Timeout race | (primitive) | ✅ | `TimeoutTests` |
| **Streaming pill — LocalAgreement-2 + sanitizer (PRD 0008)** | T-STR-1,2 | ✅ | `StreamingAgreementTests` (8) |
| Streaming session assembly order | (T-FLOW support) | ✅ | `StreamingDictationTests` (8) |
| **Apple SpeechAnalyzer provider (PRD 0008)** | T-APL-1..4 | ⛔ pending | 14a/14b automatable now (gating, converter); live needs device |
| Real STT / capture engine / insertion / permissions / HUD / AppState orchestrator | T-STT/CAP/INS/PERM/HUD/FLOW | ⛔ pending | manual — see plan §8 |

## Bugs

Per-bug test plans (permanent, per the bug→test→fold lifecycle) live under **[bugs/](bugs/)**.

## Highest-value next tests (no model needed)

From the plan backlog §9: **14a** Apple `isAvailable` gating, **14b** `AppleAudioConverter`
round-trip, **14c** batch-output-unchanged seam (streaming is preview only). All pure/mockable.
