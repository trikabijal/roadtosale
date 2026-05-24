"""Pass/partial/fail/false-positive classification for cue detections.

L1 output schema — one row per expected cue per (strategy × audio file):

  Identity:
    cue_id, script_id, audio_id, noise_level

  Ground truth:
    expected_timestamp_ms, reference_text

  Engine output:
    outcome, matched_phrase, match_method, detection_timestamp_ms,
    delta_ms, confidence, similarity_score

  Investigation evidence (raw — so misses can be understood post-hoc):
    engine_text_window_before   STT finals in [-5s, -1s] around expected timestamp
    engine_text_window_at       STT finals in [-1s, +1s] around expected timestamp
    engine_text_window_after    STT finals in [+1s, +5s] around expected timestamp
    caption_stt_agreement_score Jaccard word overlap: reference_text vs at+around window
                                (only when both are populated)

  Derived (set to empty string — filled in by a human or post-hoc pass):
    miss_type, miss_notes
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Iterable, Literal

from voice_lab.types import CueDetection, TranscriptEvent


CueOutcome = Literal["pass", "partial", "fail", "false_positive"]

TIMESTAMP_TOLERANCE_MS = 2000
CONFIDENCE_FLOOR = 0.5
WINDOW_MS = 5000          # half-window on each side of expected timestamp
AT_RADIUS_MS = 1000       # "at" band: ±1 s from expected timestamp


@dataclass(frozen=True)
class ExpectedCue:
    cue_id: str
    expected_timestamp_ms: int | None = None


@dataclass(frozen=True)
class CueClassification:
    # ── Identity ──────────────────────────────────────────────────────
    cue_id: str
    outcome: CueOutcome
    detection: CueDetection | None
    reason: str
    script_id: str = ""
    audio_id: str = ""
    noise_level: str = "clean"

    # ── Ground truth ──────────────────────────────────────────────────
    expected_timestamp_ms: int | None = None
    reference_text: str = ""          # from script segment text

    # ── Engine output ─────────────────────────────────────────────────
    # (detection fields are on self.detection; delta_ms derived below)

    # ── Investigation evidence ────────────────────────────────────────
    engine_text_window_before: str = ""
    engine_text_window_at: str = ""
    engine_text_window_after: str = ""
    caption_stt_agreement_score: float | None = None

    # ── Derived (filled post-hoc) ─────────────────────────────────────
    miss_type: str = ""    # stt_error | phrase_gap | annotation_error | not_said
    miss_notes: str = ""

    @property
    def delta_ms(self) -> int | None:
        """detection_timestamp_ms − expected_timestamp_ms. None if either is absent."""
        if self.detection is None or self.expected_timestamp_ms is None:
            return None
        return self.detection.timestamp_ms - self.expected_timestamp_ms


# ── Window extraction helpers ──────────────────────────────────────────────

def _finals_in_window(
    events: list[TranscriptEvent],
    center_ms: int,
    lo_ms: int,
    hi_ms: int,
) -> str:
    """Concatenate text of final events whose timestamp_ms falls in [lo_ms, hi_ms]."""
    parts = [
        e.text
        for e in events
        if e.stability == "final"
        and lo_ms <= e.timestamp_ms <= hi_ms
        and e.text.strip()
    ]
    return " ".join(parts)


def _extract_window(
    events: list[TranscriptEvent],
    center_ms: int,
    window_ms: int = WINDOW_MS,
    at_radius_ms: int = AT_RADIUS_MS,
) -> tuple[str, str, str]:
    """Return (before, at, after) text windows around center_ms."""
    before = _finals_in_window(events, center_ms,
                               center_ms - window_ms, center_ms - at_radius_ms)
    at     = _finals_in_window(events, center_ms,
                               center_ms - at_radius_ms, center_ms + at_radius_ms)
    after  = _finals_in_window(events, center_ms,
                               center_ms + at_radius_ms, center_ms + window_ms)
    return before, at, after


def _word_jaccard(text1: str, text2: str) -> float | None:
    """Jaccard similarity on word sets. Returns None if either text is empty."""
    w1 = set(text1.lower().split())
    w2 = set(text2.lower().split())
    if not w1 or not w2:
        return None
    union = w1 | w2
    return len(w1 & w2) / len(union)


# ── Core classifiers ───────────────────────────────────────────────────────

def classify(
    detection: CueDetection | None,
    expected: ExpectedCue,
    *,
    tolerance_ms: int = TIMESTAMP_TOLERANCE_MS,
    confidence_floor: float = CONFIDENCE_FLOOR,
    events: list[TranscriptEvent] | None = None,
    reference_text: str = "",
    script_id: str = "",
    audio_id: str = "",
    noise_level: str = "clean",
) -> CueClassification:
    """Classify one (detection, expected) pair, attaching investigation evidence."""
    # ── Extract window evidence ──────────────────────────────────────────────
    before = at = after = ""
    agreement: float | None = None

    if events and expected.expected_timestamp_ms is not None:
        before, at, after = _extract_window(events, expected.expected_timestamp_ms)
        if reference_text:
            engine_context = " ".join(filter(None, [before, at, after]))
            agreement = _word_jaccard(reference_text, engine_context)

    common = dict(
        script_id=script_id,
        audio_id=audio_id,
        noise_level=noise_level,
        expected_timestamp_ms=expected.expected_timestamp_ms,
        reference_text=reference_text,
        engine_text_window_before=before,
        engine_text_window_at=at,
        engine_text_window_after=after,
        caption_stt_agreement_score=agreement,
    )

    if detection is None:
        return CueClassification(
            cue_id=expected.cue_id,
            outcome="fail",
            detection=None,
            reason="cue did not fire",
            **common,
        )

    if expected.expected_timestamp_ms is not None:
        delta = abs(detection.timestamp_ms - expected.expected_timestamp_ms)
        if delta > tolerance_ms:
            return CueClassification(
                cue_id=expected.cue_id,
                outcome="partial",
                detection=detection,
                reason=f"timestamp drift {delta}ms exceeds {tolerance_ms}ms tolerance",
                **common,
            )

    if detection.confidence is not None and detection.confidence < confidence_floor:
        return CueClassification(
            cue_id=expected.cue_id,
            outcome="partial",
            detection=detection,
            reason=f"confidence {detection.confidence:.2f} below floor {confidence_floor}",
            **common,
        )

    return CueClassification(
        cue_id=expected.cue_id,
        outcome="pass",
        detection=detection,
        reason="on-time firing within tolerance and confidence floor",
        **common,
    )


def classify_run(
    detections: Iterable[CueDetection],
    expected_cues: list[ExpectedCue],
    negative_cues: list[str],
    *,
    events: list[TranscriptEvent] | None = None,
    reference_text: str = "",
    script_id: str = "",
    audio_id: str = "",
    noise_level: str = "clean",
) -> list[CueClassification]:
    """Classify a full set of detections. Returns one classification per expected
    cue (pass/partial/fail) plus one per fired negative cue (false_positive)."""
    detections_list = list(detections)
    first_by_cue: dict[str, CueDetection] = {}
    for det in detections_list:
        first_by_cue.setdefault(det.cue_id, det)

    ctx = dict(
        events=events,
        reference_text=reference_text,
        script_id=script_id,
        audio_id=audio_id,
        noise_level=noise_level,
    )

    results: list[CueClassification] = []
    for exp in expected_cues:
        results.append(classify(first_by_cue.get(exp.cue_id), exp, **ctx))

    negative_set = set(negative_cues)
    for det in detections_list:
        if det.cue_id in negative_set:
            results.append(
                CueClassification(
                    cue_id=det.cue_id,
                    outcome="false_positive",
                    detection=det,
                    reason="negative cue fired",
                    script_id=script_id,
                    audio_id=audio_id,
                    noise_level=noise_level,
                )
            )
    return results
