"""Cue matcher tests — phrase, synonym, case-insensitive, multi-cue."""

from __future__ import annotations

from voice_lab.matcher.cue_matcher import CueMatcher
from voice_lab.types import CueAtom, TranscriptEvent


def _event(text: str, ts: int = 0) -> TranscriptEvent:
    return TranscriptEvent(
        text=text,
        stability="final",
        timestamp_ms=ts,
        latency_ms_from_audio_start=ts + 50,
        confidence=0.9,
        engine_metadata={},
    )


HONDA_SENSING = CueAtom(
    id="honda.feature.honda_sensing_360plus",
    display_name="Honda Sensing 360+",
    source="feature",
    cue_phrases=["Honda Sensing"],
    synonyms=["safety suite", "driver assist"],
    metadata={},
)

CARPLAY = CueAtom(
    id="honda.feature.wireless_apple_carplay",
    display_name="Wireless Apple CarPlay",
    source="feature",
    cue_phrases=["wireless apple carplay"],
    synonyms=["CarPlay", "apple carplay"],
    metadata={},
)

HOSPITALITY = CueAtom(
    id="workflow.hospitality_offer",
    display_name="Hospitality Offer",
    source="workflow",
    cue_phrases=["can I offer you", "would you like a drink"],
    synonyms=["coffee", "water"],
    metadata={},
)


def test_phrase_match_fires_detection():
    matcher = CueMatcher([HONDA_SENSING])
    detections = list(matcher.match([_event("Let's talk about Honda Sensing today")]))
    assert len(detections) == 1
    assert detections[0].cue_id == "honda.feature.honda_sensing_360plus"
    assert detections[0].matched_phrase == "Honda Sensing"


def test_synonym_match_fires_detection():
    matcher = CueMatcher([CARPLAY])
    detections = list(matcher.match([_event("Pair your phone with CarPlay")]))
    assert len(detections) == 1
    assert detections[0].matched_phrase == "CarPlay"


def test_match_is_case_insensitive():
    matcher = CueMatcher([CARPLAY])
    detections = list(matcher.match([_event("HONDA AND apple CARPLAY rules")]))
    assert len(detections) == 1
    assert detections[0].cue_id == "honda.feature.wireless_apple_carplay"


def test_no_match_emits_nothing():
    matcher = CueMatcher([HONDA_SENSING, CARPLAY])
    detections = list(matcher.match([_event("the weather is nice")]))
    assert detections == []


def test_multiple_cues_in_one_event_each_emit_once():
    matcher = CueMatcher([HONDA_SENSING, CARPLAY, HOSPITALITY])
    detections = list(
        matcher.match(
            [_event("Honda Sensing and CarPlay — can I offer you a coffee?")]
        )
    )
    cue_ids = {d.cue_id for d in detections}
    assert cue_ids == {
        "honda.feature.honda_sensing_360plus",
        "honda.feature.wireless_apple_carplay",
        "workflow.hospitality_offer",
    }
    # Each atom fires exactly once even if multiple of its phrases match.
    assert len(detections) == 3


def test_punctuation_is_normalized():
    matcher = CueMatcher([CARPLAY])
    detections = list(matcher.match([_event("Use, CarPlay!")]))
    assert len(detections) == 1


def test_triggering_event_attached_to_detection():
    matcher = CueMatcher([HONDA_SENSING])
    ev = _event("Honda Sensing", ts=4242)
    detections = list(matcher.match([ev]))
    assert detections[0].triggering_event is ev
    assert detections[0].timestamp_ms == 4242
