# f3 — STT providers & selection

**Facades:** `SpeechTranscriber` protocol; `STTProvider` / `STTConfig`; `SpeechTranscriberFactory`;
implementations `WhisperKitTranscriber`, `AppleSpeechTranscriber` (+ `AppleAudioConverter`,
`AppleStreamingSession`), plus `MockTranscriber` / `UnavailableTranscriber` doubles.
**Design lever:** English → Apple (fast), Hinglish/Gujarati → WhisperKit. Batch `transcribe()` is the
authoritative pasted text; streaming is preview only.

## E2E facade ledger

| Facade | Behavior | Inputs/State | Expected | Tier | Notes |
|---|---|---|---|---|---|
| `SpeechTranscriberFactory.make` | whisperKit | `STTConfig(.whisperKit, largeV3Turbo)` | a `WhisperKitTranscriber` | 1 | |
| `SpeechTranscriberFactory.make` | mock | `.mock` | a `MockTranscriber` | 1 | |
| `SpeechTranscriberFactory.make` | appleSpeech on macOS 26 | `.appleSpeech, en-US` | `AppleSpeechTranscriber` | 1 | OS-gated |
| `SpeechTranscriberFactory.make` | appleSpeech below macOS 26 | `.appleSpeech` | `UnavailableTranscriber` | 1 | graceful |
| `STTProvider.isAvailable` | whisperKit/mock | — | true | 1 | |
| `STTProvider.isAvailable` | appleSpeech | macOS < 26 | false | 1 | |
| `STTProvider.selectable` | offered set | — | `[.whisperKit, .appleSpeech]` (no mock) | 1 | |
| `STTConfig.default` | default | — | whisperKit + largeV3Turbo | 1 | |
| `STTConfig.modelDisplayName` | whisperKit tier | largeV3Turbo | tier displayName | 1 | |
| `SpeechTranscriber.transcribe` | real English clip | fixture PCM | ordered, head/tail-complete text | 3 | model-gated |
| `SpeechTranscriber.transcribe` | long audio (>30s) | fixture | full transcript, not a few junk words | 3 | model-gated |
| `SpeechTranscriber.setVocabularyBias` | bias efficacy | clip w/ terms | ↑ exact-spelling rate vs no bias | 3 | model-gated |
| `SpeechTranscriber.transcribe` | silence | near-silent | empty (no phantom "thank you") | 3 | model-gated; filter unit-tested |
| `AppleSpeechTranscriber.load` | unsupported locale | `gu-IN` | throws `providerUnavailable` → AppState falls back | 3 | model-gated |
| `AppleSpeechTranscriber.load` | auth denied | denied | throws `providerUnavailable` (no silent dead-end) | 3 | model-gated |

## Unit module inventory

| Module | Public interface | Priority | Notes |
|---|---|---|---|
| `SpeechTranscriber` / factory / config | `make()`, `isAvailable`, `selectable`, `STTConfig.default/modelDisplayName` | **Critical** | pure contract — **gap, add now** |
| `WhisperKitTranscriber` | `transcribe`, `setVocabularyBias`, `isLikelyHallucination` (nonisolated static), `makeStreamingSession` | Critical | STT model-gated; hallucination filter unit-tested (f4) |
| `AppleSpeechTranscriber` / `AppleAudioConverter` | `load`, `transcribe`, `convert(_:)` | High | converter is model-free → **gap, add (macOS-26-gated)**; STT model-gated |
| `MockTranscriber` / `UnavailableTranscriber` | `transcribe`, `load` | Medium | doubles; exercised by pipeline |

### Unit scenarios — factory / provider / config (add now)

| Function | Scenario | Expected |
|---|---|---|
| `make(.whisperKit)` | tier resolves | `WhisperKitTranscriber` w/ correct `modelTier` |
| `make(.whisperKit, bogus)` | bad model id | falls back to `.largeV3Turbo` |
| `make(.mock)` | test double | `MockTranscriber` |
| `make(.appleSpeech)` | OS gate | Apple impl on 26+, else `UnavailableTranscriber` |
| `STTProvider.whisperKit.isAvailable` | | true |
| `STTProvider.appleSpeech.isAvailable` | | matches `#available(macOS 26)` |
| `STTProvider.selectable` | | excludes `.mock` |
| `STTConfig.default` | | `.whisperKit` + `largeV3Turbo` |
| `STTConfig.modelDisplayName` | whisperKit | tier displayName; apple → provider displayName |
| `MockTranscriber.transcribe` | canned | returns cannedText, confidence 1, provider `.mock` |
| `UnavailableTranscriber.load/transcribe` | | throws `providerUnavailable(name)` |

### Unit scenarios — `AppleAudioConverter` (macOS 26 gated, model-free)

| Function | Scenario | Expected |
|---|---|---|
| `convert` | 16 kHz mono → target | non-nil buffer, non-zero frameLength |
| `convert` | same format | returns input unchanged |
| `convert` | reused across chunks | stateful converter reused (no crash), continuous output |

## Deferred (Tier 3, model-gated — skip without weights)
Real transcription (ordered/complete, long audio, vocab efficacy, multilingual), Apple locale
fallback + auth-denied — all need on-device models; run in the device/release lane.

## Checklist grade
- **scope-at-every-level** ✅ — provider gating checked at `isAvailable` AND factory (consistent).
- **error-surfacing** ✅ — unavailable provider → `UnavailableTranscriber`/throw, never a silent nil.
