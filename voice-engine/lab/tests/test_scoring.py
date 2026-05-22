"""Scoring tests — classification + latency percentiles."""

from __future__ import annotations

from voice_lab.scoring.classify import (
    ExpectedCue,
    classify,
    classify_run,
)
from voice_lab.scoring.latency import latency_percentiles
from voice_lab.types import CueDetection, TranscriptEvent


def _event(latency: int) -> TranscriptEvent:
    return TranscriptEvent(
        text="x",
        stability="final",
        timestamp_ms=latency,
        latency_ms_from_audio_start=latency,
        confidence=0.9,
        engine_metadata={},
    )


def _detection(cue_id: str, ts: int, confidence: float | None = 0.9) -> CueDetection:
    return CueDetection(
        cue_id=cue_id,
        matched_phrase="x",
        timestamp_ms=ts,
        confidence=confidence,
        triggering_event=_event(ts),
    )


def test_classify_pass_within_tolerance():
    expected = ExpectedCue("a", expected_timestamp_ms=1000)
    result = classify(_detection("a", 1500), expected)
    assert result.outcome == "pass"


def test_classify_partial_when_timestamp_drifts():
    expected = ExpectedCue("a", expected_timestamp_ms=1000)
    result = classify(_detection("a", 4000), expected)
    assert result.outcome == "partial"


def test_classify_partial_when_low_confidence():
    expected = ExpectedCue("a", expected_timestamp_ms=1000)
    result = classify(_detection("a", 1000, confidence=0.2), expected)
    assert result.outcome == "partial"


def test_classify_fail_when_no_detection():
    expected = ExpectedCue("a", expected_timestamp_ms=1000)
    result = classify(None, expected)
    assert result.outcome == "fail"


def test_classify_run_marks_negative_cues_as_false_positive():
    detections = [_detection("good", 1000), _detection("bad", 1200)]
    results = classify_run(
        detections,
        expected_cues=[ExpectedCue("good", expected_timestamp_ms=1000)],
        negative_cues=["bad"],
    )
    outcomes = {(c.cue_id, c.outcome) for c in results}
    assert ("good", "pass") in outcomes
    assert ("bad", "false_positive") in outcomes


def test_latency_percentiles_basic():
    detections = [_detection("c", ts) for ts in [100, 200, 300, 400, 500]]
    stats = latency_percentiles(detections)
    assert stats.count == 5
    assert stats.p50_ms == 300
    assert stats.p95_ms > stats.p50_ms
    assert stats.p99_ms >= stats.p95_ms


def test_latency_percentiles_empty():
    stats = latency_percentiles([])
    assert stats.count == 0
    assert stats.p50_ms == 0.0
