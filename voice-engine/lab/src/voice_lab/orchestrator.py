"""Lab orchestrator — runs a comparison matrix over (script x strategy).

Two-step architecture:
  Step 1 (transcribe): run STT subprocess → raw events → saved to transcript
    cache at data/transcripts/{strategy}/{audio_id}.jsonl.
    Cache hit = skip STT entirely; load from disk.

  Step 2 (match + classify): load cached events → cue matching → L1 CSV rows.
    Can be re-run without touching the STT engine.

L1 output fields: see voice_lab.scoring.classify.CueClassification.
"""

from __future__ import annotations

import json
import logging
import time
from collections import Counter
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Mapping

logger = logging.getLogger(__name__)

from voice_lab.facade import VoiceEngineLab
from voice_lab.scoring.classify import (
    CueClassification,
    ExpectedCue,
    classify_run,
)


def _make_error_classification(
    cue_id: str,
    error: str,
    *,
    script_id: str = "",
    audio_id: str = "",
    noise_level: str = "clean",
) -> CueClassification:
    return CueClassification(
        cue_id=cue_id,
        outcome="fail",
        detection=None,
        reason=f"transcription error: {error[:120]}",
        script_id=script_id,
        audio_id=audio_id,
        noise_level=noise_level,
    )


from voice_lab.scoring.latency import (
    AggregateEngineMetrics,
    EngineMetrics,
    LatencyStats,
    aggregate_engine_metrics,
    compute_engine_metrics,
    latency_percentiles,
)
from voice_lab.types import CueAtom, CueDetection, TranscriptEvent


# ── Transcript cache helpers ───────────────────────────────────────────────

def _cache_path(cache_dir: Path, strategy: str, audio_id: str) -> Path:
    """canonical: cache_dir/{strategy}/{audio_id}.jsonl"""
    return cache_dir / strategy / (audio_id + ".jsonl")


def _save_transcript(path: Path, events: list[TranscriptEvent]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for e in events:
            f.write(json.dumps(asdict(e)) + "\n")
    logger.debug("transcript cache written: %s (%d events)", path, len(events))


def _load_transcript(path: Path) -> list[TranscriptEvent]:
    events: list[TranscriptEvent] = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            events.append(TranscriptEvent(
                text=d["text"],
                stability=d["stability"],
                timestamp_ms=d["timestamp_ms"],
                latency_ms_from_audio_start=d["latency_ms_from_audio_start"],
                confidence=d.get("confidence"),
                engine_metadata=d.get("engine_metadata", {}),
            ))
    logger.debug("transcript cache loaded: %s (%d events)", path, len(events))
    return events


# ── Data types ─────────────────────────────────────────────────────────────

@dataclass(frozen=True)
class LabScript:
    id: str
    audio_path: Path
    expected_cues: list[ExpectedCue]
    negative_cues: list[str] = field(default_factory=list)
    # New context fields — populated by CLI, used in L1 output
    audio_id: str = ""          # canonical: "youtube/2FXQvvp9Blw/clean"
    script_id: str = ""         # source YAML id: "script_001_crv_hybrid_walkaround"
    noise_level: str = "clean"
    reference_text: str = ""    # concatenated segment text (ground-truth source)


@dataclass(frozen=True)
class StrategyScriptResult:
    strategy_name: str
    script_id: str
    detections: list[CueDetection]
    classifications: list[CueClassification]
    engine_metrics: EngineMetrics
    transcription_time_ms: float = 0.0
    matching_time_ms: float = 0.0
    classification_time_ms: float = 0.0
    cache_hit: bool = False      # True when transcript was loaded from cache


@dataclass(frozen=True)
class OrchestratorRun:
    run_id: str
    started_at: str
    per_script_results: list[StrategyScriptResult]
    classifications_by_strategy: Mapping[str, list[CueClassification]]
    latency_by_strategy: Mapping[str, LatencyStats]
    engine_metrics_by_strategy: Mapping[str, AggregateEngineMetrics]
    timing_by_strategy: Mapping[str, dict[str, float]] = field(default_factory=dict)


# ── Orchestrator ───────────────────────────────────────────────────────────

class Orchestrator:
    def __init__(
        self,
        engine: VoiceEngineLab,
        cue_atoms: list[CueAtom],
        scripts: list[LabScript],
        strategy_names: list[str],
        *,
        use_semantic: bool = False,
        semantic_threshold: float = 0.65,
        transcript_cache_dir: Path | None = None,
    ) -> None:
        self._engine = engine
        self._cue_atoms = cue_atoms
        self._scripts = scripts
        self._strategy_names = strategy_names
        self._use_semantic = use_semantic
        self._semantic_threshold = semantic_threshold
        self._cache_dir = transcript_cache_dir

    def _get_events(
        self,
        strategy_name: str,
        script: LabScript,
    ) -> tuple[list[TranscriptEvent], bool]:
        """Return (events, cache_hit). Saves to cache after STT if cache_dir set."""
        if self._cache_dir and script.audio_id:
            cp = _cache_path(self._cache_dir, strategy_name, script.audio_id)
            if cp.exists():
                logger.info("cache HIT  %s / %s", strategy_name, script.audio_id)
                print(f"  [cache hit] {strategy_name} / {script.audio_id}", flush=True)
                return _load_transcript(cp), True

        logger.info("cache MISS %s / %s — running STT", strategy_name, script.audio_id or script.id)
        events = list(self._engine.transcribe_file(strategy_name, script.audio_path))

        if self._cache_dir and script.audio_id:
            cp = _cache_path(self._cache_dir, strategy_name, script.audio_id)
            _save_transcript(cp, events)

        return events, False

    def _run_one(
        self,
        *,
        strategy_name: str,
        script: LabScript,
        per_script_results: list,
        classifications_by_strategy: dict,
        detections_by_strategy: dict,
        engine_metrics_per_file: dict,
        timing_by_strategy: dict,
    ) -> None:
        # ── 1. Transcription (or cache load) ────────────────────────────────
        t0 = time.perf_counter()
        events_list, cache_hit = self._get_events(strategy_name, script)
        transcription_ms = (time.perf_counter() - t0) * 1000

        # ── 2. Cue matching ─────────────────────────────────────────────────
        t0 = time.perf_counter()
        detections = list(
            self._engine.match_cues(
                events_list,
                self._cue_atoms,
                use_semantic=self._use_semantic,
                semantic_threshold=self._semantic_threshold,
            )
        )
        matching_ms = (time.perf_counter() - t0) * 1000

        # ── 3. Classification — with full investigation evidence ─────────────
        t0 = time.perf_counter()
        classifications = classify_run(
            detections,
            script.expected_cues,
            script.negative_cues,
            events=events_list,
            reference_text=script.reference_text,
            script_id=script.script_id,
            audio_id=script.audio_id,
            noise_level=script.noise_level,
        )
        classification_ms = (time.perf_counter() - t0) * 1000

        # ── 4. Engine metrics ────────────────────────────────────────────────
        em = compute_engine_metrics(
            events=events_list,
            detections=detections,
            transcription_wall_ms=transcription_ms,
            audio_path=script.audio_path,
        )

        per_script_results.append(
            StrategyScriptResult(
                strategy_name=strategy_name,
                script_id=script.id,
                detections=detections,
                classifications=classifications,
                engine_metrics=em,
                transcription_time_ms=transcription_ms,
                matching_time_ms=matching_ms,
                classification_time_ms=classification_ms,
                cache_hit=cache_hit,
            )
        )
        classifications_by_strategy[strategy_name].extend(classifications)
        detections_by_strategy[strategy_name].extend(detections)
        engine_metrics_per_file[strategy_name].append(em)

        timing_by_strategy[strategy_name]["transcription_ms"] += transcription_ms
        timing_by_strategy[strategy_name]["matching_ms"] += matching_ms
        timing_by_strategy[strategy_name]["classification_ms"] += classification_ms
        timing_by_strategy[strategy_name]["count"] += 1

    def run(self, run_id: str | None = None) -> OrchestratorRun:
        now = datetime.now(timezone.utc)
        started_at = now.isoformat()
        if run_id is None:
            run_id = "run-" + now.strftime("%Y%m%d-%H%M")

        per_script_results: list[StrategyScriptResult] = []
        classifications_by_strategy: dict[str, list[CueClassification]] = {
            name: [] for name in self._strategy_names
        }
        detections_by_strategy: dict[str, list[CueDetection]] = {
            name: [] for name in self._strategy_names
        }
        engine_metrics_per_file: dict[str, list[EngineMetrics]] = {
            name: [] for name in self._strategy_names
        }
        timing_by_strategy: dict[str, dict[str, float]] = {
            name: {
                "transcription_ms": 0.0,
                "matching_ms": 0.0,
                "classification_ms": 0.0,
                "count": 0,
            }
            for name in self._strategy_names
        }

        for strategy_name in self._strategy_names:
            for script in self._scripts:
                try:
                    self._run_one(
                        strategy_name=strategy_name,
                        script=script,
                        per_script_results=per_script_results,
                        classifications_by_strategy=classifications_by_strategy,
                        detections_by_strategy=detections_by_strategy,
                        engine_metrics_per_file=engine_metrics_per_file,
                        timing_by_strategy=timing_by_strategy,
                    )
                except Exception as exc:
                    logger.warning("SKIPPED %s / %s: %s", strategy_name, script.id, exc)
                    print(f"  [SKIP] {strategy_name} / {script.id}: {exc}", flush=True)
                    fail_cls = [
                        _make_error_classification(
                            ec.cue_id, str(exc),
                            script_id=script.script_id,
                            audio_id=script.audio_id,
                            noise_level=script.noise_level,
                        )
                        for ec in script.expected_cues
                    ]
                    classifications_by_strategy[strategy_name].extend(fail_cls)

        latency_by_strategy: dict[str, LatencyStats] = {
            name: latency_percentiles(dets)
            for name, dets in detections_by_strategy.items()
        }

        engine_metrics_by_strategy: dict[str, AggregateEngineMetrics] = {}
        for name in self._strategy_names:
            cls_list = classifications_by_strategy[name]
            counts = Counter(c.outcome for c in cls_list)
            total_expected = counts["pass"] + counts["partial"] + counts["fail"]
            fnr = counts["fail"] / total_expected if total_expected > 0 else None
            fpr = counts["false_positive"] / total_expected if total_expected > 0 else None
            engine_metrics_by_strategy[name] = aggregate_engine_metrics(
                engine_metrics_per_file[name],
                false_negative_rate=fnr,
                false_positive_rate=fpr,
            )

        return OrchestratorRun(
            run_id=run_id,
            started_at=started_at,
            per_script_results=per_script_results,
            classifications_by_strategy=classifications_by_strategy,
            latency_by_strategy=latency_by_strategy,
            engine_metrics_by_strategy=engine_metrics_by_strategy,
            timing_by_strategy=timing_by_strategy,
        )
