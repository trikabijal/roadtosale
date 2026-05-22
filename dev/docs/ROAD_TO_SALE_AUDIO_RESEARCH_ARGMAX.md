# Argmax Pro SDK 2 — Research Brief

Last updated: `2026-05-22`

Owner of question: OQ2 in `dev/tasks/0001-prd-voice-engine.md`.

## Update: WhisperKit (open source, MIT) is now wired into the lab

`2026-05-22` — WhisperKit, Argmax's open-source MIT-licensed on-device
Whisper SDK, is now registered in the voice-engine lab as the
`whisperkit` strategy. It runs a sibling Swift CLI
(`voice-engine/native/apple/WhisperKitSTT/`) and emits the same JSONL
event format as the AppleSTT wrappers. No payment, no Local Server
binary, no $14 trial — model downloads on first use from
HuggingFace and runs fully offline thereafter.

**For macOS lab evaluation, WhisperKit is the recommended free path.**
The paid Pro SDK path documented below remains the **Android cross-
platform option** (open-source WhisperKit is Apple-only) and is still
the right answer when:

- We need real-time Confirmed + Hypothesis streaming (a first-party
  API on Pro; on open-source WhisperKit we synthesize partials from
  the decoder progress callback)
- We need hard custom-vocabulary boosting (up to 3,000 keywords on
  Pro; on open-source WhisperKit we use a soft decoder `promptTokens`
  bias)
- We need a Deepgram-WebSocket-compatible Local Server for a Python
  client that isn't this lab

Tradeoffs are summarized in
`voice-engine/native/apple/WhisperKitSTT/README.md`. Everything below
this section is the original Pro SDK research brief, unchanged.

## Bottom line

Argmax Pro SDK 2 went GA **April 7, 2026**. Ships strictly on-device on iOS, macOS (Apple Silicon, M1+), and Android (API 24+, NPU via LiteRT). Models: NVIDIA Parakeet (default streaming) and OpenAI Whisper. Exposes a dual `Confirmed` + `Hypothesis` real-time stream (satisfies our binding rule). Supports up to **3,000 custom-vocab keywords**. There are no official Python bindings — only Swift (SPM) and Kotlin (Maven) SDKs, with a Node.js client for the Argmax Local Server (Python client "coming soon"). For a macOS Python lab, the realistic path is `subprocess` to either the Argmax Local Server (Deepgram-WebSocket-compatible) or a small Swift CLI built from `argmax-sdk-swift-playground`. Pricing is **$1.00–$1.33/device/month** with a **$14, 14-day, 30-device-license trial** that's self-serve at app.argmaxinc.com — **no sales call needed for evaluation**. Linux/Windows are explicitly not supported.

## 1. Product status

- **Pro SDK 2 GA April 7, 2026** (succeeds Pro SDK 1, GA July 24, 2025).
- Android Pro SDK shipped March 18, 2026; SDK 2 added speakers + improved custom vocab on top.
- **Models:** NVIDIA Parakeet (streaming default), OpenAI Whisper, NVIDIA Sortformer for diarization. Enterprise tier can bring fine-tuned proprietary models.
- Strictly on-device. The "Argmax Local Server" wraps the same models for non-native apps but still runs locally.

## 2. Platform coverage

| Platform | Status | SDK shape |
|---|---|---|
| iOS 17+, A14+ | Supported | Swift SDK via SPM |
| iPadOS 17+ | Supported | Swift SDK via SPM |
| macOS 14+, M1+ | Supported | Swift SDK via SPM; Local Server host |
| Android 7.0+ (API 24+), Snapdragon 8 Gen 1 / Android 14 validated | Supported | Kotlin SDK via Maven, Google LiteRT (NPU on Snapdragon/Tensor/MediaTek) |
| Linux / Windows | **Not supported, not coming** | — |

## 3. Python interop

- **No official Python bindings.** Local Server docs list Python client as "coming soon."
- **Node client exists:** `npm install @argmaxinc/local-server`, WebSocket at `ws://localhost:50060`.
- **Local Server WebSocket is Deepgram Streaming-STT compatible** — pointing any existing Deepgram Python client at localhost works (auth header `ax_***`).
- **Lowest-friction lab path on macOS:**
  1. Sign up at app.argmaxinc.com, pay $14 trial fee → 30 device licenses.
  2. Email `customer@argmaxinc.com` to request the macOS Local Server binary (not gated to enterprise; not download-on-demand either).
  3. Run Local Server on `localhost:50060`.
  4. From Python, use the official **Deepgram Python SDK** with `host` overridden to localhost and `ax_***` API key. Streaming + Confirmed + Hypothesis + word timestamps, no Swift required.
- **Fallback** if the Local Server binary email stalls: build a ~100-LOC Swift CLI from `argmax-sdk-swift-playground` that emits JSONL on stdout, call via `subprocess.Popen` from the lab harness.

## 4. Streaming — Confirmed + Hypothesis

Confirmed from docs. Result stream emits two cases on `TranscribeDiarizeResultType`:
- `.confirm(text, seconds, result)` — finalized
- `.hypothesis(text, seconds, result)` — mutable, refined as more audio arrives
- `.speakerRevision` — retroactive diarization fix

Consumed as a Swift `for try await ... in session.results` async stream — both surfaces visible to the consumer simultaneously. `transcribeInterval` defaults to 0.1s. Pro SDK 1 claimed **160 ms real-time latency** ("world's fastest"); SDK 2 retains the same engine and adds speakers without accuracy loss vs pre-recorded mode.

## 5. Custom vocabulary

- **Up to 3,000 keywords** without significant slowdown (vs "a few hundred" on most cloud APIs).
- Two registration modes: per-init (eager, via `WhisperKitProConfig`) and per-session/runtime via `setCustomVocabulary([...])`.
- 25 languages on default Parakeet-v3 model; English-only model available but raises false-positive rate on non-English audio.

## 6. Licensing and cost

- **Free / Basic:** open-source WhisperKit/SpeakerKit/TTSKit, MIT, public Discord only — NOT the Pro SDK.
- **Pro:** **$1.33/device/month monthly, $1.00/device/month annual.** Minimums: 1,000 monthly licenses, 10,000 annual. No rate/usage/concurrency limits. Billing is per unique device that initializes the SDK in a calendar month.
- **Enterprise:** custom pricing, custom models, implementation support — sales-gated.
- **Trial:** $14 buys 14-day trial with 30 device licenses, self-serve at app.argmaxinc.com. **No sales call needed.** Auto-renews into the monthly tier unless cancelled.

## 7. Accent / dealership terms

- No explicit accent-coverage claims for North American English in public docs.
- Custom-vocabulary path is the documented mechanism for proper nouns and product names. Honda dealer terms like "CR-V Hybrid AWD," "Honda Sensing 360+," trim names, VIN partials — register as keywords (well under 3,000 limit) at init or per-session.

## 8. Competitive benchmarks

- **vs Apple SpeechTranscriber:** Argmax's own writeup and the ICML 2025 WhisperKit paper claim WhisperKit hits 2.2% WER on earnings22 at 0.46s streaming latency, beating Apple SpeechTranscriber, gpt-4o-transcribe, and Deepgram nova-3. **All first-party data — no independent third-party benchmark surfaced.**
- **vs Whisper on Android:** No public head-to-head. Pro SDK Android uses LiteRT AOT compilation, claimed advantage is removing TFLite JIT cost rather than a WER number.

## Recommended Python integration

1. Pay $14 trial (self-serve).
2. Email `customer@argmaxinc.com` for the macOS Local Server binary.
3. Run Local Server.
4. Drive from Python using Deepgram Python SDK against `localhost:50060`.

If email path stalls: Swift CLI fallback.

## Still uncertain / blocked

- **Python Local Server client release date** — "coming soon," no ETA.
- **Local Server binary availability** — requires an email to `customer@argmaxinc.com`. Not gated to enterprise, but not self-download either.
- **Hypothesis/Confirmed field parity over WebSocket** — Swift API has both. Deepgram-compatible WebSocket maps these to Deepgram's `is_final` / interim semantics; word-level confidence on hypothesis is undocumented and needs empirical verification.
- **Android Pro SDK custom vocabulary** — launched March 2026 without it; "expected 2026" parity. Not confirmed shipped as of late May 2026.
- **Independent third-party benchmarks** vs Apple SpeechTranscriber: none found. All accuracy claims trace back to Argmax's own ICML paper or blog posts.

## Sources

- [Argmax Pro SDK 2 announcement, Apr 7 2026](https://www.argmaxinc.com/blog/argmax-sdk-2)
- [Argmax Pro SDK for Android, Mar 18 2026](https://www.argmaxinc.com/blog/argmax-pro-sdk-for-android)
- [Argmax Pro SDK 1 GA, Jul 24 2025](https://www.argmaxinc.com/blog/pro-sdk-ga)
- [Argmax Local Server overview](https://www.argmaxinc.com/blog/argmax-local-server)
- [Argmax Docs — Introduction](https://app.argmaxinc.com/docs)
- [Argmax Docs — Supported Platforms](https://app.argmaxinc.com/docs/wiki/supported-platforms)
- [Argmax Docs — Real-time Transcription example](https://app.argmaxinc.com/docs/examples/real-time-transcription)
- [Argmax Docs — Custom Vocabulary example](https://app.argmaxinc.com/docs/examples/custom-vocabulary)
- [Argmax Docs — Using Local Server guide](https://app.argmaxinc.com/docs/guides/using-local-server)
- [Argmax Pricing](https://www.argmaxinc.com/pricing)
- [argmax-sdk-swift-playground repo](https://github.com/argmaxinc/argmax-sdk-swift-playground)
- [WhisperKit ICML 2025 paper (arXiv 2507.10860)](https://arxiv.org/html/2507.10860v1)
- [Apple SpeechAnalyzer and Argmax WhisperKit blog](https://www.argmaxinc.com/blog/apple-and-argmax)
