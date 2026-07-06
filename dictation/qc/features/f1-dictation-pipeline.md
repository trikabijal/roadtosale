# f1 — Dictation pipeline (record → transcribe → clean → insert)

**Facade:** the whole flow through `AppState` (macOS orchestrator) + `StreamingDictationSession`. The
primary user journey: activate → speak → cleaned text appears in the focused app; audio never lost.

## E2E facade ledger

| Facade | Behavior | Inputs/State | Expected | Tier | Notes |
|---|---|---|---|---|---|
| dictate → clean (contract) | mock STT → cleanup | fixture | polished text assembled | 1 | ✅ `PipelineTests` |
| `StreamingDictationSession.finish` | assemble raw, clean once at stop | segments | raw joined; one cleanup pass; no priorContext | 1 | ✅ `StreamingDictationTests` (8) |
| session | thrown segment | transient STT failure | `incomplete=true` → caller re-transcribes full audio | 1 | ✅ |
| session | silent segment | empty | not incomplete | 1 | ✅ |
| J2 dictate into another app | activate→speak→end | fixture | cleaned sentence at cursor; HUD listening→processing→gone; prior clipboard intact | 1 (core) | ⛔ AppState+paste seam |
| J6 recover a bad dictation | garbled/empty STT | | no junk pasted; audio preserved; Retry recovers | 1 | ⛔ AppState seam |
| J7 model hang | transcribe/clean never returns | | falls back within deadline; HUD unstuck; typing responsive | 1 | ⚠️ `TimeoutTests` proves the primitive; app-level ⛔ |
| J13 sustained soak | many back-to-back under load | | every one inserts; no freeze | 1 | ⛔ soak harness |

## Unit inventory
| Module | Interface | Priority | Status |
|---|---|---|---|
| `StreamingDictationSession` | `ingest`, `finish`, `confirmedText` | Critical | ✅ (8) |
| `Timeout` | `withTimeout` race | Critical | ✅ `TimeoutTests` (3, incl. fires-when-op-ignores-cancellation) |
| `AppState` orchestration | start/stop/finalize/retry/timeout-recover | Critical | ⛔ needs a fixture-PCM capture seam + mock STT |

## Deferred (need an AppState test seam: inject capture PCM + MockTranscriber)
Happy path (J2), never-lose-audio (J6), app-level timeout fallback (J7), per-context cleanup override,
correction window (J9), soak (J13).

## Checklist grade
- **side-effect-rollback** ✅ (unit) — a thrown segment marks `incomplete` so the caller re-runs, never pasting a truncated result.
- **error-surfacing** — app-level (retry/HUD) is ⛔ pending the AppState seam.
