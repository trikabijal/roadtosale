"""Strategy registry — single file to add a new STT engine.

Process: instantiate the strategy, call `register_strategy(instance)`.
The facade reads from `get_registered_strategies()`.
"""

from __future__ import annotations

from typing import Dict

from voice_lab.strategies.base import TranscriptionStrategy
from voice_lab.types import UnknownStrategyError


_REGISTRY: Dict[str, TranscriptionStrategy] = {}


def register_strategy(strategy: TranscriptionStrategy) -> None:
    if not strategy.name:
        raise ValueError("Strategy.name must be a non-empty string")
    _REGISTRY[strategy.name] = strategy


def unregister_strategy(name: str) -> None:
    _REGISTRY.pop(name, None)


def get_registered_strategies() -> Dict[str, TranscriptionStrategy]:
    return dict(_REGISTRY)


def get_strategy(name: str) -> TranscriptionStrategy:
    if name not in _REGISTRY:
        raise UnknownStrategyError(
            f"Unknown strategy '{name}'. Registered: {sorted(_REGISTRY)}"
        )
    return _REGISTRY[name]


def _bootstrap_default_strategies() -> None:
    """Register the default strategies. Called by the facade on load().

    Concrete strategies are imported lazily here to keep import cycles tame
    and to keep registry import side-effect-free for tests.
    """
    from voice_lab.strategies.mock import MockTranscriptionStrategy
    from voice_lab.strategies.apple_speech_transcriber import (
        AppleSpeechTranscriberStrategy,
    )
    from voice_lab.strategies.apple_sfspeechrecognizer import (
        AppleSFSpeechRecognizerStrategy,
    )

    if "mock" not in _REGISTRY:
        register_strategy(MockTranscriptionStrategy.empty())

    # Apple strategies. Construction is cheap and side-effect free —
    # subprocess only spawns inside transcribe(). It is fine to register
    # these on non-macOS hosts; failures surface as TranscriptionError when
    # transcribe() is called.
    if "apple_speech_transcriber" not in _REGISTRY:
        register_strategy(AppleSpeechTranscriberStrategy())
    if "apple_sfspeechrecognizer_vocab" not in _REGISTRY:
        register_strategy(AppleSFSpeechRecognizerStrategy())
