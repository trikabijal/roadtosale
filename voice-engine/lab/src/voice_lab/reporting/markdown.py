"""Markdown report writer.

Sections emitted (in order):
  1. Outcomes per strategy          — pass / partial / fail / FP counts + FNR / FPR rates
  2. Engine performance metrics     — TTFT / TTFinal / TTFC / RTF / event density
                                      (telemetry doc: ROAD_TO_SALE_AUDIO_TELEMETRY.md)
  3. Detection latency per strategy — P50 / P95 / P99 of wall-clock latency across detections
  4. Timing breakdown per strategy  — transcription / matching / classification wall times
"""

from __future__ import annotations

from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Mapping

from voice_lab.scoring.classify import CueClassification
from voice_lab.scoring.latency import AggregateEngineMetrics, LatencyStats


def _fmt(value: float | None, decimals: int = 0, suffix: str = "") -> str:
    """Format a nullable float for a table cell."""
    if value is None:
        return "—"
    if decimals == 0:
        return f"{value:.0f}{suffix}"
    return f"{value:.{decimals}f}{suffix}"


def _pct(value: float | None) -> str:
    if value is None:
        return "—"
    return f"{value * 100:.1f}%"


def write_summary(
    out_dir: Path,
    *,
    run_id: str,
    per_strategy: Mapping[str, list[CueClassification]],
    latency_by_strategy: Mapping[str, LatencyStats],
    engine_metrics_by_strategy: Mapping[str, AggregateEngineMetrics] | None = None,
    timing_by_strategy: Mapping[str, dict[str, float]] | None = None,
) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    summary_path = out_dir / "summary.md"

    lines: list[str] = []
    lines.append(f"# Voice lab run — {run_id}")
    lines.append("")
    lines.append(f"Generated: `{datetime.now(timezone.utc).isoformat()}`")
    lines.append("")

    # ── 1. Outcomes ──────────────────────────────────────────────────────────
    lines.append("## Outcomes per strategy")
    lines.append("")
    lines.append("| Strategy | Pass | Partial | Fail | False positive | Total | FNR | FPR |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|")
    for strategy, classifications in per_strategy.items():
        counts = Counter(c.outcome for c in classifications)
        total = len(classifications)
        total_expected = counts["pass"] + counts["partial"] + counts["fail"]
        fnr = counts["fail"] / total_expected if total_expected > 0 else None
        fpr = counts["false_positive"] / total_expected if total_expected > 0 else None
        lines.append(
            f"| {strategy}"
            f" | {counts.get('pass', 0)}"
            f" | {counts.get('partial', 0)}"
            f" | {counts.get('fail', 0)}"
            f" | {counts.get('false_positive', 0)}"
            f" | {total}"
            f" | {_pct(fnr)}"
            f" | {_pct(fpr)}"
            f" |"
        )
    lines.append("")

    # ── 2. Engine performance metrics ────────────────────────────────────────
    # TTFT / TTFinal / TTFC / RTF from dev/docs/ROAD_TO_SALE_AUDIO_TELEMETRY.md
    if engine_metrics_by_strategy:
        lines.append("## Engine performance metrics")
        lines.append("")
        lines.append(
            "Thresholds from telemetry doc: "
            "TTFT good <300 ms · TTFC good <500 ms · RTF <1.0 = faster than real-time"
        )
        lines.append("")
        lines.append(
            "| Strategy"
            " | TTFT P50 (ms)"
            " | TTFT P95 (ms)"
            " | TTFinal P50 (ms)"
            " | TTFinal P95 (ms)"
            " | TTFC P50 (ms)"
            " | TTFC P95 (ms)"
            " | RTF avg"
            " | RTF P95"
            " | Partials/file"
            " | Finals/file"
            " | Events/s"
            " |"
        )
        lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
        for strategy, em in engine_metrics_by_strategy.items():
            lines.append(
                f"| {strategy}"
                f" | {_fmt(em.ttft_p50_ms)}"
                f" | {_fmt(em.ttft_p95_ms)}"
                f" | {_fmt(em.tt_final_p50_ms)}"
                f" | {_fmt(em.tt_final_p95_ms)}"
                f" | {_fmt(em.ttfc_p50_ms)}"
                f" | {_fmt(em.ttfc_p95_ms)}"
                f" | {_fmt(em.rtf_avg, 2)}"
                f" | {_fmt(em.rtf_p95, 2)}"
                f" | {_fmt(em.avg_partial_events, 0)}"
                f" | {_fmt(em.avg_final_events, 0)}"
                f" | {_fmt(em.avg_events_per_sec, 1)}"
                f" |"
            )
        lines.append("")

    # ── 3. Detection latency percentiles ─────────────────────────────────────
    lines.append("## Detection latency per strategy")
    lines.append("")
    lines.append("_Wall-clock time from audio start to each cue detection event._")
    lines.append("")
    lines.append("| Strategy | Count | P50 (ms) | P95 (ms) | P99 (ms) |")
    lines.append("|---|---:|---:|---:|---:|")
    for strategy, stats in latency_by_strategy.items():
        lines.append(
            f"| {strategy} | {stats.count} | {stats.p50_ms:.0f} | "
            f"{stats.p95_ms:.0f} | {stats.p99_ms:.0f} |"
        )
    lines.append("")

    # ── 4. Phase timing breakdown ─────────────────────────────────────────────
    if timing_by_strategy:
        lines.append("## Timing breakdown per strategy")
        lines.append("")
        lines.append("_Average wall-clock per phase, per file._")
        lines.append("")
        lines.append(
            "| Strategy | Transcription (ms) | Matching (ms) | Classification (ms) | Total (ms) |"
        )
        lines.append("|---|---:|---:|---:|---:|")
        for strategy, timing in timing_by_strategy.items():
            trans_ms = timing.get("transcription_ms", 0.0)
            match_ms = timing.get("matching_ms", 0.0)
            class_ms = timing.get("classification_ms", 0.0)
            total_ms = trans_ms + match_ms + class_ms
            count = timing.get("count", 0)
            if count > 0:
                lines.append(
                    f"| {strategy}"
                    f" | {trans_ms / count:.1f}"
                    f" | {match_ms / count:.1f}"
                    f" | {class_ms / count:.1f}"
                    f" | {total_ms / count:.1f}"
                    f" |"
                )
            else:
                lines.append(f"| {strategy} | — | — | — | — |")
        lines.append("")

    summary_path.write_text("\n".join(lines), encoding="utf-8")
    return summary_path
