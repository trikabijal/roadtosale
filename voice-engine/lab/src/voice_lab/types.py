"""Shared data types for the voice engine lab.

These shapes are field-identical with the TypeScript types in
`voice-engine/src/types/index.ts`. A drift test enforces this.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Literal


Stability = Literal["partial", "final"]
CueSource = Literal["feature", "workflow"]


@dataclass(frozen=True)
class TranscriptEvent:
    text: str
    stability: Stability
    timestamp_ms: int
    latency_ms_from_audio_start: int
    confidence: float | None
    engine_metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class CueAtom:
    id: str
    display_name: str
    source: CueSource
    cue_phrases: list[str]
    synonyms: list[str]
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class CueDetection:
    cue_id: str
    matched_phrase: str
    timestamp_ms: int
    confidence: float | None
    triggering_event: TranscriptEvent


class UnknownStrategyError(Exception):
    pass


class TranscriptionError(Exception):
    pass


class AudioFileError(Exception):
    pass
