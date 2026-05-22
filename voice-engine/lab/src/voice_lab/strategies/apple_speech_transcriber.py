"""Apple SpeechTranscriber strategy — STUB.

The macOS Python binding mechanism for Apple's SpeechTranscriber is
unresolved. See PRD OQ1 (Apple SpeechTranscriber macOS variant).
Until OQ1 is resolved, this strategy raises NotImplementedError.
"""

from __future__ import annotations

from pathlib import Path
from typing import Iterable

from voice_lab.strategies.base import TranscriptionStrategy
from voice_lab.types import TranscriptEvent


class AppleSpeechTranscriberStrategy(TranscriptionStrategy):
    name = "apple_speech_transcriber"

    def transcribe(self, audio_path: Path) -> Iterable[TranscriptEvent]:
        raise NotImplementedError(
            "AppleSpeechTranscriberStrategy is a stub. "
            "Resolve PRD OQ1 (Apple SpeechTranscriber macOS variant) "
            "before wiring this strategy."
        )
