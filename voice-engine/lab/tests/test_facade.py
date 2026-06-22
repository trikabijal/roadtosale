"""Tests for the VoiceEngineLab facade."""

from __future__ import annotations

from pathlib import Path

import pytest

from voice_lab import VoiceEngineLab, UnknownStrategyError
from voice_lab.strategies import registry as _registry
from voice_lab.strategies.mock import MockTranscriptionStrategy
from voice_lab.types import CueAtom


FIXTURE = Path(__file__).parent / "fixtures" / "sample_events.jsonl"


def test_load_returns_facade():
    engine = VoiceEngineLab.load()
    assert isinstance(engine, VoiceEngineLab)


def test_list_strategies_includes_mock():
    engine = VoiceEngineLab.load()
    assert "mock" in engine.list_strategies()


def test_list_strategies_is_exact_sorted_set_without_argmax():
    """P-FAC-2: pin the exact five registered names (sorted) and assert the
    dormant ``argmax`` strategy is NOT auto-registered."""
    engine = VoiceEngineLab.load()
    assert engine.list_strategies() == [
        "apple_sfspeechrecognizer_vocab",
        "apple_speech_transcriber",
        "mock",
        "sherpa_onnx",
        "whisperkit",
    ]
    assert "argmax" not in engine.list_strategies()


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


def test_apple_speech_strategy_is_no_longer_a_stub():
    """The Apple SpeechTranscriber strategy was replaced with a real
    subprocess-driven implementation. Behavior tests for the AppleSTT
    wrapper live in tests/test_apple_strategy.py — they use a fake binary
    so they don't require swift or speech-framework auth."""
    from voice_lab.strategies.apple_speech_transcriber import (
        AppleSpeechTranscriberStrategy,
    )

    # Constructor must be side-effect free (no subprocess spawn).
    strat = AppleSpeechTranscriberStrategy()
    assert strat.name == "apple_speech_transcriber"


def test_argmax_strategy_requires_api_key(monkeypatch):
    from voice_lab.strategies.argmax import ArgmaxStrategy
    from voice_lab.types import TranscriptionError

    monkeypatch.delenv("ARGMAX_API_KEY", raising=False)
    strat = ArgmaxStrategy()
    with pytest.raises(TranscriptionError, match="ARGMAX_API_KEY"):
        list(strat.transcribe(Path("/dev/null")))


def test_match_cues_through_facade_returns_detections():
    """P-FAC-6: match_cues() exercised through the facade (not the CueMatcher
    directly) returns detections for known cues on the exact path."""
    engine = VoiceEngineLab.load()
    _registry.register_strategy(MockTranscriptionStrategy(events_file=FIXTURE))
    events = list(engine.transcribe_file("mock", FIXTURE))

    atoms = [
        CueAtom(
            id="honda.feature.honda_sensing_360plus",
            display_name="Honda Sensing",
            source="feature",
            cue_phrases=["Honda Sensing"],
            synonyms=[],
            metadata={},
        ),
        CueAtom(
            id="honda.feature.wireless_apple_carplay",
            display_name="Wireless Apple CarPlay",
            source="feature",
            cue_phrases=["wireless carplay"],
            synonyms=["CarPlay"],
            metadata={},
        ),
    ]

    detections = list(engine.match_cues(events, atoms, use_semantic=False))
    detected_ids = {d.cue_id for d in detections}
    assert "honda.feature.honda_sensing_360plus" in detected_ids
    assert "honda.feature.wireless_apple_carplay" in detected_ids
