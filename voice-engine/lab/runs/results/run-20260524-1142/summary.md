# Voice lab run — run-20260524-1142

Generated: `2026-05-24T11:46:34.138059+00:00`

## Outcomes per strategy

| Strategy | Detected | Not said | FNR | FPR | via Exact | via Semantic | Semantic lift |
|---|---:|---:|---:|---:|---:|---:|---:|
| apple_speech_transcriber | 800 | 80 | 9.1% | 0.0% | 702 | 98 | +98 (12%) |
| whisperkit | 812 | 68 | 7.7% | 0.0% | 666 | 146 | +146 (18%) |

_**Not said** = cue was expected but dealer never said it — coaching finding, not an engine error._ _**Semantic lift** = extra cues caught only by the embedding layer._

## Engine performance metrics

Thresholds from telemetry doc: TTFT good <300 ms · TTFC good <500 ms · RTF <1.0 = faster than real-time

| Strategy | TTFT P50 (ms) | TTFT P95 (ms) | TTFinal P50 (ms) | TTFinal P95 (ms) | TTFC P50 (ms) | TTFC P95 (ms) | RTF avg | RTF P95 | Partials/file | Finals/file | Events/s |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| apple_speech_transcriber | 60 | 120 | 107 | 258 | 116 | 330 | 0.00 | 0.00 | 2925 | 180 | 4.7 |
| whisperkit | 86 | 204 | 434 | 629 | 217 | 610 | 0.00 | 0.00 | 2956 | 217 | 4.2 |

## Detection latency per strategy

_Wall-clock time from audio start to each cue detection event._

| Strategy | Count | P50 (ms) | P95 (ms) | P99 (ms) |
|---|---:|---:|---:|---:|
| apple_speech_transcriber | 27833 | 3970 | 21936 | 28693 |
| whisperkit | 76859 | 2660 | 24984 | 34537 |

## Timing breakdown per strategy

_Average wall-clock per phase, per file._

| Strategy | Transcription (ms) | Matching (ms) | Classification (ms) | Total (ms) |
|---|---:|---:|---:|---:|
| apple_speech_transcriber | 7.3 | 1935.5 | 3.3 | 1946.0 |
| whisperkit | 10.1 | 2144.5 | 3.2 | 2157.8 |
