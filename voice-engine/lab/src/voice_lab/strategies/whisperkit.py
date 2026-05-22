"""WhisperKit strategy.

Wraps the WhisperKitSTT Swift CLI (see voice-engine/native/apple/WhisperKitSTT)
via subprocess and streams JSONL transcript events back into the lab as
``TranscriptEvent`` objects.

The Swift CLI wraps Argmax's open-source WhisperKit
(https://github.com/argmaxinc/WhisperKit, MIT licensed). This is the
**free** path: WhisperKit is the on-device Whisper engine that the paid
Argmax Pro SDK builds on. For lab evaluation on macOS we use the
open-source build directly; the paid Pro SDK / Local Server path lives
in ``argmax.py`` and remains the Android cross-platform option.

Binary discovery:
    - Default: ``voice-engine/native/apple/WhisperKitSTT/.build/release/WhisperKitSTT``
      relative to the repo root.
    - Override with the ``WHISPERKIT_BIN`` environment variable.

Model selection:
    - Default: ``openai_whisper-tiny.en`` (smallest English-only model).
    - Override per-instance via the ``model`` constructor argument, or
      globally via the ``WHISPERKIT_MODEL`` environment variable.

See ``dev/docs/ROAD_TO_SALE_AUDIO_RESEARCH_ARGMAX.md`` for the
relationship between WhisperKit (open source) and Argmax Pro SDK (paid).
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
from pathlib import Path
from typing import Iterable, Iterator

from voice_lab.strategies.base import TranscriptionStrategy
from voice_lab.types import TranscriptEvent, TranscriptionError


# Repo-anchored default binary location. Resolved from this file's location
# so the path is correct regardless of CWD.
#
# This file lives at:
#   voice-engine/lab/src/voice_lab/strategies/whisperkit.py
# We want:
#   voice-engine/native/apple/WhisperKitSTT/.build/release/WhisperKitSTT
# which is parents[4]/native/apple/WhisperKitSTT/.build/release/WhisperKitSTT.
_DEFAULT_BIN = (
    Path(__file__).resolve().parents[4]
    / "native"
    / "apple"
    / "WhisperKitSTT"
    / ".build"
    / "release"
    / "WhisperKitSTT"
)

_DEFAULT_MODEL = "openai_whisper-tiny.en"


def _resolve_binary() -> Path:
    """Return the WhisperKitSTT binary path, honoring WHISPERKIT_BIN if set."""
    override = os.environ.get("WHISPERKIT_BIN")
    if override:
        return Path(override).expanduser()
    return _DEFAULT_BIN


def _resolve_model() -> str:
    """Return the WhisperKit model name, honoring WHISPERKIT_MODEL if set."""
    override = os.environ.get("WHISPERKIT_MODEL")
    if override:
        return override
    return _DEFAULT_MODEL


class WhisperKitStrategy(TranscriptionStrategy):
    """WhisperKit (open-source Whisper) strategy."""

    name = "whisperkit"

    def __init__(
        self,
        *,
        binary_path: Path | None = None,
        model: str | None = None,
        partials: bool = True,
        vocab_path: Path | None = None,
    ) -> None:
        self._binary_path = Path(binary_path) if binary_path else None
        self._model = model
        self._partials = partials
        self._vocab_path = Path(vocab_path) if vocab_path else None

    def _build_argv(self, binary: Path, audio_path: Path) -> list[str]:
        model = self._model or _resolve_model()
        argv = [
            str(binary),
            "--file",
            str(audio_path),
            "--model",
            model,
            "--partials",
            "true" if self._partials else "false",
        ]
        if self._vocab_path is not None:
            if not self._vocab_path.exists():
                raise TranscriptionError(
                    f"{self.name}: vocab file not found at {self._vocab_path}. "
                    "Create it or pass an existing path."
                )
            argv.extend(["--vocab", str(self._vocab_path)])
        return argv

    def transcribe(self, audio_path: Path) -> Iterable[TranscriptEvent]:
        """Yield TranscriptEvents in audio-time order.

        Streams stdout from the WhisperKitSTT subprocess line-by-line; one
        JSON event per line. Backpressure is handled by the caller iterating
        lazily — we do not buffer the full output in memory.
        """
        return _run_whisperkit_stt(
            argv_builder=lambda bin_path: self._build_argv(bin_path, audio_path),
            binary_path=self._binary_path,
            audio_path=audio_path,
            engine_label=self.name,
        )


# ---------------------------------------------------------------------------
# Shared subprocess plumbing
# ---------------------------------------------------------------------------


def _run_whisperkit_stt(
    *,
    argv_builder,
    binary_path: Path | None,
    audio_path: Path,
    engine_label: str,
) -> Iterator[TranscriptEvent]:
    """Spawn WhisperKitSTT, stream JSONL stdout, yield TranscriptEvent.

    Lazy — does not buffer full stdout. Raises TranscriptionError on any
    failure with a fix-it hint.
    """
    audio_path = Path(audio_path)
    if not audio_path.exists():
        raise TranscriptionError(
            f"audio file not found: {audio_path}. "
            "Pass an existing path to transcribe()."
        )

    binary = Path(binary_path) if binary_path else _resolve_binary()
    if not binary.exists() or not os.access(binary, os.X_OK):
        swift_present = shutil.which("swift") is not None
        if swift_present:
            hint = (
                "Build the Swift CLI first:  "
                "voice-engine/native/apple/whisperkit_build.sh"
            )
        else:
            hint = (
                "Install the Swift toolchain (Xcode) and then run "
                "voice-engine/native/apple/whisperkit_build.sh"
            )
        raise TranscriptionError(
            f"{engine_label}: WhisperKitSTT binary not found or not "
            f"executable at {binary}. {hint}. You can also set WHISPERKIT_BIN "
            "to point at an alternate location."
        )

    argv = argv_builder(binary)

    try:
        proc = subprocess.Popen(
            argv,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=1,  # line-buffered
            text=True,
            encoding="utf-8",
        )
    except OSError as e:
        raise TranscriptionError(
            f"{engine_label}: failed to spawn WhisperKitSTT subprocess "
            f"({binary}): {e}"
        ) from e

    assert proc.stdout is not None
    assert proc.stderr is not None

    try:
        for line in proc.stdout:
            line = line.strip()
            if not line:
                continue
            try:
                raw = json.loads(line)
            except json.JSONDecodeError as e:
                _terminate(proc)
                raise TranscriptionError(
                    f"{engine_label}: failed to parse WhisperKitSTT JSON "
                    f"event ({e}): {line!r}"
                ) from e

            yield _event_from_json(raw)
    finally:
        stderr_text = ""
        try:
            stderr_text = proc.stderr.read() or ""
        except Exception:
            pass
        rc = proc.wait()
        if rc != 0:
            raise TranscriptionError(
                f"{engine_label}: WhisperKitSTT exited with code {rc}. "
                f"stderr: {stderr_text.strip() or '<empty>'}"
            )


def _terminate(proc: subprocess.Popen) -> None:
    try:
        proc.terminate()
    except Exception:
        pass


def _event_from_json(raw: dict) -> TranscriptEvent:
    """Validate + coerce a single WhisperKitSTT JSON line into a
    TranscriptEvent."""
    try:
        event_type = raw["type"]
        if event_type not in ("partial", "final"):
            raise ValueError(f"unknown event type: {event_type!r}")
        text = str(raw["text"])
        timestamp_ms = int(raw["timestamp_ms"])
        latency_ms = int(raw["latency_ms_from_audio_start"])
        confidence_raw = raw.get("confidence")
        confidence: float | None = (
            float(confidence_raw) if confidence_raw is not None else None
        )
        engine_metadata = raw.get("engine_metadata", {}) or {}
        if not isinstance(engine_metadata, dict):
            raise ValueError("engine_metadata must be an object")
    except (KeyError, TypeError, ValueError) as e:
        raise TranscriptionError(
            f"WhisperKitSTT emitted malformed event: {raw!r} ({e})"
        ) from e
    return TranscriptEvent(
        text=text,
        stability=event_type,  # type: ignore[arg-type]
        timestamp_ms=timestamp_ms,
        latency_ms_from_audio_start=latency_ms,
        confidence=confidence,
        engine_metadata=engine_metadata,
    )
