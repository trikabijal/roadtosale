"""Latency percentile computation across detection sets."""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Iterable

from voice_lab.types import CueDetection


@dataclass(frozen=True)
class LatencyStats:
    count: int
    p50_ms: float
    p95_ms: float
    p99_ms: float


def _percentile(sorted_values: list[int], pct: float) -> float:
    if not sorted_values:
        return 0.0
    if len(sorted_values) == 1:
        return float(sorted_values[0])
    # Nearest-rank with linear interpolation.
    k = (len(sorted_values) - 1) * pct
    lo = math.floor(k)
    hi = math.ceil(k)
    if lo == hi:
        return float(sorted_values[int(k)])
    frac = k - lo
    return sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * frac


def latency_percentiles(detections: Iterable[CueDetection]) -> LatencyStats:
    latencies = sorted(
        det.triggering_event.latency_ms_from_audio_start for det in detections
    )
    return LatencyStats(
        count=len(latencies),
        p50_ms=_percentile(latencies, 0.50),
        p95_ms=_percentile(latencies, 0.95),
        p99_ms=_percentile(latencies, 0.99),
    )
