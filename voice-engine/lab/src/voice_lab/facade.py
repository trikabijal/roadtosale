"""VoiceEngineLab — the lab-side public facade.

Binding contract: see voice-engine/docs/api.md. This file is the only
public entry point for the Python lab.
"""

from __future__ import annotations

from pathlib import Path
from typing import Iterable

from voice_lab.matcher.cue_matcher import match_cues
from voice_lab.strategies import registry as _registry
from voice_lab.strategies.base import TranscriptionStrategy
from voice_lab.types import CueAtom, CueDetection, TranscriptEvent


class VoiceEngineLab:
    def __init__(self) -> None:
        pass

    @classmethod
    def load(cls) -> "VoiceEngineLab":
        _registry._bootstrap_default_strategies()
        return cls()

    def list_strategies(self) -> list[str]:
        return sorted(_registry.get_registered_strategies().keys())

    def get_strategy(self, name: str) -> TranscriptionStrategy:
        return _registry.get_strategy(name)

    def transcribe_file(
        self, strategy_name: str, audio_path: Path
    ) -> Iterable[TranscriptEvent]:
        strategy = self.get_strategy(strategy_name)
        return strategy.transcribe(Path(audio_path))

    def match_cues(
        self, events: Iterable[TranscriptEvent], cue_atoms: list[CueAtom]
    ) -> Iterable[CueDetection]:
        return match_cues(events, cue_atoms)
