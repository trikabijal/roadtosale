# Voice lab run — run-20260524-1342

Generated: `2026-05-24T14:12:03.080680+00:00`

## Outcomes per strategy

| Strategy | Detected | Not said | FNR | FPR | via Exact | via Semantic | Semantic lift |
|---|---:|---:|---:|---:|---:|---:|---:|
| sherpa_onnx | 735 | 145 | 16.5% | 0.0% | 568 | 167 | +167 (23%) |

_**Not said** = cue was expected but dealer never said it — coaching finding, not an engine error._ _**Semantic lift** = extra cues caught only by the embedding layer._

## Engine performance metrics

Thresholds from telemetry doc: TTFT good <300 ms · TTFC good <500 ms · RTF <1.0 = faster than real-time

| Strategy | TTFT P50 (ms) | TTFT P95 (ms) | TTFinal P50 (ms) | TTFinal P95 (ms) | TTFC P50 (ms) | TTFC P95 (ms) | RTF avg | RTF P95 | Partials/file | Finals/file | Events/s |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| sherpa_onnx | 318 | 1485 | 368 | 1535 | 534 | 1612 | 0.04 | 0.05 | 91 | 91 | 0.3 |

## Detection latency per strategy

_Wall-clock time from audio start to each cue detection event._

| Strategy | Count | P50 (ms) | P95 (ms) | P99 (ms) |
|---|---:|---:|---:|---:|
| sherpa_onnx | 17948 | 21880 | 68436 | 90094 |

## Timing breakdown per strategy

_Average wall-clock per phase, per file._

| Strategy | Transcription (ms) | Matching (ms) | Classification (ms) | Total (ms) |
|---|---:|---:|---:|---:|
| sherpa_onnx | 25737.7 | 2004.5 | 2.2 | 27744.4 |
