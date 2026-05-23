"""Orchestrator tests — wire mock strategy events through to scored results."""

from __future__ import annotations

from pathlib import Path

from voice_lab.facade import VoiceEngineLab
from voice_lab.orchestrator import LabScript, Orchestrator
from voice_lab.scoring.classify import ExpectedCue
from voice_lab.strategies import registry as _registry
from voice_lab.strategies.mock import MockTranscriptionStrategy
from voice_lab.types import CueAtom


FIXTURE = Path(__file__).parent / "fixtures" / "sample_events.jsonl"


def _setup_engine() -> VoiceEngineLab:
    engine = VoiceEngineLab.load()
    _registry.register_strategy(MockTranscriptionStrategy(events_file=FIXTURE))
    return engine


def test_orchestrator_runs_mock_and_classifies_v1_style():
    # v1: Timestamp checking disabled. Verify that detections with confidence > floor
    # are classified as pass, regardless of timestamp.
    engine = _setup_engine()
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
    scripts = [
        LabScript(
            id="script-1",
            audio_path=FIXTURE,
            expected_cues=[
                ExpectedCue("honda.feature.honda_sensing_360plus", expected_timestamp_ms=None),
                ExpectedCue("honda.feature.wireless_apple_carplay", expected_timestamp_ms=None),
            ],
            negative_cues=[],
        )
    ]
    orch = Orchestrator(engine, atoms, scripts, strategy_names=["mock"])
    result = orch.run(run_id="test-run")

    assert result.run_id == "test-run"
    assert len(result.per_script_results) == 1
    classifications = result.classifications_by_strategy["mock"]
    outcomes = {c.cue_id: c.outcome for c in classifications}
    assert outcomes["honda.feature.honda_sensing_360plus"] == "pass"
    assert outcomes["honda.feature.wireless_apple_carplay"] == "pass"

    stats = result.latency_by_strategy["mock"]
    assert stats.count >= 2


def test_orchestrator_flags_false_positive():
    engine = _setup_engine()
    atoms = [
        CueAtom(
            id="bad_cue",
            display_name="Bad",
            source="workflow",
            cue_phrases=["thank you for visiting"],
            synonyms=[],
            metadata={},
        ),
    ]
    scripts = [
        LabScript(
            id="script-1",
            audio_path=FIXTURE,
            expected_cues=[],
            negative_cues=["bad_cue"],
        )
    ]
    orch = Orchestrator(engine, atoms, scripts, strategy_names=["mock"])
    result = orch.run(run_id="test-fp")
    classifications = result.classifications_by_strategy["mock"]
    assert any(c.outcome == "false_positive" and c.cue_id == "bad_cue" for c in classifications)
