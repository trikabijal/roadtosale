# Voice Lab — Architecture

This document covers the component diagram, design decisions, isolation guarantees, and the facade contract.

---

## Component diagram

```
CLI (voice-lab)
    │
    ▼
voice_lab/cli.py
    │  discovers sources/, builds LabScripts, wires subcommands
    │
    ▼
voice_lab/orchestrator.py  ←──────────────── voice_lab/facade.py  (VoiceEngineLab)
    │  LabScript matrix                        │  single public entry point
    │  transcript cache                        │  wraps registry + matcher
    │  strategy × audio × cue loop            │
    │                                          │
    ├──── _get_events()                        ├── list_strategies()
    │     cache hit → load JSONL               ├── transcribe_file(strategy, path)
    │     cache miss → facade.transcribe_file  └── match_cues(events, atoms, …)
    │
    ├──── facade.match_cues()
    │
    └──── scoring/classify.py  →  CueClassification[]
               │
               ▼
          reporting/
               ├── markdown.py      summary.md, noise_comparison.md
               └── csv_writer.py    {strategy}-results.csv, false-positives.csv


Strategies (voice_lab/strategies/)
    ├── registry.py              _REGISTRY dict; register/get helpers
    ├── base.py                  TranscriptionStrategy protocol
    ├── apple_speech_transcriber.py   subprocess → native/apple/AppleSpeechTranscriber
    ├── apple_sfspeechrecognizer.py   subprocess → native/apple/AppleSFSpeechRecognizer
    ├── whisperkit.py                 subprocess → native/apple/WhisperKitSTT
    ├── sherpa_onnx.py                subprocess → native/android/SherpaOnnxSTT
    └── mock.py                       in-process mock for tests


Matcher (voice_lab/matcher/)
    ├── cue_matcher.py           exact layer — O(phrases × events), microseconds
    └── combined_matcher.py      exact + semantic (fastembed BAAI/bge-small-en-v1.5)


Scoring (voice_lab/scoring/)
    ├── classify.py              classify_run() → CueClassification[]
    └── latency.py               compute_engine_metrics(), latency_percentiles()


Synthesis (voice_lab/synthesis/)
    ├── tts.py                   ElevenLabs TTS → clean.wav
    └── noise.py                 white Gaussian noise → snrNdb.wav


Ingestion (voice_lab/ingestion/)
    ├── youtube_transcript.py    yt-dlp captions → sources/youtube/*.yaml
    └── youtube_audio.py         yt-dlp audio → data/audio/youtube/{id}/clean.wav


Native CLIs (invoked as subprocesses)
    voice-engine/native/apple/
        AppleSpeechTranscriber/     Swift — Apple SpeechTranscriber (iOS 26 / macOS Sequoia)
        WhisperKitSTT/              Swift — WhisperKit (Argmax, MIT)
    voice-engine/native/android/
        SherpaOnnxSTT/              JVM — sherpa-onnx Whisper ONNX
```

---

## The facade contract

`voice_lab/facade.py` is the **only file any external code imports**. The orchestrator, CLI, and any future adapters import `VoiceEngineLab` and nothing from `strategies/`, `matcher/`, or `scoring/` directly.

```python
from voice_lab.facade import VoiceEngineLab

engine = VoiceEngineLab.load()
strategies = engine.list_strategies()           # → ["apple_speech_transcriber", "whisperkit", …]
events = list(engine.transcribe_file("whisperkit", Path("audio.wav")))
detections = list(engine.match_cues(events, cue_atoms, use_semantic=True))
```

Everything behind the facade is internal. Adding a capability means adding it to the facade first, then implementing behind it.

**Version contract:** The facade method signatures are the stable API. The orchestrator consumes the facade, not any internal modules. Changes to internal strategies, matcher, or scoring are invisible to consumers.

---

## Design decisions

### Decision 1: Two-step run (transcription → cache → match/classify)

The run is split into two decoupled phases: STT subprocess execution and cue matching. The transcript JSONL cache sits between them.

**Why:** STT is expensive and deterministic. Matching and classification are cheap and evolving. The cache lets you:
- Re-run matching after a cue pack change without re-transcribing (instant, all cache hits).
- Inspect transcripts by hand for any audio_id.
- Compare strategies against the same audio by loading cached transcripts from disk.
- Replay historical audio without access to the original STT binaries.

**Trade-off:** Cache files can become stale if the STT binary is upgraded. Delete the relevant JSONL file to force re-transcription.

**Implementation:** `orchestrator._get_events()` — cache path `data/transcripts/{strategy}/{audio_id}.jsonl`.

---

### Decision 2: Strategy protocol — subprocess boundary

All STT strategies communicate with native CLIs via subprocess. The Python strategy layer converts subprocess output to a uniform `list[TranscriptEvent]`.

**Why:** Native CLIs (Swift, JVM) own their own runtime, model loading, and streaming state. Subprocess boundaries mean:
- Adding a new strategy requires zero changes to the Python orchestrator, matcher, or reporter.
- Native code can be tested independently (the Swift CLIs have their own test suites).
- The iOS and Android production paths use identical Swift/Kotlin code. The lab strategy is a thin wrapper that feeds a file path instead of a live microphone.

**Trade-off:** Subprocess spawn adds ~50 ms overhead per file. For a matrix of 64 files × 2 strategies = 128 subprocesses, this is negligible against STT run time (minutes total).

**Interface:**
```python
class TranscriptionStrategy(Protocol):
    name: str
    def transcribe(self, audio_path: Path) -> list[TranscriptEvent]: ...
```

---

### Decision 3: Two-layer cue matching (exact + semantic)

The cue matcher runs two layers in sequence on every `TranscriptEvent`:

1. **Exact layer** — case-insensitive substring match on every event (partials + finals). Fires in one partial window (~280 ms). Deterministic — zero false positives by design.
2. **Semantic layer** (`--semantic`) — sentence embedding cosine similarity on confirmed finals only. Catches paraphrases the exact layer misses. Adds ~8 ms per final utterance.

**Why separate layers:** The exact layer is the safe, always-on baseline. The semantic layer is optional and tunable. Running them independently means the exact layer can fire on partials (fast) while the semantic layer runs only on stable finals (accurate).

**Deduplication:** Only the first detection per cue per script is the primary detection. The orchestrator deduplicates at classification time. Both layers can fire on the same cue from different events.

**Semantic lift (run-20260524-1051, 64 clean+noisy fixtures × 2 strategies):**
- Apple SpeechTranscriber: +193 detections (14% of all detections were semantic-only)
- WhisperKit: +291 detections (21% of all detections were semantic-only)

The semantic layer is higher-value on WhisperKit because WhisperKit paraphrases more (higher WER on exact phrases, but correct sentence-level understanding). Semantic matching narrows the Apple/WhisperKit gap in clean conditions.

---

### Decision 4: audio_id as canonical asset identifier

Every audio file has a canonical `audio_id` that encodes its lineage:

```
{source_type}/{identifier}/{noise_level}
```

The `audio_id` is:
- The key in the transcript cache
- The `audio_id` column in the L1 CSV
- Derivable from the file path without any registry lookup

This means the cache, CSV, and file system are all navigable with the same key. A row in the L1 CSV can be traced to its transcript JSONL and its source WAV file without any indirection.

**Noise level suffix drives the noise comparison grouping.** The reporting layer groups rows by the `noise_level` segment at the end of `audio_id`. No separate metadata file is needed.

---

### Decision 5: Cue atoms as frozen dataclasses (not YAML at runtime)

At run time, cue packs are loaded from YAML (via `VehicleFeatureCatalog.load()` and the workflow YAML parser) and converted to `CueAtom` frozen dataclasses. The matching layer only ever sees `list[CueAtom]` — it has no knowledge of YAML, catalogs, or packs.

**Why frozen:** Cue atoms are matched against millions of transcript events. Frozen dataclasses are hashable and can be used in sets/dicts for deduplication without concern for mutation.

**Why dataclasses not dicts:** The matcher uses `atom.cue_phrases + atom.synonyms`. Named fields catch typos at code-write time, not at run time.

---

### Decision 6: Native CLIs as the production path

The Swift CLIs in `voice-engine/native/apple/` are **identical** to what ships in the iOS app. The only difference is audio source:
- Lab: CLI reads from a file path (`--input audio.wav`)
- iOS: `AVAudioSession` live microphone → same `SpeechTranscriber`/`AudioStreamTranscriber` API

**Why:** No simulation. Lab numbers are production numbers, modulo file vs microphone input. WhisperKit's `AudioStreamTranscriber` does not change behavior between file and live mic because it processes audio in chunks regardless of source.

This decision constrains the lab: it must run on macOS (for Apple Silicon Swift CLIs). There is no cross-platform runner. The Android SherpaOnnx strategy runs a JVM binary on macOS for lab evaluation, matching the same library that powers Android production.

---

### Decision 7: The lab tests raw audio — noise suppression is hardware-only on iPhone

The lab does **not** apply any software noise suppression. Every WAV file is fed to the STT strategies as-is: clean, or with white Gaussian noise added at a fixed SNR.

**Why:** The target platform is iPhone. On iPhone, noise suppression is not a software choice — it is always active in the hardware audio capture pipeline before any PCM frame reaches user space. `AUVoiceProcessingIO` runs in the kernel audio session layer; the app never sees raw microphone samples. There is no equivalent API that can be applied to a WAV file offline on macOS. Any software denoiser applied to files (e.g., DNS64) runs a different algorithm at file-level granularity and produces results that do not represent what the iPhone hardware does on 16–20 ms frame windows.

**Consequence for lab numbers:** The FNR figures in the lab (e.g., 8.2% for WhisperKit at SNR +5 dB showroom noise) are a **conservative lower bound**. In production on iPhone, the hardware voice processor will always be active; actual FNR at showroom noise will be lower than the lab figures by some amount that can only be measured with a real device test.

**How to measure the gap:** Record the lab's noisy WAV fixtures through an actual iPhone microphone (with `AVAudioSession` configured as the production app configures it), capture those recordings, and add them as lab fixtures (e.g., `snr5db_iphone.wav`). The reporting layer will pick them up automatically via the `_collect_audio_variants()` naming convention. This is a device test task, not a lab code task.

---

## Isolation guarantees

| Boundary | Isolation mechanism |
|---|---|
| Strategy ↔ orchestrator | `TranscriptionStrategy` protocol. Orchestrator only sees `list[TranscriptEvent]`. |
| Python lab ↔ native CLIs | Subprocess. Python never links to Swift or JVM code. |
| Transcript cache | JSONL files. Strategy and file path are both in the cache key. No cross-contamination between strategies or audio variants. |
| Run output | Timestamped directory (`runs/results/run-YYYYMMDD-HHMM/`). Runs never overwrite each other. |
| Audio variants | Separate files per variant; separate `audio_id` per variant. Any suffix (e.g. `_nr` for processed fixtures) gets its own cache entry automatically via the `audio_id` naming convention. |

---

## Data flow for a single cue detection

```
sources/scripts/script_001.yaml
    │  (LabScript built by CLI)
    ▼
data/audio/synthesized/script_001__us_neutral/snr5db.wav
    │  (audio path on LabScript)
    ▼
orchestrator._get_events()
    │  cache miss
    ▼
WhisperKitStrategy.transcribe(audio_path)
    │  subprocess: WhisperKitSTT --input snr5db.wav
    ▼
list[TranscriptEvent]
    │  saved to: data/transcripts/whisperkit/synthesized/script_001__us_neutral/snr5db.jsonl
    ▼
VoiceEngineLab.match_cues(events, cue_atoms, use_semantic=True)
    │  exact layer: "wireless Apple CarPlay" in event.text  → CueDetection (exact, partial)
    │  semantic layer: cosine(embedding("it hooks up your phone"), CarPlay_embedding) = 0.71  → CueDetection (semantic, final)
    ▼
classify_run(detections, expected_cues, …)
    │  deduplicates; extracts evidence windows
    ▼
CueClassification(outcome="pass", match_method="exact", delta_ms=-200, …)
    │
    ▼
runs/results/run-20260524-1051/whisperkit-results.csv   ← one row
runs/results/run-20260524-1051/noise_comparison.md      ← aggregated
```

---

## What is NOT in the lab (and why)

| Absent | Reason |
|---|---|
| Live microphone input | Lab is offline-only. Live mic is the production path on device. The Swift CLIs accept `--input` file only. |
| Hardware noise suppression | `AUVoiceProcessingIO` is a kernel-level audio unit tied to the iPhone hardware session — it cannot be applied to WAV files on macOS. The lab tests raw audio only. See Decision 7 for what this means for the numbers. |
| Speaker diarization | Not implemented in the free tier strategies. Planned for `argmax_pro` (Parakeet, paid). |
| Multi-tenant isolation | Lab is a single-researcher tool. Production multi-tenancy is handled by the server-side facade, not the lab. |
| Streaming results | The orchestrator processes files end-to-end. Streaming is a production concern. |
| Model training | The lab evaluates models; it does not train them. bge-small-en-v1.5 and Whisper weights are all pre-trained. |

---

## Planned extensions

| Extension | Notes |
|---|---|
| `sherpa_onnx` lab strategy | JVM CLI built. Python strategy `voice_lab/strategies/sherpa_onnx.py` registered. Needs end-to-end `voice-lab run --strategies sherpa_onnx` to confirm JVM binary builds and produces results. |
| `argmax_pro` | Parakeet streaming, speaker diarization. Android paid tier. Evaluate after sherpa_onnx is live. |
| iOS Swift cue matching | `ExactCueMatcher.swift` + `SemanticCueMatcher.swift` with CoreML bge-small-en-v1.5. TO BE IMPLEMENTED. |
| Phase 2 noise (DNS Challenge clips) | Replace white Gaussian noise with real-world DNS Challenge noise clips (crowd, HVAC, music). More realistic dealership floor testing. |
| S3 audio asset registry | `voice-lab data push/pull/status` + SQLite `assets` table. When disk space becomes a constraint. |
| `apple_speech_transcriber_live` | Live mic strategy for device testing without file I/O. |
