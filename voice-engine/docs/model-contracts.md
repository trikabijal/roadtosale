# Voice Engine — Configurable Model Contracts

The voice engine has **two swappable model layers**. Each sits behind a stable
contract, so a consumer can pick whichever model it wants by setting
`{ provider, model }` config at runtime. This is the canonical, language-neutral
source of truth; native platforms (Swift, Kotlin) implement the *same* contracts.

> **Reuse principle:** code does not port — contracts, data, and learnings do.

---

## 1. STT — `TranscriptionStrategy` (the voice-understanding model)

Already defined in `src/strategies/base.ts` + `src/strategies/registry.ts`.

- **Streaming contract** (live lane): `start(context) → Session` emitting `TranscriptEvent`s.
- **Batch variant** (dictation): record → transcribe whole clip → one result. The macOS
  dictation app's Swift `SpeechTranscriber` protocol is the batch sibling of this contract.
- Providers: `whisperkit`, `apple_speech_transcriber`, `argmax`, `sherpa_onnx`, `mock`.

## 2. Cleanup — `CleanupStrategy` (the cleanup LLM)  ← new

Defined in `src/cleanup/`:

```ts
interface CleanupStrategy {
  readonly name: string;                       // provider id
  clean(request: CleanupRequest): Promise<CleanupResult>;
}

interface CleanupRequest {
  raw_text: string;
  level: 'off' | 'light' | 'full';
  vocab: Record<string, string>;               // forced spellings, applied AFTER cleanup
  command_grammar: Record<string, string>;     // "new paragraph" → "\n\n"
  profile?: string;                            // "dictation" | "road-to-sale"
}

interface CleanupResult {
  cleaned_text: string;
  ops_applied: string[];                       // ["commands","fillers","punctuation",...]
  used_fallback: boolean;
  latency_ms: number;
  engine_metadata: Record<string, unknown>;
}
```

- Providers: `foundation-models` (Apple), `gemini-nano` / `mediapipe` (Android),
  `rule-based` (always-available deterministic fallback — see `rule-based.ts`).
- The `rule-based` strategy is the **reference fallback**: every platform must fall back
  to behaviour equivalent to it when no LLM is available or the LLM returns degenerate
  output. Native fallbacks should match its transformations.

### Cleanup levels

| Level | Behaviour |
|-------|-----------|
| `off` | passthrough — raw text |
| `light` | fillers + punctuation/caps + commands; preserve exact words |
| `full` | light + collapse false starts/repeats + light restructuring; **preserve meaning** |

---

## 3. The `{ provider, model }` config

Each layer is chosen by config, persisted by the consumer (e.g. `UserDefaults` on Apple):

```
STT:     { provider: "whisperkit",        model: "openai_whisper-large-v3_turbo_954MB" }
Cleanup: { provider: "foundation-models", model: "<system-default>", level: "full" }
```

Swap a model → change config → factory rebuilds the implementation. No consumer code change.

---

## 4. Platform implementation matrix

| Layer | macOS | iOS | Android |
|-------|-------|-----|---------|
| STT | WhisperKit | Apple SpeechTranscriber / WhisperKit | Argmax / sherpa-onnx |
| Cleanup | Foundation Models | Foundation Models (iOS 26+) | Gemini Nano / MediaPipe |
| Fallback | rule-based | rule-based | rule-based |

Each cell is a different implementation of the **same** contract. The data pack (prompts,
filler list, command grammar, vocab, junk phrases) and the telemetry schema are shared,
so the macOS dogfood's learnings tune all three platforms.

---

## 5. Telemetry schema (shared across platforms)

`raw_text · cleaned_text · level · provider · model · was_corrected · latency_ms ·
confidence · frontmost_app · failure_tags`

One schema everywhere → the macOS daily-driver produces the labelled dataset that tunes
the shared data pack for iOS and Android Road to Sale.
