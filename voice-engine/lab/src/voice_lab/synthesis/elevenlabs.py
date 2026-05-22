"""ElevenLabs synthesis provider — STUB.

The ElevenLabs API key and the set of voice IDs we'll use for accent
coverage are unresolved. See PRD OQ3 (ElevenLabs API key + voice IDs).
Until OQ3 is resolved, this provider raises NotImplementedError.
"""

from __future__ import annotations

from pathlib import Path

from voice_lab.synthesis.base import SynthesisProvider, SynthesisRequest


class ElevenLabsSynthesisProvider(SynthesisProvider):
    name = "elevenlabs"

    def synthesize(self, request: SynthesisRequest) -> Path:
        raise NotImplementedError(
            "ElevenLabsSynthesisProvider is a stub. "
            "Resolve PRD OQ3 (ElevenLabs API key and voice IDs) "
            "before invoking synthesis."
        )
