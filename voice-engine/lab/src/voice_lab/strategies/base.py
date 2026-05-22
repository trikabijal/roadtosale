"""Strategy base contract — every STT engine implements this."""

from __future__ import annotations

from abc import ABC, abstractmethod
from pathlib import Path
from typing import Iterable

from voice_lab.types import TranscriptEvent


class TranscriptionStrategy(ABC):
    name: str = ""

    @abstractmethod
    def transcribe(self, audio_path: Path) -> Iterable[TranscriptEvent]:
        """Yield TranscriptEvents in audio-time order.

        Every strategy MUST emit both 'partial' and 'final' events,
        each tagged via TranscriptEvent.stability.
        Every event MUST carry latency_ms_from_audio_start.
        """
        raise NotImplementedError
