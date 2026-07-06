# f6 — Telemetry

**Facade:** `TelemetryStore` actor over GRDB SQLite; `TranscriptRecord`, `WeeklyStats`, `UsageTotals`.

## E2E facade ledger (all ✅)

| Facade | Behavior | Expected | Tier | Status |
|---|---|---|---|---|
| `save` + `fetchWeeklyStats` | aggregate in-window records | correct totals/avgs | 1 | ✅ |
| `markCorrected` | flag a row | correctionRate rises | 1 | ✅ |
| `fetchUsageTotals` | all-time sum | ignores the weekly window | 1 | ✅ |
| stats on empty store | | all zero | 1 | ✅ |
| `search` | substring | matches, newest first | 1 | ✅ |
| `purge(olderThanDays:)` | retention | drops old rows only | 1 | ✅ |
| App-Group cross-process (iOS) | host ↔ extension | shared store readable both ways | 3 | ⛔ iOS-blocked |

## Unit inventory: ✅ `TelemetryRetentionTests` (6). Uses a tmp DB per test (isolated).

## Deferred: iOS App-Group cross-process (needs the real keyboard extension, commit 1554186).

## Checklist grade: **state & data integrity** ✅ (retention window, distinct-record tallies, empty-store zero).
