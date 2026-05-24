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
    lines.append(
        "| Strategy | Detected | Not said | FNR | FPR"
        " | via Exact | via Semantic | Semantic lift |"
    )
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|")
    for strategy, classifications in per_strategy.items():
        counts = Counter(c.outcome for c in classifications)
        total_expected = counts["pass"] + counts["partial"] + counts["fail"]
        detected = counts["pass"] + counts["partial"]
        not_said = counts["fail"]
        fnr = not_said / total_expected if total_expected > 0 else None
        fpr = counts["false_positive"] / total_expected if total_expected > 0 else None

        # Split detected cues by match method.
        exact_hits = sum(
            1 for c in classifications
            if c.outcome in ("pass", "partial")
            and c.detection is not None
            and c.detection.match_method == "exact"
        )
        semantic_hits = sum(
            1 for c in classifications
            if c.outcome in ("pass", "partial")
            and c.detection is not None
            and c.detection.match_method == "semantic"
        )
        semantic_lift = (
            f"+{semantic_hits} ({semantic_hits/detected*100:.0f}%)"
            if detected > 0 and semantic_hits > 0
            else ("—" if semantic_hits == 0 else f"+{semantic_hits}")
        )
        lines.append(
            f"| {strategy}"
            f" | {detected}"
            f" | {not_said}"
            f" | {_pct(fnr)}"
            f" | {_pct(fpr)}"
            f" | {exact_hits}"
            f" | {semantic_hits}"
            f" | {semantic_lift}"
            f" |"
        )
    lines.append("")
    lines.append(
        "_**Not said** = cue was expected but dealer never said it — coaching finding, not an engine error._"
        " _**Semantic lift** = extra cues caught only by the embedding layer._"
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


# Canonical noise-level order: clean → snr15db → snr5db → snr0db (best to worst SNR)
_NOISE_ORDER: list[str] = ["clean", "snr15db", "snr5db", "snr0db"]
_NOISE_LABELS: dict[str, str] = {
    "clean": "Clean",
    "snr15db": "SNR +15 dB\n(quiet room)",
    "snr5db": "SNR +5 dB\n(showroom)",
    "snr0db": "SNR 0 dB\n(very noisy)",
}


def write_noise_comparison(
    out_dir: Path,
    *,
    run_id: str,
    per_strategy: Mapping[str, list[CueClassification]],
) -> Path | None:
    """Write a noise degradation comparison report.

    For each strategy × noise_level, emits FNR, FPR, pass/fail counts, and
    the delta vs clean baseline.

    Returns None if there is only one noise level in the data (nothing to compare).
    """
    from collections import defaultdict

    # Gather all noise levels present in the data.
    all_noise_levels: set[str] = set()
    for cls_list in per_strategy.values():
        for c in cls_list:
            all_noise_levels.add(c.noise_level)

    # Order according to canonical list, put unknowns at end.
    ordered_levels = [lv for lv in _NOISE_ORDER if lv in all_noise_levels]
    extra = sorted(all_noise_levels - set(ordered_levels))
    ordered_levels.extend(extra)

    if len(ordered_levels) <= 1:
        return None  # nothing to compare

    out_dir.mkdir(parents=True, exist_ok=True)
    report_path = out_dir / "noise_comparison.md"

    lines: list[str] = []
    lines.append(f"# Noise robustness comparison — {run_id}")
    lines.append("")
    lines.append(f"Generated: `{datetime.now(timezone.utc).isoformat()}`")
    lines.append("")
    lines.append(
        "White Gaussian noise mixed at target SNR. "
        "FNR = false negative rate (expected cues missed). "
        "FPR = false positive rate (false alarms). "
        "Δ = delta vs clean baseline (negative = better)."
    )
    lines.append("")

    for strategy, cls_list in per_strategy.items():
        lines.append(f"## {strategy}")
        lines.append("")

        # Group by noise level.
        by_noise: dict[str, list[CueClassification]] = defaultdict(list)
        for c in cls_list:
            by_noise[c.noise_level].append(c)

        # Build metrics table.
        lines.append(
            "| Noise level | Pass | Fail | FP | Total expected | FNR | ΔFNR | FPR | ΔFPR |"
        )
        lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|")

        baseline_fnr: float | None = None
        baseline_fpr: float | None = None

        for noise_level in ordered_levels:
            if noise_level not in by_noise:
                continue
            group = by_noise[noise_level]
            counts = Counter(c.outcome for c in group)
            total_expected = counts["pass"] + counts["partial"] + counts["fail"]
            n_pass = counts["pass"] + counts["partial"]
            n_fail = counts["fail"]
            n_fp = counts["false_positive"]
            fnr = n_fail / total_expected if total_expected > 0 else None
            fpr = n_fp / total_expected if total_expected > 0 else None

            if noise_level == "clean":
                baseline_fnr = fnr
                baseline_fpr = fpr
                delta_fnr_str = "—"
                delta_fpr_str = "—"
            else:
                if baseline_fnr is not None and fnr is not None:
                    delta = fnr - baseline_fnr
                    sign = "+" if delta >= 0 else ""
                    delta_fnr_str = f"{sign}{delta * 100:.1f}pp"
                else:
                    delta_fnr_str = "—"
                if baseline_fpr is not None and fpr is not None:
                    delta = fpr - baseline_fpr
                    sign = "+" if delta >= 0 else ""
                    delta_fpr_str = f"{sign}{delta * 100:.1f}pp"
                else:
                    delta_fpr_str = "—"

            label = noise_level.replace("snr", "SNR +").replace("db", " dB") if noise_level != "clean" else "clean"
            lines.append(
                f"| {label}"
                f" | {n_pass}"
                f" | {n_fail}"
                f" | {n_fp}"
                f" | {total_expected}"
                f" | {_pct(fnr)}"
                f" | {delta_fnr_str}"
                f" | {_pct(fpr)}"
                f" | {delta_fpr_str}"
                f" |"
            )

        lines.append("")

        # Cue-level breakdown: which cues degrade most?
        lines.append("### Cue-level FNR by noise level")
        lines.append("")
        # Collect all cue_ids
        all_cue_ids = sorted({c.cue_id for c in cls_list})
        if all_cue_ids:
            header = "| Cue ID |" + "".join(f" {lv} |" for lv in ordered_levels if lv in by_noise)
            sep = "|---|" + "".join("---:|" for lv in ordered_levels if lv in by_noise)
            lines.append(header)
            lines.append(sep)
            for cue_id in all_cue_ids:
                row = f"| `{cue_id}` |"
                for noise_level in ordered_levels:
                    if noise_level not in by_noise:
                        continue
                    cue_rows = [c for c in by_noise[noise_level] if c.cue_id == cue_id]
                    if not cue_rows:
                        row += " — |"
                        continue
                    n_total = len(cue_rows)
                    n_fail = sum(1 for c in cue_rows if c.outcome == "fail")
                    fnr = n_fail / n_total if n_total > 0 else None
                    row += f" {_pct(fnr)} |"
                lines.append(row)
            lines.append("")

    report_path.write_text("\n".join(lines), encoding="utf-8")
    return report_path
