"""Apple SpeechTranscriber strategy.

Wraps the AppleSTT Swift CLI (see voice-engine/native/apple/AppleSTT) via
subprocess and streams JSONL transcript events back into the lab as
``TranscriptEvent`` objects.

The Swift CLI is invoked with ``--mode speech_transcriber`` which uses
Apple's SpeechAnalyzer + SpeechTranscriber stack introduced in
macOS 26 / iOS 26 (Tahoe). This path does NOT support custom vocabulary;
for dealership-term biasing the lab also registers
``AppleSFSpeechRecognizerStrategy`` (see apple_sfspeechrecognizer.py).

Binary discovery:
    - Default: ``voice-engine/native/apple/AppleSTT/.build/release/AppleSTT``
      relative to the repo root.
    - Override with the ``APPLE_STT_BIN`` environment variable.
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


# Repo-anchored default to the .app bundle (required for TCC authorization).
# Resolved from this file's location so the path is correct regardless of CWD.
#
# This file lives at:
#   voice-engine/lab/src/voice_lab/strategies/apple_speech_transcriber.py
# We want:
#   voice-engine/native/apple/AppleSTT/AppleSTT.app/Contents/MacOS/AppleSTT
# which is parents[4]/native/apple/AppleSTT/AppleSTT.app/Contents/MacOS/AppleSTT.
_DEFAULT_BIN = (
    Path(__file__).resolve().parents[4]
    / "native"
    / "apple"
    / "AppleSTT"
    / "AppleSTT.app"
    / "Contents"
    / "MacOS"
    / "AppleSTT"
)


def _resolve_binary() -> Path:
    """Return the AppleSTT binary path, honoring APPLE_STT_BIN if set."""
    override = os.environ.get("APPLE_STT_BIN")
    if override:
        return Path(override).expanduser()
    return _DEFAULT_BIN


class AppleSpeechTranscriberStrategy(TranscriptionStrategy):
    """SpeechTranscriber (no custom vocab) strategy."""

    name = "apple_speech_transcriber"

    def __init__(
        self,
        *,
        binary_path: Path | None = None,
        locale: str = "en-US",
        partials: bool = True,
    ) -> None:
        self._binary_path = Path(binary_path) if binary_path else None
        self._locale = locale
        self._partials = partials

    # Internal hook so subclasses (e.g. SFSpeechRecognizer variant) can
    # override the CLI args without copy-pasting the subprocess plumbing.
    def _build_argv(self, binary: Path, audio_path: Path) -> list[str]:
        return [
            str(binary),
            "--file",
            str(audio_path),
            "--mode",
            "speech_transcriber",
            "--locale",
            self._locale,
            "--partials",
            "true" if self._partials else "false",
        ]

    def transcribe(self, audio_path: Path) -> Iterable[TranscriptEvent]:
        """Yield TranscriptEvents in audio-time order.

        Streams stdout from the AppleSTT subprocess line-by-line; one JSON
        event per line. Backpressure is handled by the caller iterating
        lazily — we do not buffer the full output in memory.
        """
        return _run_apple_stt(
            argv_builder=lambda bin_path: self._build_argv(bin_path, audio_path),
            binary_path=self._binary_path,
            audio_path=audio_path,
            engine_label=self.name,
        )


# ---------------------------------------------------------------------------
# Shared subprocess plumbing
# ---------------------------------------------------------------------------


def _run_apple_stt(
    *,
    argv_builder,
    binary_path: Path | None,
    audio_path: Path,
    engine_label: str,
) -> Iterator[TranscriptEvent]:
    """Spawn AppleSTT, stream JSONL stdout, yield TranscriptEvent.

    Used by both the SpeechTranscriber and the SFSpeechRecognizer wrappers.
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
        # If swift is installed, the user just needs to build. Otherwise we
        # have to flag the missing toolchain.
        swift_present = shutil.which("swift") is not None
        if swift_present:
            hint = (
                "Build the Swift CLI first:  "
                "voice-engine/native/apple/build.sh"
            )
        else:
            hint = (
                "Install the Swift toolchain (Xcode) and then run "
                "voice-engine/native/apple/build.sh"
            )
        raise TranscriptionError(
            f"{engine_label}: AppleSTT binary not found or not executable at "
            f"{binary}. {hint}. You can also set APPLE_STT_BIN to point at an "
            "alternate location."
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
            f"{engine_label}: failed to spawn AppleSTT subprocess ({binary}): {e}"
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
                # Drain remaining stderr so we can surface it.
                _terminate(proc)
                raise TranscriptionError(
                    f"{engine_label}: failed to parse AppleSTT JSON event "
                    f"({e}): {line!r}"
                ) from e

            yield _event_from_json(raw)
    finally:
        # Make sure the subprocess always closes; consumer might break early.
        stderr_text = ""
        try:
            stderr_text = proc.stderr.read() or ""
        except Exception:
            pass
        rc = proc.wait()
        if rc != 0:
            # Re-raise only if the consumer hasn't already raised an exception
            # that ended iteration; the generator finalization path runs after
            # the consumer's exception, so we still want to surface non-zero
            # rc as a clear TranscriptionError on the next __next__ call. But
            # if the generator has been GC'd cleanly, we can't raise out of
            # here without confusing the caller; we just let it propagate via
            # the loop above (which will have already raised before we got
            # here on bad output).
            raise TranscriptionError(
                f"{engine_label}: AppleSTT exited with code {rc}. "
                f"stderr: {stderr_text.strip() or '<empty>'}"
            )


def _terminate(proc: subprocess.Popen) -> None:
    try:
        proc.terminate()
    except Exception:
        pass


def _event_from_json(raw: dict) -> TranscriptEvent:
    """Validate + coerce a single AppleSTT JSON line into a TranscriptEvent."""
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
            f"AppleSTT emitted malformed event: {raw!r} ({e})"
        ) from e
    return TranscriptEvent(
        text=text,
        stability=event_type,  # type: ignore[arg-type]
        timestamp_ms=timestamp_ms,
        latency_ms_from_audio_start=latency_ms,
        confidence=confidence,
        engine_metadata=engine_metadata,
    )
