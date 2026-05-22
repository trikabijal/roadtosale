"""Synthesis provider base contract.

Synthesis is lab-only — it produces audio fixtures we then feed into the
strategies. Two providers in v1: ElevenLabs (voices) and noise overlay.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from pathlib import Path


@dataclass(frozen=True)
class SynthesisRequest:
    script_id: str
    text: str
    voice_id: str
    output_path: Path
    metadata: dict[str, str] = field(default_factory=dict)


class SynthesisProvider(ABC):
    name: str = ""

    @abstractmethod
    def synthesize(self, request: SynthesisRequest) -> Path:
        """Produce audio at request.output_path. Idempotent: if the file
        already exists, MAY skip and return the existing path."""
        raise NotImplementedError
