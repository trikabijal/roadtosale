# Voice Engine — API Reference

Last updated: `2026-05-22`

The voice engine owns audio capture, strategy-based STT, and generic cue-phrase matching. It is brand-agnostic and product-agnostic — it does not know Honda or Road to Sale exist.

This document is the binding facade contract. Everything listed here is public. Everything not listed is internal and may NOT be imported by external code.

## Two facades

Two facades for two consumer shapes. Same core types, same strategy registry, different transcription entry points (file-in for the lab; live mic for the mobile library).

| Facade | Consumer | Entry point |
|---|---|---|
| `VoiceEngineLab` | Python lab (offline comparison) | `from voice_lab import VoiceEngineLab` |
| `VoiceEngine` | TypeScript library (live mobile) | `import { VoiceEngine } from 'voice-engine'` |

## `VoiceEngineLab` (Python)

### Lifecycle

```python
engine = VoiceEngineLab.load()
```

`load()` is no-arg. It initializes the strategy registry (discovers strategies that have called `register_strategy(...)`). It does NOT load audio or fixtures — those come per-call.

### Strategy management

| Method | Returns |
|---|---|
| `list_strategies()` | `list[str]` — registered strategy names |
| `get_strategy(name)` | `TranscriptionStrategy` — raises `UnknownStrategyError` if not registered |

### Transcription

```python
def transcribe_file(
    self,
    strategy_name: str,
    audio_path: Path,
) -> Iterable[TranscriptEvent]: ...
```

Streams `TranscriptEvent`s for the given audio file through the named strategy. Caller iterates lazily; events arrive in audio-time order.

### Cue matching

```python
def match_cues(
    self,
    events: Iterable[TranscriptEvent],
    cue_atoms: list[CueAtom],
) -> Iterable[CueDetection]: ...
```

Takes a transcript event stream + the active cue-atom set, emits `CueDetection` events as matches are found. Matching is phrase + synonym-aware. Caller supplies the active atoms; engine does not load them.

## `VoiceEngine` (TypeScript)

### Lifecycle

```ts
const engine = VoiceEngine.load();
```

### Strategy management

| Method | Returns |
|---|---|
| `listStrategies()` | `string[]` |
| `getStrategy(name)` | `TranscriptionStrategy` |

### Live session

```ts
startSession(strategyName: string, context: SessionContext): Session;

interface Session {
  onEvent(handler: (e: TranscriptEvent) => void): Unsubscribe;
  stop(): Promise<void>;
}
```

Starts microphone capture, runs the selected strategy, emits `TranscriptEvent`s through `onEvent`. `stop()` flushes any final events and releases the audio session.

### Cue matching

```ts
matchCues(
  events: AsyncIterable<TranscriptEvent>,
  cueAtoms: CueAtom[],
): AsyncIterable<CueDetection>;
```

Same semantics as Python.

## Strategy interface (the contract every engine implements)

Every STT engine implements this. There is no other way for an engine to enter the system.

### Python

```python
class TranscriptionStrategy(ABC):
    name: str   # "apple_speech_transcriber", "argmax", "deepgram", ...

    @abstractmethod
    def transcribe(self, audio_path: Path) -> Iterable[TranscriptEvent]: ...
```

### TypeScript

```ts
export interface TranscriptionStrategy {
  name: string;
  start(context: SessionContext): Session;
}
```

### Required behavior (both languages)

1. **Both stability streams.** Every strategy MUST emit BOTH `partial` and `final` `TranscriptEvent`s through one event channel, tagged with `stability`. A strategy that only emits one stream is non-conforming.
2. **Wall-clock latency.** Every event MUST carry `latency_ms_from_audio_start` measured from audio start to the moment the engine produced the event.
3. **Engine metadata.** Free-form `engine_metadata: dict` — opaque to the engine layer, used by reporting and debugging.
4. **No global state.** A strategy instance is reusable across audio inputs / sessions. No hidden singletons.

## Strategy registry

Strategies register themselves via one call. Adding a new strategy is a one-file change outside the strategy itself.

### Python

```python
# voice-engine/lab/src/voice_lab/strategies/registry.py
from voice_lab.strategies.apple_speech_transcriber import AppleSpeechTranscriberStrategy
from voice_lab.strategies.argmax import ArgmaxStrategy

register_strategy(AppleSpeechTranscriberStrategy())
register_strategy(ArgmaxStrategy())
```

### TypeScript

```ts
// voice-engine/src/strategies/registry.ts
import { AppleSpeechTranscriberStrategy } from './apple-speech-transcriber';
import { MockTranscriptionStrategy } from './mock';

registerStrategy(new AppleSpeechTranscriberStrategy());
registerStrategy(new MockTranscriptionStrategy());
```

## Shared data types

Hand-written in each language. Identical fields. Drift caught by a cross-language fixture test.

### `TranscriptEvent`

```python
@dataclass
class TranscriptEvent:
    text: str
    stability: Literal['partial', 'final']
    timestamp_ms: int                       # audio-relative
    latency_ms_from_audio_start: int        # wall-clock from audio start
    confidence: float | None
    engine_metadata: dict
```

```ts
export interface TranscriptEvent {
  text: string;
  stability: 'partial' | 'final';
  timestamp_ms: number;
  latency_ms_from_audio_start: number;
  confidence: number | null;
  engine_metadata: Record<string, unknown>;
}
```

### `CueAtom`

```python
@dataclass
class CueAtom:
    id: str                                 # "honda.feature.honda_sensing_360plus" or "workflow.hospitality_offer"
    display_name: str
    source: Literal['feature', 'workflow']
    cue_phrases: list[str]
    synonyms: list[str]
    metadata: dict                          # opaque, for consumer back-references
```

```ts
export interface CueAtom {
  id: string;
  display_name: string;
  source: 'feature' | 'workflow';
  cue_phrases: string[];
  synonyms: string[];
  metadata: Record<string, unknown>;
}
```

### `CueDetection`

```python
@dataclass
class CueDetection:
    cue_id: str
    matched_phrase: str
    timestamp_ms: int
    confidence: float | None
    triggering_event: TranscriptEvent       # the event the match was found in
```

```ts
export interface CueDetection {
  cue_id: string;
  matched_phrase: string;
  timestamp_ms: number;
  confidence: number | null;
  triggering_event: TranscriptEvent;
}
```

### `SessionContext`

```ts
export interface SessionContext {
  session_id: string;
  language: string;                         // e.g. 'en-US'
  custom_vocabulary: string[];              // dealership terms to bias the recognizer
}
```

(Python equivalent omitted — the lab uses `transcribe_file`, not live sessions, so `SessionContext` is TS-only.)

## What this facade does NOT expose

| Concern | Why not | Lives where |
|---|---|---|
| Cue atom definitions (phrases, synonyms) | Voice engine doesn't define cues — it consumes them | `vehicle-feature-catalog` (feature cues) + consumer-side workflow cue file |
| Audit-item logic | Voice engine is product-agnostic | `road-to-sale-app` |
| Scoring rules (pass/partial/fail) | Lab-only concern | `voice-engine/lab/src/voice_lab/scoring/` |
| Report generation | Lab-only concern | `voice-engine/lab/src/voice_lab/reporting/` |
| Vendor SDK details (Apple Speech, Argmax) | Behind individual strategies | `voice-engine/lab/src/voice_lab/strategies/*` and `voice-engine/ios/`, `voice-engine/android/` |

## Errors

| Error | Python | TS | When |
|---|---|---|---|
| `UnknownStrategyError` | exception | thrown | `get_strategy` / `getStrategy` called with unregistered name |
| `TranscriptionError` | exception | thrown | Strategy failed mid-stream (e.g. SDK init failed) |
| `AudioFileError` | exception | thrown | `transcribe_file` couldn't read or decode the audio |
| `MicPermissionError` | — | thrown | Live session denied microphone access |
