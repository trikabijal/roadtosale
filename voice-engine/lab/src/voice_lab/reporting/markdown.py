"""Markdown report writer."""

from __future__ import annotations

from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Mapping

from voice_lab.scoring.classify import CueClassification
from voice_lab.scoring.latency import LatencyStats


def write_summary(
    out_dir: Path,
    *,
    run_id: str,
    per_strategy: Mapping[str, list[CueClassification]],
    latency_by_strategy: Mapping[str, LatencyStats],
) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    summary_path = out_dir / "summary.md"

    lines: list[str] = []
    lines.append(f"# Voice lab run — {run_id}")
    lines.append("")
    lines.append(f"Generated: `{datetime.now(timezone.utc).isoformat()}`")
    lines.append("")
    lines.append("## Outcomes per strategy")
    lines.append("")
    lines.append("| Strategy | Pass | Partial | Fail | False positive | Total |")
    lines.append("|---|---:|---:|---:|---:|---:|")
    for strategy, classifications in per_strategy.items():
        counts = Counter(c.outcome for c in classifications)
        total = len(classifications)
        lines.append(
            f"| {strategy} | {counts.get('pass', 0)} | {counts.get('partial', 0)} "
            f"| {counts.get('fail', 0)} | {counts.get('false_positive', 0)} | {total} |"
        )
    lines.append("")

    lines.append("## Latency per strategy")
    lines.append("")
    lines.append("| Strategy | Count | P50 (ms) | P95 (ms) | P99 (ms) |")
    lines.append("|---|---:|---:|---:|---:|")
    for strategy, stats in latency_by_strategy.items():
        lines.append(
            f"| {strategy} | {stats.count} | {stats.p50_ms:.0f} | "
            f"{stats.p95_ms:.0f} | {stats.p99_ms:.0f} |"
        )
    lines.append("")

    summary_path.write_text("\n".join(lines), encoding="utf-8")
    return summary_path
