"""Argmax Pro SDK 2 strategy — STUB.

Python bindings vs CLI wrapper for the Argmax SDK is unresolved.
See PRD OQ2 (Argmax Python bindings vs CLI). Until OQ2 is resolved,
this strategy raises NotImplementedError.
"""

from __future__ import annotations

from pathlib import Path
from typing import Iterable

from voice_lab.strategies.base import TranscriptionStrategy
from voice_lab.types import TranscriptEvent


class ArgmaxStrategy(TranscriptionStrategy):
    name = "argmax"

    def transcribe(self, audio_path: Path) -> Iterable[TranscriptEvent]:
        raise NotImplementedError(
            "ArgmaxStrategy is a stub. "
            "Resolve PRD OQ2 (Argmax Python bindings vs CLI) "
            "before wiring this strategy."
        )
