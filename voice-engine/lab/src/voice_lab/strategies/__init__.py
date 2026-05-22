"""Strategy package. Public surface is `base.TranscriptionStrategy` and
`registry` helpers. Concrete strategies are imported by the registry."""

from voice_lab.strategies.base import TranscriptionStrategy

__all__ = ["TranscriptionStrategy"]
