# Voice lab run — run-20260524-1051

Generated: `2026-05-24T10:59:15.190224+00:00`

## Outcomes per strategy

| Strategy | Detected | Not said | FNR | FPR | via Exact | via Semantic | Semantic lift |
|---|---:|---:|---:|---:|---:|---:|---:|
| apple_speech_transcriber | 1378 | 162 | 10.5% | 0.0% | 1185 | 193 | +193 (14%) |
| whisperkit | 1402 | 138 | 9.0% | 0.0% | 1111 | 291 | +291 (21%) |

_**Not said** = cue was expected but dealer never said it — coaching finding, not an engine error._ _**Semantic lift** = extra cues caught only by the embedding layer._

## Engine performance metrics

Thresholds from telemetry doc: TTFT good <300 ms · TTFC good <500 ms · RTF <1.0 = faster than real-time

| Strategy | TTFT P50 (ms) | TTFT P95 (ms) | TTFinal P50 (ms) | TTFinal P95 (ms) | TTFC P50 (ms) | TTFC P95 (ms) | RTF avg | RTF P95 | Partials/file | Finals/file | Events/s |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| apple_speech_transcriber | 64 | 167 | 120 | 292 | 134 | 414 | 0.00 | 0.00 | 2890 | 182 | 4.6 |
| whisperkit | 81 | 300 | 446 | 966 | 221 | 659 | 0.00 | 0.00 | 2888 | 226 | 4.2 |

## Detection latency per strategy

_Wall-clock time from audio start to each cue detection event._

| Strategy | Count | P50 (ms) | P95 (ms) | P99 (ms) |
|---|---:|---:|---:|---:|
| apple_speech_transcriber | 45584 | 4144 | 21720 | 28037 |
| whisperkit | 130471 | 2448 | 26462 | 35598 |

## Timing breakdown per strategy

_Average wall-clock per phase, per file._

| Strategy | Transcription (ms) | Matching (ms) | Classification (ms) | Total (ms) |
|---|---:|---:|---:|---:|
| apple_speech_transcriber | 7.3 | 1911.1 | 3.1 | 1921.6 |
| whisperkit | 10.3 | 2130.0 | 3.1 | 2143.3 |
