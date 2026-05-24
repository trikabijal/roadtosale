# Road to Sale — Audio Research Reading List

Papers and references relevant to the voice engine architecture. Read before proposing major changes to the STT, VAD, or cue-matching layers.

Last updated: `2026-05-24`

---

## Streaming Speech Recognition

### Macháček, Dabre & Bojar — *Turning Whisper into Real-Time Transcription System* (2023)
**arXiv:** https://arxiv.org/abs/2307.14743  
**Why it matters:** Canonical algorithm for streaming Whisper. Introduces **LocalAgreement-n**: confirm a token only when the longest common prefix matches across N consecutive hypothesis buffers. Achieves 3.3 s end-to-end latency on unsegmented speech. This is the algorithm WhisperKit implements internally — our two-lane (hypothesis/confirmed) architecture maps directly onto it.  
**Reference implementation:** https://github.com/ufal/whisper_streaming

### Orhon, Okan, Durmus, Nagengast, Pacheco — *WhisperKit: On-device Real-time ASR with Billion-Scale Transformers* (2025)
**arXiv:** https://arxiv.org/abs/2507.10860  
**Venue:** ICML 2025 On-Device Learning Workshop  
**Why it matters:** WhisperKit's own paper. Key result: block-diagonal attention mask on the encoder (15-second blocks) enables KV-caching → **65% latency reduction** (612 → 218 ms) at <1% WER cost. Benchmarks: 0.45 s mean per-word latency on hypothesis stream, 2.2% WER (beats gpt-4o-transcribe and Deepgram nova-3). Confirms our chosen engine is state of the art.

---

## Spoken Language Understanding / Cue Detection

### Chen et al. — *A Streaming End-to-End Framework for Spoken Language Understanding* (2021)
**arXiv:** https://arxiv.org/abs/2105.10042  
**Why it matters:** Closest paper to our cue-detection goal. Unidirectional RNN + CTC that processes multiple intents incrementally and fires when "sufficient evidence accumulated." No mobile-ready implementation exists; confirms our approach (pre-computed embeddings + cosine match on confirmed text stream) is the right design.

### Ghannay et al. — *RNN-based Incremental Online Spoken Language Understanding* (2019)
**arXiv:** https://arxiv.org/abs/1910.10287  
**Why it matters:** Lexical end-of-sentence detector for streaming intent classification. Background for the streaming SLU problem.

### Shon et al. — *Leveraging Acoustic and Linguistic Embeddings from Pretrained Speech and Language Models for Intent Classification* (2021)
**arXiv:** https://arxiv.org/abs/2102.07370  
**Why it matters:** Cross-modal embedding approach for intent detection from speech. Supports the embedding-based cue matching we implemented.

---

## Voice Activity Detection

### Silero VAD v5 — snakers4/silero-vad
**GitHub:** https://github.com/snakers4/silero-vad  
**Release notes:** https://github.com/snakers4/silero-vad/discussions/471  
**Why it matters:** Current best on-device VAD (2026). 2 MB ONNX model, 32 ms chunks, 3× faster than v4, 4× fewer errors than WebRTC VAD at 5% FPR. iOS/Android/macOS parity via ONNX Runtime. Default speech-probability threshold 0.5; tune up (0.6–0.7) for noisy dealership environments.

---

## Inter-Pausal Units (IPU) and Silence Thresholds

### Rieser & Lemon — *NaturalTurn: Predicting Turn Boundaries in Natural Conversation* (2025)
**Source:** Nature Scientific Reports — https://www.nature.com/articles/s41598-025-24381-1  
**Why it matters:** Empirical IPU statistics in natural conversation. Mean IPU length ~2 s. Mean turn includes several IPUs. Establishes the psycholinguistic ground truth for our silence threshold choices.

### Pal et al. — *IPU-based approach for Indic TTS* (2024)
**arXiv:** https://arxiv.org/abs/2409.11915  
**Why it matters:** Reviews IPU threshold literature. T(sil) choices: 100 ms (minimum), 200–250 ms (conversational), 400 ms (coincides with punctuation ~95%), 500 ms (production end-of-turn default). **Our choice: 300 ms utterance boundary, 800 ms–1.2 s end-of-turn**, based on this and LiveKit/Pipecat production defaults.

---

## On-Device STT Benchmarks

### Argmax — *Apple SpeechAnalyzer vs WhisperKit* (2025)
**URL:** https://www.argmaxinc.com/blog/apple-and-argmax  
**Why it matters:** Head-to-head benchmark. WhisperKit base: 15.2% WER, 111× real-time. WhisperKit small: 12.8% WER, 35×. Apple SpeechTranscriber: 14.0% WER, 70×. Our lab data: WhisperKit + semantic at 0.65 threshold → 2.6% FNR on Honda walkaround cues; Apple + semantic → 8.8% FNR.

### VoicePing — *Offline Speech Transcription Benchmark* (2026)
**URL:** https://voiceping.net/en/blog/research-offline-speech-transcription-benchmark/  
**Why it matters:** Independent 16-model benchmark. Use to cross-check WER claims.

### Picovoice — *Android Speech Recognition in 2026*
**URL:** https://picovoice.ai/blog/android-speech-recognition/  
**Why it matters:** Android-specific STT landscape. sherpa-onnx identified as best free option; Argmax Pro SDK for highest accuracy.

---

## Android Runtime Options

We need an on-device STT engine for Android that:
- runs fully offline (no cloud)
- produces streaming partials + stable finals
- is accurate enough for cue detection (not perfect transcription)
- is MIT/Apache licensed

### Evaluated options

| Stack | Strength | Weakness | License | Status |
|---|---|---|---|---|
| **sherpa-onnx + Whisper-tiny** | ONNX runtime, Silero VAD built-in, JVM bindings for lab | Offline recognizer only (no true streaming for Whisper) | MIT | **Built — lab CLI done, Android TO BE IMPLEMENTED** |
| **whisper.cpp** | Huge ecosystem, GGML backend (faster CPU than ONNX), Android JNI wrapper exists | Needs manual JNI layer, no built-in VAD | MIT | Evaluate as alternative if sherpa-onnx accuracy is insufficient |
| **MediaPipe + Gemini Nano** | Tight Android integration, Google-maintained | Not Whisper, lower accuracy, requires Pixel 9+ | Apache | Monitor — not ready for broad Android |
| **Vosk** | Truly lightweight, real-time streaming, official Android AAR | Lower accuracy than Whisper-class models | Apache | Fallback for very low-end devices |
| **Parakeet-TDT-0.6B** | 6.32% WER (best accuracy), fastest inference | Argmax Pro SDK only (paid); no open-source Android streaming | Proprietary | Tier 2 paid path |
| **Argmax Pro SDK 2** | Best accuracy + real-time Parakeet streaming + speaker diarization | Paid, Kotlin/LiteRT only | Commercial | **Tier 2 paid path — TO BE IMPLEMENTED** |

### Key links

- **sherpa-onnx:** https://github.com/k2-fsa/sherpa-onnx
- **whisper.cpp:** https://github.com/ggml-org/whisper.cpp
- **Argmax Pro SDK for Android:** https://www.argmaxinc.com/blog/argmax-pro-sdk-for-android — see `dev/docs/ROAD_TO_SALE_AUDIO_RESEARCH_ARGMAX.md`

---

## Cloud STT Competitors (track for on-device moves)

These companies currently dominate cloud STT. Track their research publications and any on-device announcements — the landscape is moving fast and any of them could ship an on-device model that changes our decision.

### Deepgram
**URL:** https://deepgram.com / **Research:** https://deepgram.com/research  
**Strength:** nova-3 model — best-in-class WER on real-world speech (not clean read speech). Real-time streaming. Speaker diarization. 35+ languages.  
**On-device status:** No on-device product as of 2026. Watch for edge deployment announcements.  
**Why we track:** Benchmark reference. Our WhisperKit + semantic (2.6% FNR) needs to be compared against Deepgram nova-3 on the same dealer audio to understand the gap.

### AssemblyAI
**URL:** https://www.assemblyai.com / **Research:** https://www.assemblyai.com/research  
**Strength:** Universal-1 model — strong multilingual, speaker diarization, speaker labels. Best-in-class summarization layer.  
**On-device status:** Cloud only as of 2026.  
**Why we track:** Multilingual support matters if we expand beyond English-speaking markets. Their speaker diarization research is relevant to multi-person sessions.

### Fireworks AI
**URL:** https://fireworks.ai  
**Strength:** Extremely fast inference (< 300 ms end-to-end latency on Whisper large-v3-turbo). Competitive WER.  
**On-device status:** Cloud inference only.  
**Why we track:** Speed benchmark reference. WhisperKit at 0.45 s mean per-word latency matches Fireworks on hypothesis stream (per arXiv 2507.10860). If Fireworks ships edge/on-device, re-evaluate.

### Speechmatics
**URL:** https://www.speechmatics.com / **Research:** https://www.speechmatics.com/research  
**Strength:** Strongest accent and dialect handling. Best non-native English speaker WER. Real-time streaming.  
**On-device status:** No on-device product as of 2026, but they publish strong language research.  
**Why we track:** Dealer floors have diverse accents. If Speechmatics ships on-device, it may outperform Whisper for non-native English speakers. Track their research papers.
