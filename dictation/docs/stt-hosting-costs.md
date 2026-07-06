# STT hosting & cost economics

Decision-support doc for where speech-to-text (STT) runs across Just Talk's platforms.
Captures what we learned comparing **on-device** vs **cloud** STT, the per-user cost math, and
the resulting strategy. Source of the numbers is vendor-published pricing (July 2026); treat as
estimates — cloud pricing drifts and tiers down with volume.

## TL;DR

- **On-device is $0 marginal cost, private, offline** — our whole thesis. It's constrained only by the
  user's hardware.
- **Cloud STT costs recur per user and grow with usage** — the opposite economics.
  Amazon Transcribe at ~$1.44/audio-hour would eat a $15/mo subscription on a heavy user.
- Cloud vendors that offer flat-rate dictation (e.g. Wispr Flow) **self-host Whisper on GPU**, not
  Amazon Transcribe — that's ~7–14× cheaper at scale.
- **Our design:** on-device by default (free), thin cloud fallback only for weak/unsupported machines
  or hard languages. ~95% of traffic stays at $0; we pay a vendor only for the tail.

## The engines, by platform

| Platform | On-device engine | Accelerator | Multilingual (Hinglish/Gujarati) |
|---|---|---|---|
| macOS | WhisperKit (CoreML) / Apple SpeechAnalyzer | Apple Neural Engine | WhisperKit ✅ / Apple English-lean |
| iOS (keyboard) | Apple SpeechAnalyzer (fits ~60 MB extension cap) | ANE | English-lean; large models don't fit the keyboard |
| Android (IME) | Sherpa-ONNX (already in repo) | NNAPI / CPU | ✅ with the right model |
| Windows | Sherpa-ONNX / whisper.cpp | CUDA/TensorRT (NVIDIA), DirectML (Intel/AMD), CPU | ✅ (Whisper large-v3) |

**Key distinction:** *WhisperKit* is a CoreML runtime — **Apple-only**. The *Whisper model* itself runs
on-device everywhere via other runtimes (whisper.cpp, Sherpa-ONNX, ONNX Runtime). So "on-device on
Windows" is real — just not via WhisperKit.

## Cloud options & cost

### A — Amazon Transcribe (managed ASR)
Zero ops, per-minute billing.
- ~**$0.024/min = $1.44 per audio-hour** (first 250k min/mo; tiers down to ~$0.0078/min at high volume).

### B — Self-host Whisper on EC2 GPU (faster-whisper / Sherpa-ONNX)
Whisper large-v3 runs ~5–10× faster than realtime on an A10G, so one GPU backs many users.
- `g5.xlarge` (A10G) ≈ **$1.01/hr on-demand** (~$0.35/hr spot).
- With batching + decent utilization → **~$0.10–0.20 per audio-hour**. 7–14× cheaper than Transcribe.
- Cost is ops burden + cold-start latency + you now hold user audio (privacy tradeoff).

### Cleanup LLM (if run in cloud)
Bedrock Haiku-class, ~200–500 tokens per dictation → **< $0.001 each**. Negligible either way.

## Cost per user / month

| Usage | Transcribe (A) | Self-host GPU (B) | On-device (ours) |
|---|---|---|---|
| Light (~5 audio-hr/mo) | ~$7.20 | ~$0.50–1.00 | **$0** |
| Heavy (~15 audio-hr/mo) | ~$21.60 | ~$1.50–3.00 | **$0** |

A heavy user on Transcribe (~$22) would exceed a $15/mo plan — which is exactly why flat-rate cloud
dictation vendors self-host Whisper on GPU rather than use managed ASR.

## NVIDIA / Windows GPU acceleration

NVIDIA is actively pushing on-device inference (it sells RTX/CUDA). Relevant pieces:

- **TensorRT / TensorRT-LLM** — Whisper compiled for RTX GPUs, far faster than CPU.
- **Parakeet / Canary (NeMo)** — NVIDIA's own ASR models; top speed/accuracy, GPU-native, English-strong.
- **Riva** — GPU speech SDK (ASR/TTS), fully on-device on NVIDIA.
- **faster-whisper (CTranslate2)** and **whisper.cpp** — both have CUDA backends.

**Catch:** CUDA only helps machines *with* an NVIDIA GPU. Most thin-and-light Windows laptops have
integrated Intel/AMD graphics. That fragmentation is why Wispr Flow's Windows build is x64 cloud-only
(one code path for everyone) and excludes ARM/Snapdragon.

## The strategy engine (all platforms)

Same pattern as the macOS two-lane voice engine: pick the backend by detected hardware, keep on-device
default, fall back to cloud only when forced.

| Detected hardware | Lane | Cost |
|---|---|---|
| Apple Silicon (Mac/iOS) | WhisperKit / Apple ANE | $0 |
| NVIDIA RTX (Windows) | TensorRT / CUDA Whisper (or Parakeet) | $0 |
| Other GPU (Windows) | DirectML | $0 |
| CPU-only (Android/Windows) | whisper.cpp / Sherpa-ONNX CPU | $0 |
| Weak / unsupported / hard language | **cloud (self-hosted Whisper GPU)** | ~$0.10–0.20/audio-hr |

Model choice is **per-language** (Parakeet/Apple for English speed; Whisper large-v3 for
Hinglish/Gujarati); backend choice is **per-hardware**.

## What's shared across platforms (DRY core)

The cleanup **contract + data pack + telemetry schema** are portable by design, and **Sherpa-ONNX** is
the common cross-platform STT runtime (Android + Windows, and a fallback elsewhere). Only the app shell
and accelerator wiring are per-platform.

## Competitor reference — Wispr Flow (Windows), July 2026

- OS: Windows 10 (1903+) / 11. CPU: **x64 only**, ARM/Snapdragon unsupported. RAM: 8 GB+. ~200 MB.
- Mic **and internet required** — dictation is **cloud**, not on-device.
- Reviewers: functional but less polished/stable than the Mac version.

Sources: Wispr Flow help center (supported devices & system requirements); Amazon Transcribe / EC2
public pricing; NVIDIA RTX AI / NeMo / Riva product pages.
