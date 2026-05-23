# Voice lab run — run-20260523-1955

Generated: `2026-05-23T19:57:49.299430+00:00`

## Outcomes per strategy

| Strategy | Pass | Partial | Fail | False positive | Total | FNR | FPR |
|---|---:|---:|---:|---:|---:|---:|---:|
| apple_speech_transcriber | 93 | 0 | 21 | 0 | 114 | 18.4% | 0.0% |

## Engine performance metrics

Thresholds from telemetry doc: TTFT good <300 ms · TTFC good <500 ms · RTF <1.0 = faster than real-time

| Strategy | TTFT P50 (ms) | TTFT P95 (ms) | TTFinal P50 (ms) | TTFinal P95 (ms) | TTFC P50 (ms) | TTFC P95 (ms) | RTF avg | RTF P95 | Partials/file | Finals/file | Events/s |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| apple_speech_transcriber | 54 | 98 | 146 | 226 | 276 | 1958 | 0.01 | 0.02 | 4729 | 290 | 4.8 |

## Detection latency per strategy

_Wall-clock time from audio start to each cue detection event._

| Strategy | Count | P50 (ms) | P95 (ms) | P99 (ms) |
|---|---:|---:|---:|---:|
| apple_speech_transcriber | 2384 | 4614 | 18258 | 21725 |

## Timing breakdown per strategy

_Average wall-clock per phase, per file._

| Strategy | Transcription (ms) | Matching (ms) | Classification (ms) | Total (ms) |
|---|---:|---:|---:|---:|
| apple_speech_transcriber | 12967.1 | 150.5 | 0.0 | 13117.6 |
