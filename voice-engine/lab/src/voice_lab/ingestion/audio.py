"""Download YouTube audio and normalize it to the lab's standard WAV format.

Standard format (matches Argmax / AppleSTT / WhisperKit input requirements):
- 16 kHz sample rate
- mono
- PCM signed 16-bit little-endian

Pipeline: yt-dlp downloads the bestaudio m4a stream; ffmpeg converts to WAV.
Idempotent: if the target WAV already exists with non-zero size, skips.

Requires `yt-dlp` and `ffmpeg` on PATH. Both are widely available via
Homebrew, apt, or pip (yt-dlp).
"""

from __future__ import annotations

import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path


_DEFAULT_OUTPUT_DIR = Path("voice-engine/lab/fixtures/youtube")


@dataclass(frozen=True)
class DownloadResult:
    video_id: str
    audio_path: Path
    skipped: bool
    duration_seconds: float | None = None


class AudioFetchError(Exception):
    pass


def fetch_youtube_audio(
    video_id: str,
    output_dir: Path | None = None,
    *,
    force: bool = False,
) -> DownloadResult:
    """Download + normalize one YouTube video's audio.

    Args:
        video_id: YouTube video ID (the bit after `?v=` in the URL).
        output_dir: where to write the WAV. Defaults to
            voice-engine/lab/fixtures/youtube/.
        force: re-download even if the target WAV already exists.

    Returns:
        DownloadResult with the path and whether the file was skipped.

    Raises:
        AudioFetchError if yt-dlp / ffmpeg are missing or fail.
    """
    out_dir = output_dir or _DEFAULT_OUTPUT_DIR
    out_dir.mkdir(parents=True, exist_ok=True)
    wav_path = out_dir / f"{video_id}.wav"

    if wav_path.exists() and wav_path.stat().st_size > 0 and not force:
        return DownloadResult(
            video_id=video_id,
            audio_path=wav_path,
            skipped=True,
            duration_seconds=_probe_duration(wav_path),
        )

    _require_binary("yt-dlp")
    _require_binary("ffmpeg")

    tmp_audio = out_dir / f"{video_id}.download.m4a"
    if tmp_audio.exists():
        tmp_audio.unlink()

    url = f"https://www.youtube.com/watch?v={video_id}"
    ytdl_cmd = [
        "yt-dlp",
        "-q",
        "--no-warnings",
        "-f",
        "bestaudio[ext=m4a]/bestaudio",
        "-o",
        str(tmp_audio),
        url,
    ]
    _run(ytdl_cmd, what="yt-dlp")

    if not tmp_audio.exists():
        raise AudioFetchError(
            f"yt-dlp completed but no audio file produced at {tmp_audio}. "
            f"Video may be private, age-gated, or region-locked."
        )

    ffmpeg_cmd = [
        "ffmpeg",
        "-y",
        "-loglevel",
        "error",
        "-i",
        str(tmp_audio),
        "-ac",
        "1",
        "-ar",
        "16000",
        "-sample_fmt",
        "s16",
        str(wav_path),
    ]
    _run(ffmpeg_cmd, what="ffmpeg")
    tmp_audio.unlink(missing_ok=True)

    return DownloadResult(
        video_id=video_id,
        audio_path=wav_path,
        skipped=False,
        duration_seconds=_probe_duration(wav_path),
    )


def _require_binary(name: str) -> None:
    if shutil.which(name) is None:
        raise AudioFetchError(
            f"`{name}` is not on PATH. Install it before running fetch-youtube-audio "
            f"(`brew install {name}` on macOS)."
        )


def _run(cmd: list[str], *, what: str) -> None:
    try:
        result = subprocess.run(cmd, check=False, capture_output=True, text=True)
    except FileNotFoundError as exc:
        raise AudioFetchError(f"{what} not found on PATH: {exc}") from exc
    if result.returncode != 0:
        stderr = (result.stderr or "").strip()[:500]
        raise AudioFetchError(
            f"{what} failed (exit {result.returncode}): {stderr or '(no stderr)'}"
        )


def _probe_duration(wav_path: Path) -> float | None:
    try:
        import wave

        with wave.open(str(wav_path), "rb") as wf:
            return wf.getnframes() / wf.getframerate()
    except Exception:
        return None
