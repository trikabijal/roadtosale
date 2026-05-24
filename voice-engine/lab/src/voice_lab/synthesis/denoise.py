"""Neural noise suppression for lab audio fixtures.

Uses Facebook Research's denoiser (DNS64 model) — trained on the Microsoft
Deep Noise Suppression Challenge dataset, which covers real-world noise:
crowds, HVAC, music, office, traffic, restaurant, vehicle interiors.

This is a general-purpose denoiser appropriate for the dealership floor
environment. It is NOT tuned for white Gaussian noise — it handles the
non-stationary, multi-source noise conditions found in real deployments.

Naming convention for denoised files:
  snr5db.wav    →  snr5db_nr.wav    (nr = noise reduced)
  snr0db.wav    →  snr0db_nr.wav

The denoised files live in the same directory as their noisy source so the
transcript cache uses a distinct audio_id:
  synthesized/{asset}/snr5db     →  cache for noisy
  synthesized/{asset}/snr5db_nr  →  cache for denoised

Usage
-----
  from voice_lab.synthesis.denoise import denoise_wav, generate_denoised_variants

  denoise_wav(
      src=Path("data/audio/synthesized/script_001__neutral/snr5db.wav"),
      dst=Path("data/audio/synthesized/script_001__neutral/snr5db_nr.wav"),
  )

  # Generate _nr variants for a list of SNR files in a directory
  generate_denoised_variants(
      wav_dir=Path("data/audio/synthesized/script_001__neutral/"),
      snr_labels=["snr15db", "snr5db", "snr0db"],
  )

Requires
--------
  pip install denoiser   (pulls torch + torchaudio; no Rust needed)
"""

from __future__ import annotations

import logging
from pathlib import Path

logger = logging.getLogger(__name__)

# Module-level model cache — init once per Python process.
_model_cache: object | None = None


def _get_model():
    """Return the denoiser model, loading it the first time.

    DNS64 runs on CPU only — MPS (Apple Silicon) does not implement all
    convolution ops the model uses (convolution_overrideable), causing hard
    failures on long audio. CPU inference is fast enough for offline batch use.
    """
    global _model_cache
    if _model_cache is None:
        from denoiser import pretrained
        logger.info("Loading DNS64 denoiser model (first run — may download ~130 MB)…")
        print("  [denoise] loading DNS64 model (first run may download ~130 MB)…", flush=True)
        _model_cache = pretrained.dns64()
        _model_cache.eval()
        # Explicitly keep on CPU — MPS lacks convolution_overrideable for long audio.
    return _model_cache


def _denoise_tensor_chunked(model, wav: "torch.Tensor", chunk_sec: float = 120.0) -> "torch.Tensor":
    """Denoise a (channels, samples) tensor in overlapping chunks.

    DNS64 processes audio fine in a single forward pass for short clips, but
    for long audio (10–40 min YouTube videos) it can OOM even on CPU because
    the encoder-decoder activations scale with input length.

    Strategy:
      - Split into chunk_sec-second chunks with a 0.5-second overlap on each side.
      - Denoise each chunk independently.
      - Strip the overlap margins from each output chunk (guard region that
        may have edge artifacts from the encoder's receptive field).
      - Concatenate the stripped regions.

    Overlap is applied on the LEFT of each chunk (except the first) and on the
    RIGHT (except the last).  We keep only the interior of each denoised chunk.
    For a 0.5 s guard at 16 kHz that is 8 000 samples on each side.
    """
    import torch

    sr = model.sample_rate
    chunk_samples = int(chunk_sec * sr)
    guard = int(0.5 * sr)   # 8 000 samples at 16 kHz

    total = wav.shape[-1]
    if total <= chunk_samples + 2 * guard:
        # Short enough to process in one shot — no chunking needed.
        with torch.no_grad():
            return model(wav[None])[0]

    enhanced_parts: list["torch.Tensor"] = []
    pos = 0

    while pos < total:
        # Chunk with guard on both sides where available.
        chunk_start = max(0, pos - guard)
        chunk_end   = min(total, pos + chunk_samples + guard)
        chunk = wav[:, chunk_start:chunk_end]

        with torch.no_grad():
            denoised_chunk = model(chunk[None])[0]   # (C, T_chunk)

        # Trim guard regions from the denoised output.
        left_trim  = pos - chunk_start          # 0 for first chunk
        right_keep = pos + chunk_samples - chunk_start  # samples we want (no right guard)
        right_keep = min(right_keep, denoised_chunk.shape[-1])
        stripped = denoised_chunk[:, left_trim:right_keep]
        enhanced_parts.append(stripped)

        pos += chunk_samples
        if pos >= total:
            break

    return torch.cat(enhanced_parts, dim=-1)[:, :total]


def denoise_wav(
    src: Path,
    dst: Path,
    *,
    dry_run: bool = False,
    chunk_sec: float = 120.0,
) -> None:
    """Denoise src WAV with DNS64 and write to dst at the same sample rate.

    I/O is done entirely with soundfile + numpy to avoid torchaudio's
    TorchCodec dependency (required in torchaudio ≥ 2.x on macOS).
    Torch is used only for model inference and julius-based resampling.

    The DNS64 model targets 16 kHz mono. If the source is at a different
    sample rate it is resampled before inference and the output is saved at
    model.sample_rate (16 kHz for DNS64).

    Long audio (> chunk_sec seconds) is processed in overlapping chunks to
    avoid OOM on CPU. chunk_sec=120 (2 min) is safe for all machines.

    Args:
        src:       Source WAV file (must be readable by soundfile).
        dst:       Output path. Parent directories are created automatically.
        dry_run:   If True, skip actual inference (useful for testing the wiring).
        chunk_sec: Chunk size for long audio. Default 120 s.
    """
    import numpy as np
    import soundfile as sf
    import torch
    from denoiser.dsp import convert_audio

    if dry_run:
        import shutil
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(str(src), str(dst))
        return

    model = _get_model()
    device = next(model.parameters()).device

    # ── 1. Read source audio with soundfile (no TorchCodec needed) ──────────
    samples, orig_sr = sf.read(str(src), dtype="float32", always_2d=False)

    # Convert to torch tensor with explicit channel dim: (channels, samples).
    if samples.ndim == 1:
        wav = torch.from_numpy(samples).unsqueeze(0)   # mono → (1, T)
    else:
        wav = torch.from_numpy(samples.T)               # (T, C) → (C, T)

    # ── 2. Resample + mix to model's expected rate and channel count ─────────
    wav = convert_audio(wav, orig_sr, model.sample_rate, model.chin)
    wav = wav.to(device)

    # ── 3. Inference — chunked for long audio, single-pass for short ─────────
    dur_sec = wav.shape[-1] / model.sample_rate
    logger.debug("denoising %.1f s audio (chunk_sec=%.0f)", dur_sec, chunk_sec)
    if dur_sec > chunk_sec:
        n_chunks = int(dur_sec / chunk_sec) + 1
        print(f"  [denoise] {dur_sec:.0f} s audio → {n_chunks} chunks of {chunk_sec:.0f} s…", flush=True)
    enhanced = _denoise_tensor_chunked(model, wav, chunk_sec=chunk_sec)

    # ── 4. Back to CPU numpy and write with soundfile ────────────────────────
    enhanced_np = enhanced.cpu().numpy()   # (channels, samples)
    if enhanced_np.shape[0] == 1:
        enhanced_np = enhanced_np[0]       # mono: (samples,)
    enhanced_np = np.clip(enhanced_np, -1.0, 1.0).astype(np.float32)

    dst.parent.mkdir(parents=True, exist_ok=True)
    sf.write(str(dst), enhanced_np, model.sample_rate)
    logger.debug("denoised: %s → %s (%d Hz)", src, dst, model.sample_rate)


def generate_denoised_variants(
    wav_dir: Path,
    snr_labels: list[str] | None = None,
    *,
    force: bool = False,
) -> list[Path]:
    """For each snr*db.wav in wav_dir, write snr*db_nr.wav alongside it.

    Args:
        wav_dir:    Directory containing the noisy WAVs (same dir as clean.wav).
        snr_labels: Which noise levels to denoise (e.g. ["snr15db", "snr5db", "snr0db"]).
                    If None, discovers all snr*db.wav files automatically.
        force:      Re-run even if the _nr file already exists.

    Returns: List of output paths created or already existing.
    """
    if snr_labels is None:
        sources = sorted(wav_dir.glob("snr*db.wav"))
    else:
        sources = [wav_dir / f"{label}.wav" for label in snr_labels]

    out: list[Path] = []
    for src in sources:
        if not src.exists():
            logger.warning("source not found, skipping: %s", src)
            continue
        dst = src.parent / (src.stem + "_nr.wav")
        if dst.exists() and dst.stat().st_size > 0 and not force:
            logger.debug("skip (exists): %s", dst)
            out.append(dst)
            continue
        denoise_wav(src, dst)
        out.append(dst)
    return out
