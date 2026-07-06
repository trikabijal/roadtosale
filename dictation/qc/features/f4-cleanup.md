# f4 — Cleanup

**Facades:** `TextCleanup` protocol (`clean` never throws); `RuleBasedCleanup`, `FoundationModelsCleanup`;
`CleanupOutputSanitizer`; `CleanupPack`/`CleanupPackLoader`; `TextCleanupFactory`. Cleanup must never
block paste and must never drop content (RCA: cleanup content-drop) or echo the prompt (RCA: F10).

## E2E facade ledger (all ✅ unless noted)

| Facade | Behavior | Expected | Tier | Status |
|---|---|---|---|---|
| `clean(.off)` | pass-through | raw unchanged | 1 | ✅ |
| `clean(.full)` rule-based | fillers + caps + repeats | tidied, non-degenerate | 1 | ✅ |
| `clean(.light)` | preserve wording | no repeat-collapse | 1 | ✅ |
| command grammar | "new paragraph" | → `\n\n` | 1 | ✅ |
| vocab post-pass | forced spelling | applied after cleanup | 1 | ✅ |
| decimals | "3.5" | not mangled | 1 | ✅ |
| **content-drop guard** | cleanup drops leading/trailing clause | degenerate → fall back | 1 | ✅ (4 tests) |
| **prompt echo (F10)** | "Known names and terms…" | stripped | 1 | ✅ |
| tags/labels | `<transcript>`, "Dictated text:" | stripped | 1 | ✅ |
| degenerate | ballooned / repeated-twice | fall back | 1 | ✅ |
| hallucination filter | junk phrase on short/low-conf | dropped; real speech kept | 1 | ✅ |
| cleanup packs | bundled loads; matches fallback (drift); road-to-sale lexicon | | 1 | ✅ |
| FM `clean` | model unavailable/timeout | falls back, `usedFallback`, never blocks | 2 | ✅ (unit) |
| FM `responseTimeout` | scale/floor/ceiling, monotonic | | 1 | ✅ |
| FM `taskPrompt` | frames text as data not a question | | 1 | ✅ |
| FM full path w/ real model | question cleaned not answered | | 3 | ⛔ model-gated |

## Unit inventory (✅ 34 CleanupTests + 6 FoundationModelsCleanupTests)
`RuleBasedCleanup`, `CleanupOutputSanitizer` (sanitize + isDegenerate + content-drop guard),
`CleanupPack`/loader (+ drift guard), `TextCleanupFactory`, `FoundationModelsCleanup` (timeout/prompt/fallback).

## Deferred (Tier 3): live FM model behavior (question-not-answered, long-transcript scaled timeout) — model-gated.

## Checklist grade
- **error-surfacing / content integrity** ✅ — content-drop guard + echo strip + never-throws are all pinned.
