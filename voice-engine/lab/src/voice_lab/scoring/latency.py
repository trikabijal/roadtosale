"""Latency and engine-performance metric computation.

Two complementary metric families:

LatencyStats  — P50/P95/P99 of wall-clock latency across cue detections.
                "How fast did the system detect cues from audio start?"
                Sourced from detection.triggering_event.latency_ms_from_audio_start.

EngineMetrics — Per-file engine behaviour (TTFT, TTFinal, TTFC, RTF).
                Matches the metric definitions in
                dev/docs/ROAD_TO_SALE_AUDIO_TELEMETRY.md.

                TTFT   — time from audio start to first partial event (ms).
                          Answers: "how quickly does the engine start producing output?"
                TTFinal — time from audio start to first final event (ms).
                          Answers: "when is the first confirmed utterance ready?"
                TTFC   — time from audio start to first cue detection (ms).
                          Answers: "how quickly can the system fire a coaching cue?"
                RTF    — real-time factor = transcription_wall_ms / audio_duration_ms.
                          <1.0 = faster than real-time; >1.0 = slower.
                          Answers: "can this engine keep up with a live speaker?"
                partial_events — count of partial (volatile) events emitted.
                final_events   — count of final (stable) events emitted.
                events_per_sec — (partial + final) / (audio_duration_ms / 1000).
                                  Density of the event stream per second of audio.
"""

from __future__ import annotations

import math
import wave
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from voice_lab.types import CueDetection, TranscriptEvent


# ---------------------------------------------------------------------------
# Detection-latency percentiles  (existing — unchanged interface)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class LatencyStats:
    count: int
    p50_ms: float
    p95_ms: float
    p99_ms: float


def _percentile(sorted_values: list[float], pct: float) -> float:
    if not sorted_values:
        return 0.0
    if len(sorted_values) == 1:
        return float(sorted_values[0])
    k = (len(sorted_values) - 1) * pct
    lo = math.floor(k)
    hi = math.ceil(k)
    if lo == hi:
        return float(sorted_values[int(k)])
    frac = k - lo
    return sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * frac


def latency_percentiles(detections: Iterable[CueDetection]) -> LatencyStats:
    latencies = sorted(
        float(det.triggering_event.latency_ms_from_audio_start) for det in detections
    )
    return LatencyStats(
        count=len(latencies),
        p50_ms=_percentile(latencies, 0.50),
        p95_ms=_percentile(latencies, 0.95),
        p99_ms=_percentile(latencies, 0.99),
    )


# ---------------------------------------------------------------------------
# Engine performance metrics  (new — per file / per strategy run)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class EngineMetrics:
    """Per-file engine behaviour metrics. None means not computable."""
    audio_duration_ms: float
    ttft_ms: float | None        # time to first partial event
    tt_final_ms: float | None    # time to first final event
    ttfc_ms: float | None        # time to first cue detection
    rtf: float | None            # real-time factor (transcription_wall / audio_duration)
    partial_events: int          # count of partial (volatile) events
    final_events: int            # count of final (stable) events
    events_per_sec: float        # (partial + final) / audio_duration_s


def audio_duration_ms(path: Path) -> float:
    """Return WAV duration in milliseconds. Returns 0.0 on error."""
    try:
        with wave.open(str(path), "r") as wf:
            return wf.getnframes() / wf.getframerate() * 1000.0
    except Exception:
        return 0.0


def compute_engine_metrics(
    *,
    events: list[TranscriptEvent],
    detections: list[CueDetection],
    transcription_wall_ms: float,
    audio_path: Path,
) -> EngineMetrics:
    dur_ms = audio_duration_ms(audio_path)

    partials = [e for e in events if e.stability == "partial"]
    finals   = [e for e in events if e.stability == "final"]

    # TTFT — latency of the event that arrived first at the Python side.
    ttft_ms: float | None = None
    if events:
        ttft_ms = float(min(e.latency_ms_from_audio_start for e in events))

    # TTFinal — latency of the first final event to arrive.
    tt_final_ms: float | None = None
    if finals:
        tt_final_ms = float(min(e.latency_ms_from_audio_start for e in finals))

    # TTFC — latency of the first cue detection.
    ttfc_ms: float | None = None
    if detections:
        ttfc_ms = float(min(
            d.triggering_event.latency_ms_from_audio_start for d in detections
        ))

    # RTF — transcription wall time / audio duration.
    rtf: float | None = None
    if dur_ms > 0:
        rtf = transcription_wall_ms / dur_ms

    # Event stream density.
    total_events = len(partials) + len(finals)
    dur_s = dur_ms / 1000.0
    events_per_sec = total_events / dur_s if dur_s > 0 else 0.0

    return EngineMetrics(
        audio_duration_ms=dur_ms,
        ttft_ms=ttft_ms,
        tt_final_ms=tt_final_ms,
        ttfc_ms=ttfc_ms,
        rtf=rtf,
        partial_events=len(partials),
        final_events=len(finals),
        events_per_sec=events_per_sec,
    )


# ---------------------------------------------------------------------------
# Aggregate engine metrics across multiple per-file results
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class AggregateEngineMetrics:
    """Percentile summary of EngineMetrics across N files for one strategy."""
    script_count: int

    ttft_p50_ms: float | None
    ttft_p95_ms: float | None

    tt_final_p50_ms: float | None
    tt_final_p95_ms: float | None

    ttfc_p50_ms: float | None
    ttfc_p95_ms: float | None

    rtf_avg: float | None           # average real-time factor
    rtf_p95: float | None           # worst-case real-time factor (tail)

    avg_partial_events: float
    avg_final_events: float
    avg_events_per_sec: float

    # False-negative and false-positive rates from accuracy scoring.
    false_negative_rate: float | None   # fail_count / total_expected
    false_positive_rate: float | None   # fp_count / total_expected


def aggregate_engine_metrics(
    per_file: list[EngineMetrics],
    *,
    false_negative_rate: float | None = None,
    false_positive_rate: float | None = None,
) -> AggregateEngineMetrics:
    n = len(per_file)
    if n == 0:
        return AggregateEngineMetrics(
            script_count=0,
            ttft_p50_ms=None, ttft_p95_ms=None,
            tt_final_p50_ms=None, tt_final_p95_ms=None,
            ttfc_p50_ms=None, ttfc_p95_ms=None,
            rtf_avg=None, rtf_p95=None,
            avg_partial_events=0.0, avg_final_events=0.0, avg_events_per_sec=0.0,
            false_negative_rate=false_negative_rate,
            false_positive_rate=false_positive_rate,
        )

    def _pct_series(values: list[float], pct: float) -> float | None:
        if not values:
            return None
        return _percentile(sorted(values), pct)

    ttfts  = [m.ttft_ms     for m in per_file if m.ttft_ms     is not None]
    finals = [m.tt_final_ms for m in per_file if m.tt_final_ms is not None]
    ttfcs  = [m.ttfc_ms     for m in per_file if m.ttfc_ms     is not None]
    rtfs   = [m.rtf         for m in per_file if m.rtf         is not None]

    return AggregateEngineMetrics(
        script_count=n,
        ttft_p50_ms=_pct_series(ttfts,  0.50),
        ttft_p95_ms=_pct_series(ttfts,  0.95),
        tt_final_p50_ms=_pct_series(finals, 0.50),
        tt_final_p95_ms=_pct_series(finals, 0.95),
        ttfc_p50_ms=_pct_series(ttfcs,  0.50),
        ttfc_p95_ms=_pct_series(ttfcs,  0.95),
        rtf_avg=sum(rtfs) / len(rtfs) if rtfs else None,
        rtf_p95=_pct_series(rtfs, 0.95),
        avg_partial_events=sum(m.partial_events for m in per_file) / n,
        avg_final_events=sum(m.final_events for m in per_file) / n,
        avg_events_per_sec=sum(m.events_per_sec for m in per_file) / n,
        false_negative_rate=false_negative_rate,
        false_positive_rate=false_positive_rate,
    )
