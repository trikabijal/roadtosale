"""Noise overlay synthesis — mixes white Gaussian noise into a WAV at a target SNR.

Uses soundfile + numpy only (no pydub / audioop — works on Python 3.13+).

SNR (Signal-to-Noise Ratio) in dB:
  +15 dB — quiet room, faint background noise (minimal impact on ASR)
   +5 dB — typical showroom floor, general conversation background
    0 dB — noisy environment: signal and noise at equal power (hard for ASR)

Usage
-----
  from voice_lab.synthesis.noise import add_noise_at_snr

  add_noise_at_snr(
      src=Path("data/audio/synthesized/script_001__us_baseline_neutral/clean.wav"),
      dst=Path("data/audio/synthesized/script_001__us_baseline_neutral/snr5db.wav"),
      snr_db=5.0,
  )
"""

from __future__ import annotations

import math
from pathlib import Path

import numpy as np


def add_noise_at_snr(
    src: Path,
    dst: Path,
    snr_db: float,
    *,
    seed: int | None = 42,
) -> None:
    """Read src WAV, mix white Gaussian noise at snr_db, write dst WAV.

    The noise is scaled so that:
        SNR_dB = 20 * log10(rms_signal / rms_noise)

    Args:
        src:    Source WAV (any sample rate / bit depth — must be readable by soundfile).
        dst:    Output WAV (same format as src).
        snr_db: Target SNR in dB. Lower = more noise. Typical range: -5 to +20.
        seed:   RNG seed for reproducibility. None = random.
    """
    import soundfile as sf  # local import — keeps top-level import cheap

    samples, sr = sf.read(str(src), dtype="float32", always_2d=False)

    rng = np.random.default_rng(seed)
    noise = rng.standard_normal(samples.shape).astype(np.float32)

    # Scale noise to achieve target SNR.
    rms_signal = math.sqrt(float(np.mean(samples ** 2)))
    rms_noise_raw = math.sqrt(float(np.mean(noise ** 2)))

    if rms_signal == 0.0:
        raise ValueError(f"Source audio {src} is silent — cannot compute SNR.")

    # rms_noise_target = rms_signal / 10^(snr_db / 20)
    rms_noise_target = rms_signal / (10.0 ** (snr_db / 20.0))
    scale = rms_noise_target / rms_noise_raw
    noise_scaled = noise * scale

    mixed = samples + noise_scaled

    # Clip to [-1.0, 1.0] to prevent soundfile overflow on integer formats.
    mixed = np.clip(mixed, -1.0, 1.0)

    dst.parent.mkdir(parents=True, exist_ok=True)
    sf.write(str(dst), mixed, sr)


def generate_noisy_variants(
    src: Path,
    snr_levels_db: list[float],
    *,
    seed: int | None = 42,
) -> list[Path]:
    """Generate one noisy variant per SNR level alongside the source file.

    Output files are written into the same directory as src:
        src.parent / f"snr{n}db.wav"   (n is the absolute value of snr_db)

    Returns the list of output paths created.
    """
    out: list[Path] = []
    for snr_db in snr_levels_db:
        label = f"snr{int(round(snr_db))}db"
        dst = src.parent / f"{label}.wav"
        add_noise_at_snr(src, dst, snr_db, seed=seed)
        out.append(dst)
    return out
