# WhisperKit Failure Findings

> Catalogue of WhisperKit failure patterns observed during sustained daily use of Just Talk.
> Each finding feeds **Road to Sale STT calibration** (see the telemetry rationale in
> [architecture.md](architecture.md)).

**Status: none yet — the app is not yet in sustained daily use.** Telemetry is being captured
(`TelemetryStore`, with a `wasCorrected` flag per transcript), but no failure patterns have
accumulated to catalogue. This file is the home for those findings once they do.

## Format (to use when findings arrive)

Each finding:
- **Condition** — what was happening when it failed.
- **Observed behaviour** — what went wrong (hallucination / dropped speech / wrong word / truncation).
- **Estimated failure rate** — from the telemetry correction rate.
- **Road to Sale implication** — what it means for dealership cue detection.

## Findings

_None yet._

## Open questions for lab

_None yet._
