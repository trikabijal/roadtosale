# Test Coverage & Follow-up Backlog

_Created 2026-06-22. Updated 2026-06-22 after implementing the automatable
backlog. **Source of truth for the detailed, tier-grouped list = each module's
`docs/e2e-tests.md`** (linked below)._

**Status:** the automatable backlog is now **implemented** — ~127 new tests added
across the four modules (all suites green via `./test-all.sh`, no product code
changed). Repo test totals went from ~348 to ~469:

| Module | Before | After |
|--------|--------|-------|
| vehicle-feature-catalog | 79 | **126** (93 py + 33 ts) |
| voice-engine | 105 | **141** (114 py + 27 ts) |
| road-to-sale-app | 106 | **142** |
| dictation | 58 | **60** (51 DictationCore incl. 2 model-gated skips + 9 app) |

What remains is genuinely **manual / hardware / running-app** work that can't be
unit-automated (see "Still pending" below).

## Priority 1 — zero-test components — ✅ ALL DONE

| # | Module | Component | Status |
|---|--------|-----------|--------|
| 1 | road-to-sale-app | `src/db/RetryQueueConsumer.ts` | ✅ 14 tests — drain, `[1s,2s,4s,8s,16s]` backoff, **5-retry circuit breaker verified to trip at the 5th failure**. No bug. |
| 2 | voice-engine | `sherpa_onnx` strategy | ✅ 16-test fake-binary suite (parse, argv, model-env, 5 error paths, lazy-yield) |
| 3 | vehicle-feature-catalog | `scripts/derive_vocab.py` | ✅ 8 tests (schema, case-insensitive dedupe, ≤3-word synonym filter, per-make scoping) |
| 4 | vehicle-feature-catalog | `scripts/validate.py` CLI | ✅ 4 tests (exit codes + stderr vs valid/invalid/missing trees) |
| 5 | vehicle-feature-catalog | scraper `download.py` + `cli.py main()` | ✅ 11 tests (download idempotency/retry; CLI end-to-end, dry-run, exit codes) |

Plus many Priority-2 ledger items closed: TS↔Python cue-matcher **parity harness**
(voice-engine + vehicle-catalog, both verified to fail on injected drift),
cleanup-pack JSON loading (incl. RTS lexicon), client edge cases (401-refresh
dedup, multipart upload, logout, error class), repo retry-mutators, SessionEngine
cue/override, telemetry aggregation, FoundationModels cleanup-selection logic.

## Still pending — genuinely manual / hardware / running-app

These were deliberately NOT faked with shallow mocks:

- **road-to-sale-app** — the multi-screen black-box **E2E journeys J1–J11** (need a running app / Detox / simulator). `road-to-sale-app/tests/` stays reserved. The engine-level logic they decompose into is now covered at the contract level.
- **dictation** — hotkey→record→transcribe→clean→paste journeys, `ClipboardPaster` insertion, `CGEventTap` activation, `PermissionsService`, `RecordingHUD`, real `RecordingEngine` capture, real STT (needs WhisperKit weights), and the `AppState` orchestrator (its `init()` downloads models + installs the event tap — not headless-testable without product changes).
- **dictation iOS keyboard** — blocked: `DictationKeyboard/*.swift` are diagnostic stubs on `development` (real impl in commit `1554186`).
- **voice-engine** — reporting writers, the combined/semantic matcher (optional `fastembed` dep), and the manual real-audio benchmark lane (needs real models + audio).
- **vehicle-feature-catalog** — the `check_imports.py` boundary test (that script lives at repo-root `scripts/`, outside the module).

## Investigations (not test-writing)

- **Possible backend mismatch:** `SessionEngine.startSession` sends `submissionVersion: 0` / `shift: 'First'`, but `road-to-sale-app/docs/smartcomply-contract.md` shows `submissionVersion: 1` / `shift: 'MORNING'`. Confirm against the live SmartComply backend — may be a real bug, not doc drift.
- **iOS keyboard is stubbed on `development`** (see above) — restore from commit `1554186` when resuming iOS work.

## Done since the backlog was created

- ✅ **Build/deploy scripts** standardized across all sub-engines + root orchestrators + per-module READMEs with fail-loud prereq checks (verified by running every build+test).
