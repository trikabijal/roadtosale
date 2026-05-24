# Voice lab run — run-20260524-1221

Generated: `2026-05-24T12:51:06.393873+00:00`

## Outcomes per strategy

| Strategy | Detected | Not said | FNR | FPR | via Exact | via Semantic | Semantic lift |
|---|---:|---:|---:|---:|---:|---:|---:|
| sherpa_onnx | 766 | 114 | 13.0% | 0.0% | 638 | 128 | +128 (17%) |

_**Not said** = cue was expected but dealer never said it — coaching finding, not an engine error._ _**Semantic lift** = extra cues caught only by the embedding layer._

## Engine performance metrics

Thresholds from telemetry doc: TTFT good <300 ms · TTFC good <500 ms · RTF <1.0 = faster than real-time

| Strategy | TTFT P50 (ms) | TTFT P95 (ms) | TTFinal P50 (ms) | TTFinal P95 (ms) | TTFC P50 (ms) | TTFC P95 (ms) | RTF avg | RTF P95 | Partials/file | Finals/file | Events/s |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| sherpa_onnx | 1140 | 1292 | 1240 | 1392 | 1242 | 1408 | 0.04 | 0.04 | 24 | 24 | 0.1 |

## Detection latency per strategy

_Wall-clock time from audio start to each cue detection event._

| Strategy | Count | P50 (ms) | P95 (ms) | P99 (ms) |
|---|---:|---:|---:|---:|
| sherpa_onnx | 16435 | 20821 | 61606 | 81118 |

## Timing breakdown per strategy

_Average wall-clock per phase, per file._

| Strategy | Transcription (ms) | Matching (ms) | Classification (ms) | Total (ms) |
|---|---:|---:|---:|---:|
| sherpa_onnx | 25865.7 | 1606.6 | 1.8 | 27474.1 |
