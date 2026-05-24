# Voice lab run — run-20260524-0745

Generated: `2026-05-24T07:46:49.200022+00:00`

## Outcomes per strategy

| Strategy | Detected | Not said | FNR | FPR | via Exact | via Semantic | Semantic lift |
|---|---:|---:|---:|---:|---:|---:|---:|
| apple_speech_transcriber | 210 | 10 | 4.5% | 0.0% | 199 | 11 | +11 (5%) |
| whisperkit | 217 | 3 | 1.4% | 0.0% | 198 | 19 | +19 (9%) |

_**Not said** = cue was expected but dealer never said it — coaching finding, not an engine error._ _**Semantic lift** = extra cues caught only by the embedding layer._

## Engine performance metrics

Thresholds from telemetry doc: TTFT good <300 ms · TTFC good <500 ms · RTF <1.0 = faster than real-time

| Strategy | TTFT P50 (ms) | TTFT P95 (ms) | TTFinal P50 (ms) | TTFinal P95 (ms) | TTFC P50 (ms) | TTFC P95 (ms) | RTF avg | RTF P95 | Partials/file | Finals/file | Events/s |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| apple_speech_transcriber | 58 | 110 | 100 | 247 | 112 | 272 | 0.00 | 0.00 | 3123 | 193 | 4.8 |
| whisperkit | 85 | 222 | 516 | 929 | 168 | 618 | 0.00 | 0.00 | 2510 | 220 | 3.7 |

## Detection latency per strategy

_Wall-clock time from audio start to each cue detection event._

| Strategy | Count | P50 (ms) | P95 (ms) | P99 (ms) |
|---|---:|---:|---:|---:|
| apple_speech_transcriber | 8663 | 4152 | 18559 | 23509 |
| whisperkit | 23120 | 4192 | 28694 | 37820 |

## Timing breakdown per strategy

_Average wall-clock per phase, per file._

| Strategy | Transcription (ms) | Matching (ms) | Classification (ms) | Total (ms) |
|---|---:|---:|---:|---:|
| apple_speech_transcriber | 8.4 | 1975.6 | 3.2 | 1987.2 |
| whisperkit | 8.5 | 2081.6 | 3.2 | 2093.3 |
