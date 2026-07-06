# f2 — Live pill (streaming)

**Facades:** `StreamingTranscriber` protocol (`step`/`reset`); pure `StreamingAgreement` (LocalAgreement-2);
`WhisperKitStreamingSession`, `AppleStreamingSession`. Preview only — the pasted text is the batch pass,
so the pill can never corrupt output.

## E2E facade ledger

| Facade | Behavior | Inputs/State | Expected | Tier | Notes |
|---|---|---|---|---|---|
| `StreamingAgreement.integrate` | below window | < requiredUnconfirmed segs | all hypothesis, nothing confirmed | 1 | ✅ |
| `StreamingAgreement.integrate` | beyond window | > window | confirm all-but-N; tail = hypothesis | 1 | ✅ |
| `StreamingAgreement.integrate` | tail revised next pass | "fax"→"fox" | confirmed prefix never rewritten | 1 | ✅ |
| `StreamingAgreement.integrate` | whole-buffer re-decode | dup segments | no duplication; `lastConfirmedEnd` monotonic | 1 | ✅ |
| `StreamingAgreement.flushHypothesis` | at stop | | trailing hypothesis promoted | 1 | ✅ |
| `WhisperKitStreamingSession.sanitize` | timestamp tokens | `<|5.90|>` | stripped | 1 | ✅ |
| streaming session | pill drives pill only | streaming on/off | pasted text byte-identical (batch) | 2 | ⛔ AppState seam |
| `WhisperKitStreamingSession.step` | live growing buffer | fixture | confirmed grows monotonically, matches batch prefix | 3 | model-gated |
| `AppleStreamingSession` | volatile + finalized | fixture | finalized append-only, volatile as tail | 3 | model-gated |

## Unit inventory
| Module | Interface | Priority | Status |
|---|---|---|---|
| `StreamingAgreement` | `integrate`, `flushHypothesis`, `confirmedText`/`hypothesisText` | Critical | ✅ `StreamingAgreementTests` (8) |
| `WhisperKitStreamingSession.sanitize` | token strip | High | ✅ (in StreamingAgreementTests) |
| `StreamingDictationSession` | `ingest`, `finish` (assembly + once-at-stop cleanup) | Critical | ✅ `StreamingDictationTests` (8) |
| `StreamingTranscript` | `confirmed`/`hypothesis`/`display`/`empty` | Low | trivial DTO |

## Deferred (Tier 3, model-gated)
Live WhisperKit/Apple streaming sessions; the batch-output-unchanged seam (needs an AppState/mock seam).

## Checklist grade
- **state & data integrity** ✅ — confirmed is append-only (tail-revision test proves no rewrite); monotonic `lastConfirmedEnd`.
