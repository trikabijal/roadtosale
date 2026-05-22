# Apple SpeechTranscriber — Research Brief

Last updated: `2026-05-22`

Owner of question: OQ1 in `dev/tasks/0001-prd-voice-engine.md`.

## Bottom line

`SpeechTranscriber` is a real, currently-shipping Apple API introduced in **iOS 26 / macOS 26 (Tahoe)** at WWDC 2025, exposed via the Speech framework's new `SpeechAnalyzer` orchestrator. It is fully on-device, supports streaming with both **volatile (interim) and final** results via async sequences, supports en-US plus 40+ locales, and downloads its model on first use without bloating app size. It does **NOT** support custom vocabulary / contextual biasing — that exists only on the older `SFSpeechRecognizer`, which still ships and is the fallback for dealership-term boosting. For our Python lab, the lowest-friction path is a small standalone Swift CLI that emits JSONL events to stdout, invoked via `subprocess`.

## 1. API existence, shape, OS minimums

| Item | Fact | Source |
|---|---|---|
| Class | `SpeechTranscriber` in `Speech` framework | Apple docs |
| Introduced | iOS 26 / macOS 26 ("Tahoe") / iPadOS 26 / visionOS 26 / tvOS 26 — WWDC 2025 | WWDC25 session 277 |
| Not on | watchOS | WWDC25 session 277 |
| macOS equivalent | Same API — `SpeechTranscriber` is unified across iOS and macOS targets | WWDC25 session 277 |
| Relation to `SFSpeechRecognizer` | Replacement architecture. `SpeechAnalyzer` is the orchestrator; modules compose: `SpeechTranscriber` (long-form), `DictationTranscriber` (short utterance), `SpeechDetector` (VAD). Old `SFSpeechRecognizer` still ships. | Anton Gubarenko, Blake Crosley |
| Hardware on macOS | Apple Silicon practical assumption; "certain hardware requirements apply for non-watchOS platforms" — unconfirmed at spec level | WWDC25 session 277 |

## 2. Streaming behavior

- **Both volatile and final results are first-class.** Enable via `reportingOptions: [.volatileResults]` on transcriber init. Results arrive on an `AsyncSequence` (`transcriber.results`) and each result carries `isFinal: Bool`. Satisfies our both-streams binding rule.
- On-device only. No server fallback.
- Audio format: query via `SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [transcriber])`.
- Latency: MacStories measured ~2.2× faster than MacWhisper Large V3 Turbo; 34-min file processed in ~45s. Volatile partials sub-second perceptually but no Apple-published number.

## 3. Python interop

- **No native Python SDK.** PyObjC has a `Speech` framework binding, but coverage of iOS 26 / macOS 26 `SpeechAnalyzer`/`SpeechTranscriber` is unconfirmed.
- **Realistic options, ranked:**
  1. **Swift CLI subprocess emitting JSONL to stdout** — lowest friction. ~150 LOC Swift, single binary, easy to test in isolation, mirrors what the iOS native module eventually does. **Recommended.**
  2. PyObjC bridge — viable for `SFSpeechRecognizer`, uncertain for `SpeechTranscriber`. Async sequences and `AttributedString` translate awkwardly to Python.

## 4. Custom vocabulary — GAP

- **`SpeechTranscriber` does NOT support custom vocabulary / contextual biasing.** Confirmed by Blake Crosley engineering blog and WWDC25 session 277 (no mention).
- **`SFSpeechRecognizer` does** — `SFSpeechRecognitionRequest.contextualStrings: [String]` (no documented hard limit; Apple recommends "shorter is better").
- **Implication for our lab:** the with-custom-vocab test mode (OQ10) cannot use SpeechTranscriber alone. Use a **hybrid pattern**: `SpeechTranscriber` for general transcription, `SFSpeechRecognizer` with `contextualStrings = ["CR-V Hybrid AWD", "Sensing 360 plus", "RAV4", ...]` for vocab-sensitive passes. Two strategy registrations in the lab: `apple_speech_transcriber` (general) and `apple_sfspeechrecognizer_with_vocab` (vocab).

## 5. Confidence scores

- `SpeechTranscriber` returns results as `AttributedString` with per-token attributes. `SpeechTranscriber.ResultAttributeOption` includes `.audioTimeRange`. Per-token confidence is reported by third-party engineering write-ups; scale (0–1 vs log-prob) unconfirmed.
- `SFSpeechRecognizer` exposes `SFTranscriptionSegment.confidence` as `Float` (0.0–1.0).

## 6. Cost / licensing

- Free. No API fees, no per-call cost, no quota. On-device. User authorization via `SFSpeechRecognizer.requestAuthorization` (same gate as legacy).

## 7. Known gaps for our use case

- **No custom vocabulary** on SpeechTranscriber — biggest gap vs SFSpeechRecognizer for domain terms.
- **iOS 26 / macOS 26 only** — no backport. Devices on older OS must use SFSpeechRecognizer.
- **Hardware reqs on macOS** — Apple Silicon almost certainly required; unconfirmed minimum.

## Recommended lab integration

Build a Swift CLI `apple_stt` that:
1. Takes `--file <path> --locale en-US --partials true --vocab-mode {none|sfsr}` flags.
2. With `vocab-mode=none`: initializes `SpeechAnalyzer` + `SpeechTranscriber` with `[.volatileResults]` + `[.audioTimeRange]`.
3. With `vocab-mode=sfsr`: uses `SFSpeechRecognizer` with `contextualStrings` loaded from a sidecar file or env var.
4. Streams the file through `AVAudioFile` → `AnalyzerInput`.
5. Emits one JSON object per stdout line: `{"type":"partial|final","text":"...","start":1.23,"end":2.41,"confidence":0.87,"tokens":[...]}`.
6. Exits 0 on completion.

Python wrapper invokes via `subprocess.Popen`, reads JSONL line-by-line, surfaces partials + finals through the `TranscriptionStrategy` interface.

The same Swift module becomes the basis for the React Native bridge later — the JSONL contract maps 1:1 to the RN event emitter.

## Sources

- [SpeechTranscriber — Apple Developer Documentation](https://developer.apple.com/documentation/speech/speechtranscriber)
- [SpeechTranscriber.ResultAttributeOption](https://developer.apple.com/documentation/speech/speechtranscriber/resultattributeoption)
- [WWDC25 session 277 — Bring advanced speech-to-text to your app with SpeechAnalyzer](https://developer.apple.com/videos/play/wwdc2025/277/)
- [SFSpeechRecognitionRequest.contextualStrings](https://developer.apple.com/documentation/speech/sfspeechrecognitionrequest/contextualstrings)
- [SFTranscriptionSegment.confidence](https://developer.apple.com/documentation/speech/sftranscriptionsegment/confidence)
- [Apple's New Speech Framework — Blake Crosley](https://blakecrosley.com/blog/speech-framework-vs-sfspeechrecognizer)
- [iOS 26 SpeechAnalyzer Guide — Anton Gubarenko](https://antongubarenko.substack.com/p/ios-26-speechanalyzer-guide)
- [Hands-On: Apple's New Speech APIs vs Whisper — MacStories](https://www.macstories.net/stories/hands-on-how-apples-new-speech-apis-outpace-whisper-for-lightning-fast-transcription/)
- [On-Device Speech Transcription with Apple SpeechAnalyzer — Callstack](https://www.callstack.com/blog/on-device-speech-transcription-with-apple-speechanalyzer)
