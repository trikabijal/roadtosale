"""CSV report writers — L1 per-cue results and false positives."""

from __future__ import annotations

import csv
from pathlib import Path
from typing import Iterable

from voice_lab.scoring.classify import CueClassification


_RESULT_FIELDS = [
    # ── Identity ────────────────────────────────────────────────────────────
    "cue_id",
    "script_id",
    "audio_id",
    "noise_level",
    # ── Ground truth ────────────────────────────────────────────────────────
    "expected_timestamp_ms",
    "reference_text",
    # ── Engine output ────────────────────────────────────────────────────────
    "outcome",
    "reason",
    "matched_phrase",
    "match_method",
    "detection_timestamp_ms",
    "delta_ms",
    "confidence",
    "similarity_score",
    # ── Investigation evidence ───────────────────────────────────────────────
    "engine_text_window_before",
    "engine_text_window_at",
    "engine_text_window_after",
    "caption_stt_agreement_score",
    # ── Derived (post-hoc) ───────────────────────────────────────────────────
    "miss_type",
    "miss_notes",
]


def write_results_csv(
    out_dir: Path,
    strategy_name: str,
    classifications: Iterable[CueClassification],
) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"{strategy_name}-results.csv"
    with path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=_RESULT_FIELDS)
        writer.writeheader()
        for c in classifications:
            writer.writerow({
                # Identity
                "cue_id": c.cue_id,
                "script_id": c.script_id,
                "audio_id": c.audio_id,
                "noise_level": c.noise_level,
                # Ground truth
                "expected_timestamp_ms": (
                    c.expected_timestamp_ms if c.expected_timestamp_ms is not None else ""
                ),
                "reference_text": c.reference_text,
                # Engine output
                "outcome": c.outcome,
                "reason": c.reason,
                "matched_phrase": c.detection.matched_phrase if c.detection else "",
                "match_method": c.detection.match_method if c.detection else "",
                "detection_timestamp_ms": (
                    c.detection.timestamp_ms if c.detection else ""
                ),
                "delta_ms": c.delta_ms if c.delta_ms is not None else "",
                "confidence": (
                    c.detection.confidence
                    if c.detection and c.detection.confidence is not None
                    else ""
                ),
                "similarity_score": (
                    c.detection.similarity_score
                    if c.detection and c.detection.similarity_score is not None
                    else ""
                ),
                # Investigation evidence
                "engine_text_window_before": c.engine_text_window_before,
                "engine_text_window_at": c.engine_text_window_at,
                "engine_text_window_after": c.engine_text_window_after,
                "caption_stt_agreement_score": (
                    round(c.caption_stt_agreement_score, 4)
                    if c.caption_stt_agreement_score is not None
                    else ""
                ),
                # Derived
                "miss_type": c.miss_type,
                "miss_notes": c.miss_notes,
            })
    return path


_FP_FIELDS = [
    "cue_id",
    "script_id",
    "audio_id",
    "matched_phrase",
    "timestamp_ms",
    "confidence",
    "triggering_text",
]


def write_false_positives_csv(
    out_dir: Path,
    strategy_name: str,
    classifications: Iterable[CueClassification],
) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"{strategy_name}-false-positives.csv"
    with path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=_FP_FIELDS)
        writer.writeheader()
        for c in classifications:
            if c.outcome != "false_positive" or c.detection is None:
                continue
            writer.writerow({
                "cue_id": c.cue_id,
                "script_id": c.script_id,
                "audio_id": c.audio_id,
                "matched_phrase": c.detection.matched_phrase,
                "timestamp_ms": c.detection.timestamp_ms,
                "confidence": (
                    c.detection.confidence
                    if c.detection.confidence is not None
                    else ""
                ),
                "triggering_text": c.detection.triggering_event.text,
            })
    return path
