"""Lab orchestrator — runs a comparison matrix over (script x strategy).

The orchestrator is parameter-driven. It takes the engine, the cue atom
set, the scripts to run, and the strategy names to compare. It does NOT
import the vehicle catalog — the CLI is the place where engine + catalog
meet.

A "script" here is a minimal record: an id, an audio file path, the cues
expected to fire (with optional expected timestamps), and the cues that
must NOT fire (negative cues). The orchestrator iterates strategy x script,
runs transcription + matching + classification, and returns results
suitable for the reporting layer.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Mapping

from voice_lab.facade import VoiceEngineLab
from voice_lab.scoring.classify import (
    CueClassification,
    ExpectedCue,
    classify_run,
)
from voice_lab.scoring.latency import LatencyStats, latency_percentiles
from voice_lab.types import CueAtom, CueDetection


@dataclass(frozen=True)
class LabScript:
    id: str
    audio_path: Path
    expected_cues: list[ExpectedCue]
    negative_cues: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class StrategyScriptResult:
    strategy_name: str
    script_id: str
    detections: list[CueDetection]
    classifications: list[CueClassification]


@dataclass(frozen=True)
class OrchestratorRun:
    run_id: str
    started_at: str
    per_script_results: list[StrategyScriptResult]
    classifications_by_strategy: Mapping[str, list[CueClassification]]
    latency_by_strategy: Mapping[str, LatencyStats]


class Orchestrator:
    def __init__(
        self,
        engine: VoiceEngineLab,
        cue_atoms: list[CueAtom],
        scripts: list[LabScript],
        strategy_names: list[str],
    ) -> None:
        self._engine = engine
        self._cue_atoms = cue_atoms
        self._scripts = scripts
        self._strategy_names = strategy_names

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

        for strategy_name in self._strategy_names:
            for script in self._scripts:
                events: Iterable = self._engine.transcribe_file(
                    strategy_name, script.audio_path
                )
                detections = list(
                    self._engine.match_cues(list(events), self._cue_atoms)
                )
                classifications = classify_run(
                    detections, script.expected_cues, script.negative_cues
                )
                per_script_results.append(
                    StrategyScriptResult(
                        strategy_name=strategy_name,
                        script_id=script.id,
                        detections=detections,
                        classifications=classifications,
                    )
                )
                classifications_by_strategy[strategy_name].extend(classifications)
                detections_by_strategy[strategy_name].extend(detections)

        latency_by_strategy: dict[str, LatencyStats] = {
            name: latency_percentiles(dets)
            for name, dets in detections_by_strategy.items()
        }

        return OrchestratorRun(
            run_id=run_id,
            started_at=started_at,
            per_script_results=per_script_results,
            classifications_by_strategy=classifications_by_strategy,
            latency_by_strategy=latency_by_strategy,
        )
