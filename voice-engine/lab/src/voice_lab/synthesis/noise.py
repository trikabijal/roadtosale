"""Noise overlay synthesis provider — STUB.

A canonical dealership-floor noise track has not been sourced yet.
See PRD OQ7 (noise track sourcing). Until OQ7 is resolved, this
provider raises NotImplementedError.
"""

from __future__ import annotations

from pathlib import Path

from voice_lab.synthesis.base import SynthesisProvider, SynthesisRequest


class NoiseOverlayProvider(SynthesisProvider):
    name = "noise_overlay"

    def synthesize(self, request: SynthesisRequest) -> Path:
        raise NotImplementedError(
            "NoiseOverlayProvider is a stub. "
            "Resolve PRD OQ7 (noise track sourcing) "
            "before invoking noise overlay."
        )
