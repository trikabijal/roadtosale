"""Mock transcription strategy — replays events from a JSONL file.

JSONL line format matches TranscriptEvent fields exactly:
    {"text": "...", "stability": "partial"|"final",
     "timestamp_ms": int, "latency_ms_from_audio_start": int,
     "confidence": float|null, "engine_metadata": {}}
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Iterable, Iterator

from voice_lab.strategies.base import TranscriptionStrategy
from voice_lab.types import TranscriptEvent


class MockTranscriptionStrategy(TranscriptionStrategy):
    name = "mock"

    def __init__(self, events_file: Path | None = None) -> None:
        self._events_file = Path(events_file) if events_file is not None else None

    @classmethod
    def empty(cls) -> "MockTranscriptionStrategy":
        return cls(events_file=None)

    def transcribe(self, audio_path: Path) -> Iterable[TranscriptEvent]:
        source = self._events_file if self._events_file is not None else Path(audio_path)
        return list(self._iter_from(source))

    def _iter_from(self, path: Path) -> Iterator[TranscriptEvent]:
        if not path.exists():
            return
        with path.open("r", encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                raw = json.loads(line)
                yield TranscriptEvent(
                    text=raw["text"],
                    stability=raw["stability"],
                    timestamp_ms=int(raw["timestamp_ms"]),
                    latency_ms_from_audio_start=int(raw["latency_ms_from_audio_start"]),
                    confidence=raw.get("confidence"),
                    engine_metadata=raw.get("engine_metadata", {}),
                )
