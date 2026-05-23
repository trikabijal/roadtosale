"""CSV report writers — per-strategy results and false positives."""

from __future__ import annotations

import csv
from pathlib import Path
from typing import Iterable

from voice_lab.scoring.classify import CueClassification


_RESULT_FIELDS = [
    "cue_id",
    "outcome",
    "reason",
    "matched_phrase",
    "detection_timestamp_ms",
    "confidence",
    "match_method",
    "similarity_score",
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
            writer.writerow(
                {
                    "cue_id": c.cue_id,
                    "outcome": c.outcome,
                    "reason": c.reason,
                    "matched_phrase": c.detection.matched_phrase if c.detection else "",
                    "detection_timestamp_ms": (
                        c.detection.timestamp_ms if c.detection else ""
                    ),
                    "confidence": (
                        c.detection.confidence
                        if c.detection and c.detection.confidence is not None
                        else ""
                    ),
                    "match_method": (
                        c.detection.match_method if c.detection else ""
                    ),
                    "similarity_score": (
                        c.detection.similarity_score
                        if c.detection and c.detection.similarity_score is not None
                        else ""
                    ),
                }
            )
    return path


_FP_FIELDS = [
    "cue_id",
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
            writer.writerow(
                {
                    "cue_id": c.cue_id,
                    "matched_phrase": c.detection.matched_phrase,
                    "timestamp_ms": c.detection.timestamp_ms,
                    "confidence": (
                        c.detection.confidence
                        if c.detection.confidence is not None
                        else ""
                    ),
                    "triggering_text": c.detection.triggering_event.text,
                }
            )
    return path
