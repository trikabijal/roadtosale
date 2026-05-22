# WhisperKitSTT — Swift CLI for the voice-engine lab

A standalone Swift Package Manager executable that wraps Argmax's
open-source [WhisperKit](https://github.com/argmaxinc/WhisperKit) (MIT,
on-device Whisper for Apple platforms) and emits JSONL transcript
events on stdout. The Python lab invokes this binary as a subprocess;
see `voice-engine/lab/src/voice_lab/strategies/whisperkit.py`.

This is a sibling to the AppleSTT CLI (`../AppleSTT/`). AppleSTT wraps
Apple's first-party Speech framework; WhisperKitSTT wraps WhisperKit
(open-source Whisper inference). The two share the JSONL output
contract on purpose.

## Why WhisperKit alongside the Argmax Pro SDK strategy

WhisperKit is the **free**, MIT-licensed core that Argmax's paid Pro SDK
is built on. The Pro SDK adds real-time hypothesis/confirmed streaming,
3,000-keyword custom-vocab boosting, Android support, and a Deepgram-
compatible Local Server. For lab evaluation on macOS the open-source
WhisperKit is sufficient and avoids the $14 trial fee / device-license
process documented in `dev/docs/ROAD_TO_SALE_AUDIO_RESEARCH_ARGMAX.md`.

| Surface | Open-source WhisperKit | Argmax Pro SDK |
|---|---|---|
| License | MIT | Commercial, $1.00-$1.33/device/month |
| Models | OpenAI Whisper (CoreML) | + NVIDIA Parakeet (default), Sortformer |
| Streaming | Simulated via segment callbacks + decoder progress | Native Confirmed + Hypothesis async stream |
| Custom vocab | Soft `promptTokens` bias only | Up to 3,000 keywords, per-init or per-session |
| Android | Not supported | Supported (Kotlin SDK + LiteRT) |
| Confidence | `exp(avg_logprob)` per segment | First-party API |

## Requirements

- macOS 14.0 (Sonoma) or newer.
- Apple Silicon strongly recommended; CoreML on the ANE is the fast path.
- Xcode / Swift toolchain 5.9+.
- Network access on first run for the model download (subsequent runs are
  fully offline).

## Build

```bash
# from voice-engine/native/apple/WhisperKitSTT/
swift build -c release
# binary lands at .build/release/WhisperKitSTT
```

Or use the wrapper script which prints the binary path on its last line:

```bash
# from voice-engine/native/apple/
./whisperkit_build.sh             # release build, default
./whisperkit_build.sh debug       # debug build
```

The Python lab defaults to looking for the release binary at
`voice-engine/native/apple/WhisperKitSTT/.build/release/WhisperKitSTT`.
Override with the `WHISPERKIT_BIN` environment variable if you put it
elsewhere.

## Model selection and download

WhisperKit downloads CoreML model bundles from
[`argmaxinc/whisperkit-coreml`](https://huggingface.co/argmaxinc/whisperkit-coreml)
on first use. The model is fetched into WhisperKit's default cache
directory (under `~/Documents/huggingface/models/argmaxinc/whisperkit-coreml`
or equivalent) — not into this package directory. The download is a
one-time cost; subsequent runs reuse the cached model and require no
network.

Default model: `openai_whisper-tiny.en` (smallest English-only build,
~40 MB). For higher accuracy, pass `--model openai_whisper-base.en`
(~150 MB) or `--model openai_whisper-large-v3-v20240930_626MB` (~620 MB,
the model Argmax recommends for multilingual accuracy).

If a run fails with exit code 50 ("model load failed") on a clean
checkout, the most common cause is no network on first run — the model
download silently fails. Pre-download by running the Argmax CLI's
`download-model` recipe, or simply re-run with network available.

## Run

```bash
# Smallest English model, partials on, no vocab biasing
.build/release/WhisperKitSTT \
  --file /path/to/audio.wav \
  --model openai_whisper-tiny.en \
  --partials true

# With dealership-vocab soft biasing (decoder promptTokens)
.build/release/WhisperKitSTT \
  --file /path/to/audio.wav \
  --model openai_whisper-base.en \
  --partials true \
  --vocab ../../../lab/cue-packs/dealership_vocabulary.txt
```

### Output

One JSON object per line on stdout. Field shape matches
`voice_lab.types.TranscriptEvent`:

```json
{"type":"partial","text":"and wireless car play","timestamp_ms":1230,"latency_ms_from_audio_start":1180,"confidence":0.87,"engine_metadata":{"engine":"whisperkit","model":"openai_whisper-tiny.en","is_volatile":true,"window_id":0}}
{"type":"final","text":"and wireless CarPlay is great","timestamp_ms":3100,"latency_ms_from_audio_start":3500,"confidence":0.94,"engine_metadata":{"engine":"whisperkit","model":"openai_whisper-tiny.en","is_volatile":false,"segment_start_s":3.1,"segment_end_s":4.8,"avg_logprob":-0.062,"no_speech_prob":0.01,"compression_ratio":1.4}}
```

- `type` mirrors `TranscriptEvent.stability` (`partial` | `final`).
- `timestamp_ms` is audio-relative. For finals, it's
  `segment.start * 1000` (truly audio-relative). For partials, WhisperKit
  does not expose a per-callback audio offset, so we surface the wall-
  clock latency from audio start as a best-effort surrogate; the lab can
  cross-reference the next final's `segment_start_s` if it needs an
  audio-relative anchor.
- `latency_ms_from_audio_start` is wall-clock from when the runner
  started transcribing.
- `confidence` is `exp(avg_logprob)` clamped to `[0, 1]`. WhisperKit
  exposes per-segment `avgLogprob` (log-domain mean of chosen-token log
  probabilities, non-positive); `exp` lifts it into (0, 1]. Documented
  choice in `WhisperKitRunner.convertLogprobToConfidence`.
- `engine_metadata` is opaque to the lab core; carries
  `avg_logprob`, `no_speech_prob`, `compression_ratio`, segment bounds,
  and the model name for offline diagnostics.

### Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Argument error or generic runtime failure |
| 30 | Input audio file unreadable / missing |
| 50 | WhisperKit model load failed (often: no network on first run) |
| 51 | Tokenizer unavailable for selected model |
| 52 | WhisperKit transcription failed mid-run |

Errors are written to stderr with a human-readable message prefixed by
`WhisperKitSTT error:`. The Python wrapper surfaces these as
`TranscriptionError`.

## Two-stream stability mapping

The voice-engine binding rule (`voice-engine/docs/architecture.md`)
requires every strategy to emit BOTH partial and final events. Here is
how the runner maps WhisperKit's surface onto that contract:

| Stream | Source | When it fires | Stability |
|---|---|---|---|
| Partial | `TranscriptionCallback` on `WhisperKit.transcribe(audioPath:callback:)` | Repeatedly during decoding, carrying in-flight `TranscriptionProgress.text` and `avgLogprob` | `partial`, `is_volatile: true` |
| Final | `WhisperKit.segmentDiscoveryCallback` | Once per `TranscriptionSegment` as the seeker seals it | `final`, `is_volatile: false` |

Partials are dedup'd by `(windowId, text)` so a stable decoder window
doesn't spam identical lines.

## Custom vocabulary (best-effort)

The open-source WhisperKit does not have a dedicated custom-vocabulary
API. The Argmax Pro SDK does (3,000-keyword limit). For the lab path we
take the user's `--vocab` file (one term per line) and feed it to the
decoder as a tokenized `promptTokens` prompt, which biases generation
toward the prompt's lexicon. This is a SOFT bias, not a guarantee — it
helps with proper nouns the model has seen in training but cannot
hallucinate truly novel terms. For hard hot-word boosting, the
SFSpeechRecognizer path (AppleSTT `--mode sfspeech_recognizer`) is
stricter; the Pro SDK path is even stricter. See
`dev/docs/ROAD_TO_SALE_AUDIO_RESEARCH_ARGMAX.md`.

## How this slots into the lab

```
voice-engine/lab
  src/voice_lab/strategies/
    whisperkit.py                # spawns WhisperKitSTT
  cue-packs/
    dealership_vocabulary.txt    # can be passed via --vocab
```

The Python wrapper spawns `WhisperKitSTT` via `subprocess.Popen`,
streams JSONL stdout line-by-line into `TranscriptEvent` objects, and
yields them to the lab orchestrator. No internal Swift state crosses
the process boundary — the JSONL contract is the entire surface.

## Known gaps / manual verification still required

1. **End-to-end run with real audio + real model download.** Build
   success only proves the API surface compiles. Actually running the
   binary against a fixture WAV requires (a) network on first run for
   the CoreML model download from HuggingFace, and (b) ~40 MB of disk
   for the smallest English model. Both are manual steps.
2. **WhisperKit version pinning.** Pinned via `.upToNextMajor(from:
   "0.13.0")` in `Package.swift`, which captures the pre-rename stable
   line (0.13.x .. 0.18.x). At v1.0.0 (released 2026-05-01) the project
   renamed to "Argmax Open-Source SDK" (`argmax-oss-swift`) with API
   removals; we'll cut over deliberately rather than ride the major.
3. **Partial-event audio timestamp.** WhisperKit's
   `TranscriptionProgress` lacks a per-callback audio offset, so the
   partial-event `timestamp_ms` is the wall-clock latency, not a true
   audio-relative position. The lab reconciles via the next sealed
   segment's `segment_start_s`. The Pro SDK exposes audio-relative
   `seconds` on every hypothesis event; switching surfaces that field.
4. **Confidence scale.** `exp(avg_logprob)` is a documented convention,
   not a calibrated probability. Cross-engine comparisons should keep
   the scale in mind.
