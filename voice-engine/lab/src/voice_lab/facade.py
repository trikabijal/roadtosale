"""VoiceEngineLab — the lab-side public facade.

Binding contract: see voice-engine/docs/api.md. This file is the only
public entry point for the Python lab.
"""

from __future__ import annotations

from pathlib import Path
from typing import Iterable

from voice_lab.matcher.cue_matcher import match_cues as _exact_match_cues
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
        self,
        events: Iterable[TranscriptEvent],
        cue_atoms: list[CueAtom],
        *,
        use_semantic: bool = False,
        semantic_threshold: float = 0.55,
    ) -> Iterable[CueDetection]:
        """Match transcript events against cue atoms.

        use_semantic=False (default): exact phrase + synonym matching only.
            Fast, zero false positives. Use for baseline runs.

        use_semantic=True: exact matching on all events PLUS embedding-based
            semantic matching on final events for cues exact matching missed.
            Catches paraphrases ("engine shuts off at lights" → idle-stop).
            Requires fastembed to be installed.
        """
        if use_semantic:
            from voice_lab.matcher.combined_matcher import match_cues_combined
            return match_cues_combined(
                events, cue_atoms, semantic_threshold=semantic_threshold
            )
        return _exact_match_cues(events, cue_atoms)
