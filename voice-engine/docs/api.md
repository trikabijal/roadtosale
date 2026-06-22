# Voice Engine — API Reference

Last updated: `2026-06-22`

> **Scope.** `voice-engine` is the contract/data/research core — **not a linked
> library.** The only *runnable* facade is the Python lab's `VoiceEngineLab`.
> The TS `VoiceEngine` + `CleanupStrategy` surface below is the **reference
> contract** that the production apps re-implement natively (Swift/Kotlin); no
> code in this repo imports the TS package. See
> [`architecture.md`](./architecture.md) and
> [`model-contracts.md`](./model-contracts.md).

This document is the binding facade contract. Everything listed here is public.
Anything not listed is internal and must not be imported by external code.

---

## 1. Python — `VoiceEngineLab` (the working facade)

Import:

```python
from voice_lab import VoiceEngineLab
```

Defined in `lab/src/voice_lab/facade.py`. This is the **only** public entry
point for the lab.

### Methods

| Method | Signature | Description |
|---|---|---|
| `VoiceEngineLab.load()` | `classmethod -> VoiceEngineLab` | Bootstraps the default strategy registry, returns an instance. |
| `list_strategies()` | `-> list[str]` | Sorted names of registered strategies. |
| `get_strategy(name)` | `(str) -> TranscriptionStrategy` | The strategy instance; raises `UnknownStrategyError` if absent. |
| `transcribe_file(strategy_name, audio_path)` | `(str, Path) -> Iterable[TranscriptEvent]` | Run a strategy over an audio file, lazily yielding events in audio-time order. |
| `match_cues(events, cue_atoms, *, use_semantic=False, semantic_threshold=0.55)` | `-> Iterable[CueDetection]` | Match transcript events against cue atoms. `use_semantic=False` = exact phrase + synonym only (fast, zero false positives). `use_semantic=True` = exact on all events plus embedding-based semantic matching on final events for misses (requires `fastembed`). |

### Registered strategies

After `VoiceEngineLab.load()`, `list_strategies()` returns exactly:

```
['apple_sfspeechrecognizer_vocab', 'apple_speech_transcriber', 'mock', 'sherpa_onnx', 'whisperkit']
```

| Name | Backing engine | Native CLI |
|---|---|---|
| `mock` | replays JSONL events (no dependency) | — |
| `apple_speech_transcriber` | Apple `SpeechAnalyzer`/`SpeechTranscriber` (macOS 26+), no custom vocab | `native/apple/AppleSTT` |
| `apple_sfspeechrecognizer_vocab` | Apple legacy `SFSpeechRecognizer`, supports dealership-vocab biasing | `native/apple/AppleSTT` |
| `whisperkit` | Argmax open-source WhisperKit (MIT, on-device Whisper) | `native/apple/WhisperKitSTT` |
| `sherpa_onnx` | sherpa-onnx Whisper-tiny ONNX (JVM); same lib as Android production | `native/android/SherpaOnnxSTT` |

**Not registered (dormant):** `argmax` (`ArgmaxStrategy`, paid Argmax Pro SDK 2
via the Local Server WebSocket). It is fully implemented but not bootstrapped —
construct it and call `register_strategy()` manually after setting
`ARGMAX_API_KEY` and installing the `[argmax]` extra. See
`lab/src/voice_lab/strategies/argmax.py`.

> The non-`mock` strategies spawn a native subprocess. On a host where the
> binary isn't built they don't fail at registration — they raise
> `TranscriptionError` (with a build hint) when `transcribe()` is called.

### The strategy contract — `TranscriptionStrategy`

`lab/src/voice_lab/strategies/base.py`:

```python
class TranscriptionStrategy(ABC):
    name: str = ""

    @abstractmethod
    def transcribe(self, audio_path: Path) -> Iterable[TranscriptEvent]:
        """Yield TranscriptEvents in audio-time order. Every strategy MUST
        emit both 'partial' and 'final' events and carry
        latency_ms_from_audio_start on each."""
```

### Data types — `lab/src/voice_lab/types.py`

Re-exported from `voice_lab` (`__init__.py`). **Field-identical** with the TS
types in `src/types/index.ts`; a lab drift test enforces parity.

```python
@dataclass(frozen=True)
class TranscriptEvent:
    text: str
    stability: Literal["partial", "final"]
    timestamp_ms: int
    latency_ms_from_audio_start: int
    confidence: float | None
    engine_metadata: dict[str, Any]

@dataclass(frozen=True)
class CueAtom:
    id: str
    display_name: str
    source: Literal["feature", "workflow"]
    cue_phrases: list[str]
    synonyms: list[str]
    metadata: dict[str, Any]

@dataclass(frozen=True)
class CueDetection:
    cue_id: str
    matched_phrase: str
    timestamp_ms: int
    confidence: float | None
    triggering_event: TranscriptEvent
    match_method: Literal["exact", "semantic"] = "exact"
    similarity_score: float | None = None
```

### Errors (exported from `voice_lab`)

`UnknownStrategyError`, `TranscriptionError`, `AudioFileError`.

### Minimal usage

```python
from pathlib import Path
from voice_lab import VoiceEngineLab, CueAtom

lab = VoiceEngineLab.load()
events = list(lab.transcribe_file("whisperkit", Path("clip.wav")))

atoms = [CueAtom(
    id="universal.feature.wireless_apple_carplay",
    display_name="Wireless Apple CarPlay",
    source="feature",
    cue_phrases=["wireless apple carplay", "wireless carplay"],
    synonyms=["carplay"],
    metadata={},
)]
detections = list(lab.match_cues(events, atoms))
```

> For full benchmark runs you normally use the `voice-lab` CLI
> (`lab/src/voice_lab/cli.py`, subcommand `run`) rather than driving the facade
> by hand. See [`flows.md`](./flows.md) §A and
> [`../lab/docs/pipeline.md`](../lab/docs/pipeline.md).

---

## 2. TypeScript — `VoiceEngine` (reference contract)

Import (reference only — **no consumer in this repo imports it**):

```ts
import { VoiceEngine } from 'voice-engine';
```

Defined in `src/facade.ts`. Only the `mock` strategy is wired; the apps mirror
this surface natively.

### `VoiceEngine`

| Method | Signature | Description |
|---|---|---|
| `VoiceEngine.load()` | `static -> VoiceEngine` | Bootstraps the registry (registers `mock` only). |
| `listStrategies()` | `-> string[]` | Registered strategy names. |
| `getStrategy(name)` | `(string) -> TranscriptionStrategy` | Throws `UnknownStrategyError` if absent. |
| `registerStrategy(strategy)` | `(TranscriptionStrategy) -> void` | Register an extra strategy at runtime (tests / consumers). |
| `startSession(strategyName, context)` | `(string, SessionContext) -> Session` | Live streaming session (`onEvent`, `stop`). |
| `matchCues(events, cueAtoms)` | `(AsyncIterable<TranscriptEvent>, CueAtom[]) -> AsyncIterable<CueDetection>` | Async cue matching. |

The live `TranscriptionStrategy` contract (`src/strategies/base.ts`) is
streaming (`start(context) -> Session`), the live-lane sibling of the lab's
batch `transcribe(audio_path)`.

### Exported symbols (`src/index.ts`)

STT surface:

```ts
export { VoiceEngine }                                  // facade
export { CueMatcher, matchCues, matchCuesAsync }        // matcher
export { MockTranscriptionStrategy }                    // only working strategy
export type { Session, TranscriptionStrategy }
export {
  registerStrategy, getStrategy, listStrategies,
  unregisterStrategy, getRegisteredStrategies, bootstrapDefaultStrategies,
}
export type {
  CueAtom, CueDetection, CueSource, SessionContext,
  Stability, TranscriptEvent, Unsubscribe,
}
export {
  UnknownStrategyError, TranscriptionError, AudioFileError,
  MicPermissionError, NotImplementedError,
}
```

> **Removed:** `AppleSpeechTranscriberStrategy` is **no longer exported** — the
> throwing TS stub at `src/strategies/apple-speech-transcriber.ts` was deleted.
> Older docs/examples that `import { AppleSpeechTranscriberStrategy }` are stale.

`src/capture/index.ts` is a **sketched** mic-capture interface only; it is not
implemented and not part of the live contract.

---

## 3. The cleanup contract — `CleanupStrategy`

The voice engine's **second** configurable model layer (the first is STT). The
language-neutral source of truth; native platforms implement the same shape.
Full discussion in [`model-contracts.md`](./model-contracts.md).

Cleanup surface (`src/index.ts`):

```ts
export type { CleanupStrategy }                         // base.ts
export { RuleBasedCleanupStrategy }                     // rule-based.ts (deterministic fallback)
export {
  registerCleanupStrategy, unregisterCleanupStrategy,
  getCleanupStrategy, listCleanupStrategies,
  bootstrapDefaultCleanupStrategies,                    // registers 'rule-based'
}
export type {
  CleanupLevel, CleanupRequest, CleanupResult,
  CommandGrammar, VocabMap,
}
export { CleanupError }
```

### Interface — `src/cleanup/base.ts`

```ts
interface CleanupStrategy {
  readonly name: string;                       // provider id: "foundation-models" | "gemini-nano" | "rule-based"
  clean(request: CleanupRequest): Promise<CleanupResult>;
}
```

### Types — `src/cleanup/types.ts`

```ts
type CleanupLevel = 'off' | 'light' | 'full';
type CommandGrammar = Record<string, string>;  // "new paragraph" -> "\n\n"
type VocabMap = Record<string, string>;        // forced spellings, applied AFTER cleanup

interface CleanupRequest {
  raw_text: string;
  level: CleanupLevel;
  vocab: VocabMap;
  command_grammar: CommandGrammar;
  profile?: string;                            // "dictation" | "road-to-sale" — selects a cleanup-pack
}

interface CleanupResult {
  cleaned_text: string;
  ops_applied: string[];                       // e.g. ["commands","fillers","punctuation"]
  used_fallback: boolean;                      // true if it fell back to rule-based
  latency_ms: number;
  engine_metadata: Record<string, unknown>;
}

class CleanupError extends Error {}
```

`RuleBasedCleanupStrategy` (`src/cleanup/rule-based.ts`, `name = 'rule-based'`)
is the always-available, dependency-free fallback that every platform falls back
to. It applies the command grammar, strips fillers, collapses repeats (at
`full`), normalizes whitespace/caps, then applies forced vocab — and never
paraphrases.

### Native mirror

The dictation app's `TextCleanup` protocol
(`../../dictation/Shared/Sources/DictationCore/TextCleanup.swift`) is the Swift
sibling of `CleanupStrategy`, with `FoundationModelsCleanup` (LLM) and
`RuleBasedCleanup` (fallback) implementations. It loads
`{profile}-cleanup-pack.json` from [`../cleanup-packs/`](../cleanup-packs/) at
runtime; a Swift test asserts the lists stay in sync with the pack.

---

## 4. Cleanup-pack data shape

`cleanup-packs/{dictation,road-to-sale}.json`. The contract the
`CleanupRequest` fields are populated from:

| Field | Type | Notes |
|---|---|---|
| `profile` | string | matches the file name and `CleanupRequest.profile`. |
| `min_words_for_cleanup` | int | skip cleanup below this word count. |
| `command_grammar` | object | feeds `CleanupRequest.command_grammar`. |
| `fillers` | string[] | disfluencies removed at `light`/`full`. |
| `junk_phrases` | string[] | STT hallucinations to drop (e.g. "thanks for watching"). |
| `prompts.{light,full}` | string | LLM cleanup system prompts per level. |
| `lexicon.terms` | string[] | *(road-to-sale only)* dealership glossary. |
| `lexicon.expansions` | object | *(road-to-sale only)* `"f and i" → "F&I"`, applied as forced `VocabMap`. |

Derived per-make vocab packs (`cleanup-packs/derived/<make>.vocab.json`) are
generated, not authored — see [`flows.md`](./flows.md) §C.
