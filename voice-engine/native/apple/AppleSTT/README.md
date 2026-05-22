# AppleSTT — Swift CLI for the voice-engine lab

A standalone Swift Package Manager executable that wraps Apple's on-device
speech-to-text frameworks and emits JSONL transcript events on stdout. The
Python lab invokes this binary as a subprocess; see
`voice-engine/lab/src/voice_lab/strategies/apple_speech_transcriber.py` and
`apple_sfspeechrecognizer.py`.

## Two modes

| Flag | Backing API | Custom vocab | Min OS |
|---|---|---|---|
| `--mode speech_transcriber` | `SpeechAnalyzer` + `SpeechTranscriber` (Speech framework, WWDC25) | **No** | macOS 26 (Tahoe) / iOS 26 |
| `--mode sfspeech_recognizer` | `SFSpeechRecognizer` + `SFSpeechURLRecognitionRequest.contextualStrings` | **Yes** | macOS 10.15 / iOS 10 (Apple Silicon for on-device) |

Why two: `SpeechTranscriber` is the new long-form on-device engine introduced
in macOS 26 / iOS 26 and supports both volatile (partial) and final results
out of the box. It does **not** accept custom vocabulary. The legacy
`SFSpeechRecognizer` does — via `contextualStrings` — so we keep both lanes
available. See `dev/docs/ROAD_TO_SALE_AUDIO_RESEARCH_APPLE.md` for the
research brief that motivates this split.

## Requirements

- macOS 26 (Tahoe) or newer for `speech_transcriber` mode.
- macOS 10.15 or newer for `sfspeech_recognizer` mode (Apple Silicon strongly
  recommended for on-device recognition).
- Xcode / Swift toolchain 5.9+. Tested against Swift 6.3 on macOS 26.3.1.
- Speech recognition authorization granted to the binary. The first run on a
  given device triggers a system prompt. Approve under
  *System Settings > Privacy & Security > Speech Recognition*.

## Build

```bash
# from voice-engine/native/apple/AppleSTT/
swift build -c release
# binary lands at .build/release/AppleSTT
```

Or use the wrapper script which prints the binary path on its last line:

```bash
# from voice-engine/native/apple/
./build.sh             # release build, default
./build.sh debug       # debug build
```

The Python lab defaults to looking for the release binary at
`voice-engine/native/apple/AppleSTT/.build/release/AppleSTT`. Override with
the `APPLE_STT_BIN` environment variable if you put it elsewhere.

## Run

```bash
# General transcription, no vocab biasing
.build/release/AppleSTT \
  --file /path/to/audio.wav \
  --mode speech_transcriber \
  --locale en-US \
  --partials true

# Dealership-vocab biasing (legacy recognizer)
.build/release/AppleSTT \
  --file /path/to/audio.wav \
  --mode sfspeech_recognizer \
  --locale en-US \
  --partials true \
  --vocab ../../../lab/cue-packs/dealership_vocabulary.txt
```

### Output

One JSON object per line on stdout. Field shape matches
`voice_lab.types.TranscriptEvent`:

```json
{"type":"partial","text":"and wireless car play","timestamp_ms":1230,"latency_ms_from_audio_start":1180,"confidence":null,"engine_metadata":{"engine":"speech_transcriber","is_volatile":true,"locale":"en-US"}}
{"type":"final","text":"and wireless CarPlay is great","timestamp_ms":3100,"latency_ms_from_audio_start":3500,"confidence":0.92,"engine_metadata":{"engine":"sfspeech_recognizer","is_volatile":false,"locale":"en-US","on_device":true,"vocab_terms":47}}
```

- `type` mirrors `TranscriptEvent.stability` (`partial` | `final`).
- `timestamp_ms` is audio-relative (from the audio file's t=0).
- `latency_ms_from_audio_start` is wall-clock from when the analyzer started
  feeding audio.
- `confidence` is `null` for `speech_transcriber` (per-token confidence
  attribute scale is undocumented at writing — research brief OQ11). For
  `sfspeech_recognizer` it is the average of non-zero
  `SFTranscriptionSegment.confidence` values on the final.
- `engine_metadata` is opaque to the lab core; surfaces engine-specific
  diagnostics.

### Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Argument error or generic runtime failure |
| 10 | Speech authorization denied |
| 11 | Speech recognition restricted |
| 12 | Speech authorization not determined yet (prompt pending) |
| 13 | Unknown authorization status |
| 20 | `SFSpeechRecognizer` unavailable for requested locale |
| 21 | `SFSpeechRecognizer` reported `isAvailable = false` |
| 30 | Input audio file unreadable / missing |
| 40 | `SpeechTranscriber` requested but OS < macOS 26 / iOS 26 |

Errors are written to stderr with a human-readable message prefixed by
`AppleSTT error:`. The Python wrapper surfaces these as `TranscriptionError`.

## How this slots into the lab

```
voice-engine/lab
  src/voice_lab/strategies/
    apple_speech_transcriber.py        # spawns AppleSTT --mode speech_transcriber
    apple_sfspeechrecognizer.py        # spawns AppleSTT --mode sfspeech_recognizer
  cue-packs/
    dealership_vocabulary.txt          # passed as --vocab to sfspeech_recognizer
```

The Python wrappers spawn `AppleSTT` via `subprocess.Popen`, stream JSONL
stdout line-by-line into `TranscriptEvent` objects, and yield them to the
lab orchestrator. No internal Swift state crosses the process boundary —
the JSONL contract is the entire surface.

The same Swift sources are also the reference implementation that the
future iOS React Native bridge in `voice-engine/ios/` will mirror; the JSONL
shape maps 1:1 to the RN event emitter.

## Known gaps / manual verification still required

1. **End-to-end run with real audio on macOS 26.** Build success only proves
   the API surface compiles. Actually running the binary against an audio
   file requires (a) a wired-up audio fixture and (b) the OS to have granted
   speech-recognition authorization to the binary. Both are manual steps;
   see `voice-engine/lab/fixtures/`.
2. **Microphone vs file authorization.** This CLI only reads files. No mic
   access is requested. Speech-recognition authorization is still required.
3. **`speech_transcriber` confidence.** Left as `null` deliberately — Apple
   has not documented the per-token confidence attribute's scale. Will be
   surfaced via `engine_metadata` if a stable scale appears.
