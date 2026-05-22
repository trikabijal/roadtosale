"""Tests for the VoiceEngineLab facade."""

from __future__ import annotations

from pathlib import Path

import pytest

from voice_lab import VoiceEngineLab, UnknownStrategyError
from voice_lab.strategies import registry as _registry
from voice_lab.strategies.mock import MockTranscriptionStrategy


FIXTURE = Path(__file__).parent / "fixtures" / "sample_events.jsonl"


def test_load_returns_facade():
    engine = VoiceEngineLab.load()
    assert isinstance(engine, VoiceEngineLab)


def test_list_strategies_includes_mock():
    engine = VoiceEngineLab.load()
    assert "mock" in engine.list_strategies()


def test_get_strategy_unknown_raises():
    engine = VoiceEngineLab.load()
    with pytest.raises(UnknownStrategyError):
        engine.get_strategy("nope")


def test_transcribe_file_with_mock_replays_jsonl():
    engine = VoiceEngineLab.load()
    _registry.register_strategy(MockTranscriptionStrategy(events_file=FIXTURE))
    events = list(engine.transcribe_file("mock", FIXTURE))
    assert len(events) == 5
    assert events[0].stability == "partial"
    assert events[1].stability == "final"
    assert events[-1].text == "thank you for visiting"


def test_apple_speech_stub_raises_not_implemented():
    from voice_lab.strategies.apple_speech_transcriber import (
        AppleSpeechTranscriberStrategy,
    )

    strat = AppleSpeechTranscriberStrategy()
    with pytest.raises(NotImplementedError):
        list(strat.transcribe(Path("/dev/null")))


def test_argmax_stub_raises_not_implemented():
    from voice_lab.strategies.argmax import ArgmaxStrategy

    strat = ArgmaxStrategy()
    with pytest.raises(NotImplementedError):
        list(strat.transcribe(Path("/dev/null")))
