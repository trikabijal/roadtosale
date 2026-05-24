# Voice Lab — Cues and STT Strategies

This document covers the cue system (what a cue is, how they're structured, how matching works) and the STT strategy layer (what strategies are available, how they're registered, how to add a new one).

---

## The cue system

### What is a cue?

A **cue** is a detectable moment in a sales conversation that signals a specific step, feature demonstration, or workflow event has occurred. Cues are the atomic unit of the Road to Sale audit trail.

Examples:
- Rep offered hospitality (coffee, water) → `workflow.hospitality_offer`
- Rep mentioned Apple CarPlay → `universal.feature.wireless_apple_carplay`
- Rep mentioned Honda Sensing → `honda.feature.honda_sensing`
- Rep opened the walkaround → `workflow.walkaround_opening`

When the voice engine detects a cue, it fires a `CueDetection` event. The product uses these events to auto-mark workflow steps and build the audit trail.

---

### CueAtom — the schema

Every cue is a `CueAtom` (defined in `voice_lab/types.py`):

```python
@dataclass(frozen=True)
class CueAtom:
    id: str                    # e.g. "universal.feature.wireless_apple_carplay"
    display_name: str          # e.g. "Wireless Apple CarPlay"
    source: str                # "workflow" | "feature"
    cue_phrases: list[str]     # exact phrases to match (case-insensitive substring)
    synonyms: list[str]        # additional exact phrases
    metadata: dict             # category, brand_scope, feature_id, etc.
```

The combined match set for an atom is `cue_phrases + synonyms`. Both lists use identical matching logic — the separation is semantic (primary phrases vs alternative expressions), not functional.

---

### Cue packs

#### Universal workflow cues (`cue-packs/universal_workflow_cues.yaml`)

Step-by-step workflow cues that apply to any Honda walk. These are the cues that map to the Road to Sale process:

```yaml
cues:
  - id: workflow.hospitality_offer
    display_name: "Hospitality Offer"
    category: workflow
    cue_phrases:
      - coffee
      - water
      - anything to drink
      - grab you a drink
    synonyms:
      - refreshment
      - beverage

  - id: workflow.walkaround_opening
    display_name: "Walkaround Opening"
    category: workflow
    cue_phrases:
      - let me walk you through
      - let me show you
      - take a look at
    synonyms: []
```

#### Feature cues (vehicle-feature-catalog)

Feature cues come from the `vehicle-feature-catalog` package. Each feature YAML:

```yaml
id: universal.feature.wireless_apple_carplay
display_name: "Wireless Apple CarPlay"
category: infotainment
brand_scope: universal          # universal | honda | toyota | …

cue_phrases:
  - wireless Apple CarPlay
  - wireless CarPlay

synonyms:
  - CarPlay without a cable
  - plug-free CarPlay
```

The CLI loads these at runtime via `VehicleFeatureCatalog.load()`. If the catalog package is not installed, only workflow cues are used.

At run-20260524-0829: **286 cue atoms total** — 39 workflow + 247 feature cues.

---

### Two-layer matching

The matcher (`voice_lab/matcher/cue_matcher.py`) runs two layers in sequence on every `TranscriptEvent`:

#### Layer 1 — Exact matcher (always active)

For every event (partials + finals), checks if any cue phrase is a case-insensitive substring of the event text:

```python
for atom in cue_atoms:
    for phrase in (atom.cue_phrases + atom.synonyms):
        if phrase.lower() in event.text.lower():
            yield CueDetection(cue_id=atom.id, match_method="exact", ...)
```

- Fires on partials — first detection happens as soon as the first partial event contains the phrase. Typical latency: one partial window (~280 ms after the word is spoken).
- Deterministic — no model, no threshold, no false positives from this layer.
- Fast — O(phrases × events), typically microseconds per event.

#### Layer 2 — Semantic matcher (optional, `--semantic`)

For confirmed **final** events only, computes a sentence embedding and finds the closest cue:

```python
from fastembed import TextEmbedding

model = TextEmbedding("BAAI/bge-small-en-v1.5")

event_embedding = model.embed([event.text])
for atom in cue_atoms:
    cue_embedding = precomputed_cue_embeddings[atom.id]
    score = cosine_similarity(event_embedding, cue_embedding)
    if score >= threshold:
        yield CueDetection(cue_id=atom.id, match_method="semantic", match_score=score, ...)
```

- Only fires on finals — avoids noise from unstable partial text.
- Threshold default: 0.65 (tuned empirically on Honda walkaround data).
- Catches paraphrases the exact layer misses. Example: "it connects your iPhone without a wire" → `wireless_apple_carplay` (exact layer misses this; semantic fires at 0.71).
- Adds ~8 ms per final utterance on the lab machine.

#### Semantic lift

At clean conditions (run-20260524-0829):
- Apple + semantic: +98 detections (12% of all detections were semantic-only)
- WhisperKit + semantic: +139 detections (18% of all detections were semantic-only)

The semantic layer is higher-value on WhisperKit because WhisperKit paraphrases more — its model has higher WER on exact phrases but its sentence-level understanding is still accurate.

#### Deduplication

Both layers can fire on the same cue from different events (e.g., the exact layer fires on a partial, and the semantic layer fires on the subsequent final). The orchestrator deduplicates: only the first detection per cue per script is counted as the primary detection. Multiple detections strengthen confidence but don't inflate the detection count.

---

### CueDetection — the output

```python
@dataclass(frozen=True)
class CueDetection:
    cue_id: str
    matched_phrase: str          # the phrase that triggered the detection
    match_method: str            # "exact" | "semantic"
    match_score: float | None    # cosine similarity (semantic) or None (exact)
    timestamp_ms: int            # from the triggering TranscriptEvent
    event_text: str              # full text of the triggering event
    stability: str               # "partial" | "final"
```

---

### CueClassification — the L1 output

The classifier maps each `(expected_cue, detection_list)` pair to a `CueClassification`:

```python
@dataclass
class CueClassification:
    cue_id: str
    outcome: str                       # pass | partial | fail | false_positive
    detection: CueDetection | None     # null for fails
    reason: str
    # context fields
    script_id: str
    audio_id: str
    noise_level: str
    # investigation evidence
    expected_timestamp_ms: int | None
    reference_text: str
    engine_text_window_before: str     # finals in 5s window before expected
    engine_text_window_at: str         # finals in ±1s window at expected
    engine_text_window_after: str      # finals in 5s after expected
    caption_stt_agreement_score: float # Jaccard overlap: reference vs engine
    miss_type: str                     # filled post-hoc
    miss_notes: str
```

The `delta_ms` property: `detection.timestamp_ms - expected_timestamp_ms` (negative = detected early, positive = detected late or missed).

---

## STT strategies

### What is a strategy?

A strategy is a named, pluggable STT backend. The orchestrator calls `engine.transcribe_file(strategy_name, audio_path)` and gets back a stream of `TranscriptEvent` objects. The orchestrator does not know or care which engine is underneath.

All strategies share the same interface. The product can switch engines by changing strategy_name — the cue matching, classification, and reporting code is identical.

---

### Available strategies

#### `apple_speech_transcriber`

**Engine:** Apple SpeechTranscriber (iOS 26+ / macOS Sequoia+)  
**Lab CLI:** `voice-engine/native/apple/AppleSpeechTranscriber/` (Swift command-line tool)  
**Model:** Apple's on-device speech model (Neural Engine accelerated)  
**Format:** 16 kHz mono WAV  

Lab performance (10 Honda walkaround videos + 6 synthesized scripts):
- TTFT P50: 60 ms / P95: 120 ms
- TTFinal P50: 107 ms / P95: 258 ms
- RTF avg: 0.004 (250× real-time)
- FNR clean: 4.5% — FNR at SNR +5 dB: 8.6%
- FPR: 0.0% at all noise levels

**Noise characteristic:** Gentle degradation curve. Completely stable at SNR +15 dB. Loses 4 pp at +5 dB, 14 pp at 0 dB. The preferred engine for the dealership floor (the production operating point is +5 dB).

**iOS production path:** Identical Swift APIs. The only difference is audio source: lab CLIs feed a file path; iOS production feeds `AVAudioSession` live microphone. Zero code changes to the recognizer.

---

#### `whisperkit`

**Engine:** WhisperKit (Argmax, MIT licensed, iOS 17+)  
**Lab CLI:** `voice-engine/native/apple/WhisperKitSTT/` (Swift command-line tool)  
**Model:** Whisper base.en (CoreML, Neural Engine accelerated)  
**Algorithm:** `AudioStreamTranscriber` with LocalAgreement-2 confirmation (Macháček et al. 2023). `unconfirmedSegments` → partial stream, `confirmedSegments` → final stream. VAD chunking via built-in EnergyVAD.  
**Format:** 16 kHz mono WAV  

Lab performance:
- TTFT P50: 89 ms / P95: 206 ms
- TTFinal P50: 440 ms / P95: 631 ms
- RTF avg: 0.004
- FNR clean: 1.4% — FNR at SNR +5 dB: 20.0%
- FPR: 0.0% at all noise levels

**Noise characteristic:** Sharper in clean conditions (1.4% vs 4.5%). Falls off a cliff at showroom noise: +18.6 pp at +5 dB vs Apple's +4.1 pp. The clean-condition advantage disappears at the production operating point.

**Why it paraphrases more:** WhisperKit's sliding-window attention produces more natural-language output. The exact matcher misses more paraphrases, which is why semantic lift is higher (18% vs 12%). With semantic matching, the gap to Apple narrows in clean conditions.

**iOS production path:** Identical `AudioStreamTranscriber` API. Falls back from Apple SpeechTranscriber for iOS 17–25 where SpeechTranscriber is unavailable.

---

### Strategy registration

Strategies are registered in `voice_lab/strategies/registry.py`. Each strategy is a class that implements the `TranscriptionStrategy` protocol:

```python
class TranscriptionStrategy(Protocol):
    def transcribe_file(self, audio_path: Path) -> Iterator[TranscriptEvent]:
        """Stream TranscriptEvent objects for the given audio file."""
        ...
```

The `VoiceEngineLab` facade wraps the registry:

```python
engine = VoiceEngineLab.load()
engine.list_strategies()         # → ["apple_speech_transcriber", "whisperkit", …]
events = engine.transcribe_file("whisperkit", Path("audio.wav"))
```

### Adding a new strategy

1. Create `voice_lab/strategies/my_strategy.py`:
```python
class MyStrategy:
    def transcribe_file(self, audio_path: Path) -> Iterator[TranscriptEvent]:
        # invoke your STT engine (subprocess, API call, local model)
        for result in my_engine.transcribe(audio_path):
            yield TranscriptEvent(
                text=result.text,
                stability="final" if result.is_final else "partial",
                timestamp_ms=result.offset_ms,
                latency_ms_from_audio_start=result.offset_ms,
                confidence=result.confidence,
                engine_metadata={"model": "my_model"},
            )
```

2. Register it in `voice_lab/strategies/registry.py`:
```python
from voice_lab.strategies.my_strategy import MyStrategy
REGISTRY["my_strategy"] = MyStrategy()
```

3. Run:
```bash
voice-lab run --strategies my_strategy --semantic
```

The transcript cache, cue matching, classification, and all report writers work unchanged.

---

### Planned strategies

| Strategy | Status | Notes |
|---|---|---|
| `sherpa_onnx` | Lab CLI built | JVM-based Android tier 1. `voice-engine/native/android/SherpaOnnxSTT/`. Python lab strategy registration TO BE DONE. |
| `argmax_pro` | TO BE IMPLEMENTED | Android tier 2 paid path (Parakeet streaming, speaker diarization). Evaluate after sherpa_onnx is live. |
| `apple_speech_transcriber_live` | TO BE IMPLEMENTED | Live mic strategy for device testing (vs file-based lab strategy). |

---

## The VoiceEngineLab facade

`voice_lab/facade.py` is the only public entry point. Everything behind it is internal.

```python
from voice_lab.facade import VoiceEngineLab

engine = VoiceEngineLab.load()

# List registered strategies
strategies = engine.list_strategies()

# Transcribe a file
events: list[TranscriptEvent] = list(engine.transcribe_file("whisperkit", Path("audio.wav")))

# Match cues against transcript events
detections: list[CueDetection] = list(
    engine.match_cues(
        events,
        cue_atoms,
        use_semantic=True,
        semantic_threshold=0.65,
    )
)
```

The orchestrator, CLI, and any future adapters import only `VoiceEngineLab`. They never import from `strategies/`, `matcher/`, or `scoring/` directly.
