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

    if "mock" not in _REGISTRY:
        register_strategy(MockTranscriptionStrategy.empty())
