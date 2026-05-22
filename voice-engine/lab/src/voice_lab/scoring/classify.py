"""Pass/partial/fail/false-positive classification for cue detections."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Literal

from voice_lab.types import CueDetection


CueOutcome = Literal["pass", "partial", "fail", "false_positive"]


# Window within which a cue firing is considered "on time" relative to the
# expected timestamp. Past this window the firing is partial.
TIMESTAMP_TOLERANCE_MS = 2000

# Confidence below this floor downgrades a pass to partial.
CONFIDENCE_FLOOR = 0.5


@dataclass(frozen=True)
class ExpectedCue:
    cue_id: str
    expected_timestamp_ms: int | None = None


@dataclass(frozen=True)
class CueClassification:
    cue_id: str
    outcome: CueOutcome
    detection: CueDetection | None
    reason: str


def classify(
    detection: CueDetection | None,
    expected: ExpectedCue,
    *,
    tolerance_ms: int = TIMESTAMP_TOLERANCE_MS,
    confidence_floor: float = CONFIDENCE_FLOOR,
) -> CueClassification:
    """Classify a single (detection, expected) pair.

    If detection is None, the cue did not fire → fail.
    """
    if detection is None:
        return CueClassification(
            cue_id=expected.cue_id,
            outcome="fail",
            detection=None,
            reason="cue did not fire",
        )

    if expected.expected_timestamp_ms is not None:
        delta = abs(detection.timestamp_ms - expected.expected_timestamp_ms)
        if delta > tolerance_ms:
            return CueClassification(
                cue_id=expected.cue_id,
                outcome="partial",
                detection=detection,
                reason=f"timestamp drift {delta}ms exceeds {tolerance_ms}ms tolerance",
            )

    if detection.confidence is not None and detection.confidence < confidence_floor:
        return CueClassification(
            cue_id=expected.cue_id,
            outcome="partial",
            detection=detection,
            reason=f"confidence {detection.confidence:.2f} below floor {confidence_floor}",
        )

    return CueClassification(
        cue_id=expected.cue_id,
        outcome="pass",
        detection=detection,
        reason="on-time firing within tolerance and confidence floor",
    )


def classify_run(
    detections: Iterable[CueDetection],
    expected_cues: list[ExpectedCue],
    negative_cues: list[str],
) -> list[CueClassification]:
    """Classify a full set of detections against expected and negative cues.

    Returns one classification per expected cue (pass/partial/fail) plus one
    classification per fired negative cue (false_positive). For each expected
    cue, the first detection (in event order) is used.
    """
    detections_list = list(detections)
    first_by_cue: dict[str, CueDetection] = {}
    for det in detections_list:
        first_by_cue.setdefault(det.cue_id, det)

    results: list[CueClassification] = []
    for exp in expected_cues:
        results.append(classify(first_by_cue.get(exp.cue_id), exp))

    negative_set = set(negative_cues)
    for det in detections_list:
        if det.cue_id in negative_set:
            results.append(
                CueClassification(
                    cue_id=det.cue_id,
                    outcome="false_positive",
                    detection=det,
                    reason="negative cue fired",
                )
            )
    return results
