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
  given device should trigger a system prompt. Approve under
  *System Settings > Privacy & Security > Speech Recognition*.

## Authorization model (read this first)

SwiftPM produces a plain Mach-O executable — not an app bundle. macOS's
privacy subsystem (TCC) keys speech-recognition consent off a binary's
identity, which it derives from its embedded `Info.plist` and its
codesignature. Without those, `SFSpeechRecognizer.requestAuthorization`
either silently never returns (sfspeech_recognizer) or the Speech framework
crashes inside its XPC reply path (speech_transcriber, SIGTRAP via
`completeTaskWithClosure`). No system prompt ever appears.

To mitigate this we:

1. Ship an `Info.plist` next to `Package.swift` declaring
   `CFBundleIdentifier=com.auditpro.voiceengine.applestt`,
   `NSSpeechRecognitionUsageDescription`, `NSMicrophoneUsageDescription`,
   and `LSMinimumSystemVersion=26.0`.
2. Embed that plist into the executable's `__TEXT,__info_plist` Mach-O
   section via `linkerSettings` in `Package.swift` (see the `.unsafeFlags`
   block).
3. After `swift build -c release`, ad-hoc codesign the binary with an
   explicit `--identifier` matching the plist's `CFBundleIdentifier` so
   that `codesign -d -vvv` reports `Info.plist entries=N` rather than
   `Info.plist=not bound`. This step is required because `swift build`
   emits a linker-signed signature that ignores the embedded plist.

**Status as of last test (macOS 26.3.1):** steps 1–3 land the plist into
the binary and bind it to the codesignature, but the OS still does **not**
surface the speech-recognition consent prompt for a bare CLI binary. The
binary still hangs (sfspeech_recognizer) or crashes inside Speech.framework
XPC (speech_transcriber). The expected next step is to wrap the executable
in a proper `.app` bundle (Contents/Info.plist + Contents/MacOS/AppleSTT)
and invoke it through that bundle — TCC keys consent off the bundle URL,
not the inner Mach-O. See "Known gaps" below.

## Build

```bash
# from voice-engine/native/apple/AppleSTT/
swift build -c release

# Required follow-up: rebind the embedded Info.plist to the codesignature.
# swift build emits a linker-signed binary that does not bind the plist;
# without this step `codesign -d -vvv` will report `Info.plist=not bound`
# and TCC will not recognize the binary.
codesign -f -s - \
  --identifier com.auditpro.voiceengine.applestt \
  .build/release/AppleSTT

# Verify both the section and the binding:
otool -s __TEXT __info_plist .build/release/AppleSTT | head -5
codesign -d -vvv .build/release/AppleSTT 2>&1 | grep -E 'Identifier|Info.plist'
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

### Expected first-run authorization flow

Once the `.app` bundle wrap (see "Known gaps" #1) is in place, the intended
behavior is:

- **First run on a device:** macOS shows a one-time consent prompt
  ("AppleSTT would like to access Speech Recognition"). The user clicks
  *Allow*. The auth callback resolves to `.authorized` and transcription
  proceeds.
- **Subsequent runs:** no prompt; the binary transcribes directly.
- **If the user clicks Deny:** the binary exits with one of codes 10–13
  (typically 10 = denied). To reverse, toggle the entry under
  *System Settings > Privacy & Security > Speech Recognition*.

Until the bundle wrap lands, the current behavior on first run is the
unauthorized hang / SIGTRAP documented in "Known gaps" — the prompt does
not surface for a bare CLI executable.

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

## First-run setup (SIGTRAP fix)

**Status (2026-05-23):** Bundle wrapping + TCC reset completed. The `.app`
bundle approach now takes the place of the bare executable.

### One-time setup per macOS user

When you first run the lab, macOS will show a speech-recognition authorization
prompt. This is expected and correct. **Click "Allow"** in System Settings to
proceed.

Steps:
1. Run `voice-engine/native/apple/build.sh` to build the executable (if not
   already built).
2. The Python lab will invoke
   `AppleSTT.app/Contents/MacOS/AppleSTT --file ... --mode ...` on the first
   strategy registration.
3. macOS will show a one-time consent prompt. Click **Allow** under
   **System Settings > Privacy & Security > Speech Recognition**.
4. Subsequent runs proceed without prompts.

### Prior issues (now fixed)

Earlier versions tried to use a bare Mach-O executable with an embedded
`Info.plist`. macOS 26's TCC (Transparency, Consent, and Control) keys
speech-recognition consent off an `.app` bundle, not a bare binary — so:

- `speech_transcriber` exited with SIGTRAP after ~1s (crash in Speech.framework
  XPC reply path)
- `sfspeech_recognizer` hung in `dispatch_group_wait_slow` (authorization
  callback never fired)

The fix: wrap the executable in `AppleSTT.app/Contents/MacOS/AppleSTT` and
invoke via that path. The Python wrappers (`apple_speech_transcriber.py` /
`apple_sfspeechrecognizer.py`) now default to the bundle location.
2. **End-to-end run with real audio on macOS 26.** Build success only proves
   the API surface compiles. Actually running the binary against an audio
   file additionally requires the OS to have granted speech-recognition
   authorization to the binary (blocked on gap #1 above).
3. **Microphone vs file authorization.** This CLI only reads files. No mic
   access is requested. Speech-recognition authorization is still required;
   `NSMicrophoneUsageDescription` is present in `Info.plist` defensively
   because SFSpeechRecognizer probes the mic subsystem during init.
4. **`speech_transcriber` confidence.** Left as `null` deliberately — Apple
   has not documented the per-token confidence attribute's scale. Will be
   surfaced via `engine_metadata` if a stable scale appears.
