"""voice-lab CLI.

Subcommands:
- synth          synthesize fixtures (stub — see OQ3/OQ7)
- run            run a comparison matrix
- validate-catalog   delegates to vehicle-feature-catalog's validator
- ingest-youtube  pull public YouTube dealer walkaround transcripts into the
                  script YAML schema (see voice_lab.ingestion)

This is the only file in the lab that ties the catalog and the engine
together. The orchestrator stays catalog-agnostic.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from voice_lab.facade import VoiceEngineLab
from voice_lab.reporting.csv_writer import write_false_positives_csv, write_results_csv
from voice_lab.reporting.markdown import write_summary


def _cmd_synth(args: argparse.Namespace) -> int:
    import yaml as _yaml
    from voice_lab.synthesis.base import SynthesisRequest
    from voice_lab.synthesis.elevenlabs import ElevenLabsSynthesisProvider, SynthesisError

    lab_root = Path(__file__).resolve().parents[2]
    scripts_dir = Path(args.scripts_dir) if args.scripts_dir else lab_root / "fixtures" / "scripts"
    voices_path = Path(args.voices) if args.voices else lab_root / "cue-packs" / "accent_voices.yaml"
    out_dir = Path(args.output_dir) if args.output_dir else lab_root / "fixtures" / "synthesized"
    out_dir.mkdir(parents=True, exist_ok=True)

    if not voices_path.exists():
        print(f"voices file not found: {voices_path}", file=sys.stderr)
        return 2
    voices = _yaml.safe_load(voices_path.read_text(encoding="utf-8")).get("profiles") or []
    if not voices:
        print(f"no voice profiles in {voices_path}", file=sys.stderr)
        return 2

    script_paths = sorted(p for p in scripts_dir.glob("*.yaml"))
    if not script_paths:
        print(f"no scripts at {scripts_dir}", file=sys.stderr)
        return 2

    provider = ElevenLabsSynthesisProvider()
    n_ok = n_skipped = n_failed = 0
    for sp in script_paths:
        script = _yaml.safe_load(sp.read_text(encoding="utf-8"))
        text = "\n\n".join(seg.get("text", "") for seg in (script.get("segments") or []) if seg.get("text"))
        if not text.strip():
            continue
        for v in voices:
            out_path = out_dir / f"{script['id']}__{v['id']}.wav"
            already = out_path.exists() and out_path.stat().st_size > 0
            try:
                provider.synthesize(SynthesisRequest(
                    script_id=script["id"], text=text, voice_id=v["voice_id"], output_path=out_path,
                ))
            except SynthesisError as exc:
                print(f"[{script['id']} x {v['id']}] FAILED: {exc}", file=sys.stderr)
                n_failed += 1
                continue
            if already:
                n_skipped += 1
                print(f"[{script['id']} x {v['id']}] skipped (exists)")
            else:
                n_ok += 1
                print(f"[{script['id']} x {v['id']}] -> {out_path}")
    print(f"\nsynthesized={n_ok} skipped={n_skipped} failed={n_failed}", file=sys.stderr)
    return 0 if n_failed == 0 else 1


def _try_import_catalog() -> Any | None:
    try:
        from vehicle_feature_catalog import VehicleFeatureCatalog  # type: ignore
        return VehicleFeatureCatalog
    except Exception:
        return None


def _cmd_run(args: argparse.Namespace) -> int:
    import yaml as _yaml
    from voice_lab.orchestrator import LabScript, Orchestrator
    from voice_lab.scoring.classify import ExpectedCue
    from voice_lab.types import CueAtom

    strategy_names: list[str] = [s.strip() for s in args.strategies.split(",") if s.strip()]
    if not strategy_names:
        print("--strategies is required (comma-separated)", file=sys.stderr)
        return 2

    engine = VoiceEngineLab.load()
    available = set(engine.list_strategies())
    for name in strategy_names:
        if name not in available:
            print(f"Unknown strategy '{name}'. Known: {sorted(available)}", file=sys.stderr)
            return 2

    lab_root = Path(__file__).resolve().parents[2]
    scripts_dir = Path(args.scripts_dir) if getattr(args, "scripts_dir", None) else lab_root / "fixtures" / "scripts"
    audio_dir = Path(args.audio_dir) if getattr(args, "audio_dir", None) else lab_root / "fixtures" / "synthesized"
    workflow_cues_path = lab_root / "cue-packs" / "universal_workflow_cues.yaml"

    # Build the cue atom set: workflow cues + every feature cue from the catalog.
    cue_atoms = _load_workflow_cue_atoms(workflow_cues_path)
    catalog_cls = _try_import_catalog()
    if catalog_cls is not None:
        try:
            cat_data_dir = Path(args.data_dir) if args.data_dir else lab_root.parent.parent / "vehicle-feature-catalog" / "data"
            catalog = catalog_cls.load(cat_data_dir)
            for f in catalog.list_features():
                cue_atoms.append(CueAtom(
                    id=f.id, display_name=f.display_name, source="feature",
                    cue_phrases=list(f.cue_phrases), synonyms=list(f.synonyms),
                    metadata={"feature_id": f.id, "category": f.category, "brand_scope": f.brand_scope},
                ))
        except Exception as exc:
            print(f"warning: catalog load failed, running with workflow cues only: {exc}", file=sys.stderr)

    # Build LabScript entries by matching synthesized fixtures back to their source script.
    scripts: list[LabScript] = []
    for script_yaml in sorted(scripts_dir.glob("*.yaml")):
        script = _yaml.safe_load(script_yaml.read_text(encoding="utf-8"))
        sid = script["id"]
        expected: list[ExpectedCue] = []
        for seg in (script.get("segments") or []):
            for ec in (seg.get("expected_cues") or []):
                # v1: timestamp checking disabled. Hand-written approx_ms estimates
                # don't match synthesized audio cadence (varies per voice). Timestamp
                # accuracy is a v2 concern once we have real recorded audio with
                # verified ground truth. For v1, we measure "did the engine catch
                # the phrase" (pass/partial/fail), not "did it catch at the right time".
                expected.append(ExpectedCue(cue_id=ec["cue_id"], expected_timestamp_ms=None))
        negatives = list(script.get("negative_cues") or [])
        for wav in sorted(audio_dir.glob(f"{sid}__*.wav")):
            scripts.append(LabScript(id=f"{sid}::{wav.stem.split('__', 1)[1]}",
                                     audio_path=wav, expected_cues=expected, negative_cues=negatives))

    if not scripts:
        print(f"No (script x synthesized WAV) pairs found. Looked under {audio_dir}.", file=sys.stderr)
        return 2

    print(f"Running {len(strategy_names)} strategies x {len(scripts)} fixtures = "
          f"{len(strategy_names) * len(scripts)} transcriptions against "
          f"{len(cue_atoms)} cue atoms...")

    orch = Orchestrator(engine=engine, cue_atoms=cue_atoms, scripts=scripts, strategy_names=strategy_names)
    run_id = "run-" + datetime.now(timezone.utc).strftime("%Y%m%d-%H%M")
    try:
        run = orch.run(run_id=run_id)
    except Exception as exc:
        print(f"orchestrator failed: {exc}", file=sys.stderr)
        return 1

    out_dir = Path(args.reports_dir) / run_id
    write_summary(out_dir, run_id=run_id,
                  per_strategy=run.classifications_by_strategy,
                  latency_by_strategy=run.latency_by_strategy)
    for name in strategy_names:
        results = [r for r in run.per_script_results if r.strategy_name == name]
        all_cls = [c for r in results for c in r.classifications]
        write_results_csv(out_dir, name, all_cls)
        false_pos = [c for c in all_cls if c.outcome == "false_positive"]
        write_false_positives_csv(out_dir, name, false_pos)
    print(f"report written to {out_dir}/summary.md")
    return 0


def _load_workflow_cue_atoms(path: Path):
    from voice_lab.types import CueAtom
    import yaml as _yaml
    if not path.exists():
        return []
    raw = _yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    out = []
    for c in raw.get("cues", []) or []:
        out.append(CueAtom(
            id=c["id"], display_name=c.get("display_name", c["id"]),
            source="workflow", cue_phrases=list(c.get("cue_phrases", [])),
            synonyms=list(c.get("synonyms", [])),
            metadata={"category": c.get("category", "")},
        ))
    return out

    print(f"Wrote scaffold report to {out_dir}")
    return 0


def _cmd_validate_catalog(args: argparse.Namespace) -> int:
    repo_root = Path(__file__).resolve().parents[4]
    validator = repo_root / "vehicle-feature-catalog" / "scripts" / "validate.py"
    if not validator.exists():
        print(
            f"Catalog validator not found at {validator}. "
            "Has the vehicle-feature-catalog module been created?",
            file=sys.stderr,
        )
        return 2
    cmd = [sys.executable, str(validator), "--data-dir", str(args.data_dir)]
    result = subprocess.run(cmd)
    return result.returncode


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="voice-lab")
    sub = parser.add_subparsers(dest="command", required=True)

    p_synth = sub.add_parser("synth", help="Synthesize audio fixtures (ElevenLabs)")
    p_synth.add_argument("--scripts-dir", default=None,
                         help="Dir of script YAMLs (default: lab/fixtures/scripts)")
    p_synth.add_argument("--voices", default=None,
                         help="Path to accent_voices.yaml (default: lab/cue-packs/accent_voices.yaml)")
    p_synth.add_argument("--output-dir", default=None,
                         help="Where to write synthesized WAVs (default: lab/fixtures/synthesized)")
    p_synth.set_defaults(func=_cmd_synth)

    p_run = sub.add_parser("run", help="Run a strategy comparison matrix")
    p_run.add_argument("--strategies", required=True, help="Comma-separated strategy names")
    p_run.add_argument("--data-dir", default=None, help="Path to vehicle catalog data dir")
    p_run.add_argument("--scripts-dir", default=None, help="Dir of script YAMLs (default: lab/fixtures/scripts)")
    p_run.add_argument("--audio-dir", default=None, help="Dir of synthesized WAVs (default: lab/fixtures/synthesized)")
    p_run.add_argument(
        "--reports-dir",
        default="reports",
        help="Directory under which run-* subfolders are written",
    )
    p_run.set_defaults(func=_cmd_run)

    p_val = sub.add_parser("validate-catalog", help="Validate the vehicle catalog")
    p_val.add_argument("--data-dir", required=True)
    p_val.set_defaults(func=_cmd_validate_catalog)

    # Late import so the youtube-transcript-api dependency is only required
    # when the ingest subcommand is registered (which is always — but the
    # actual transcript fetch is lazy and only fires when the user runs it).
    from voice_lab.ingestion.cli import add_subparser as _add_ingest_subparser
    _add_ingest_subparser(sub)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return int(args.func(args) or 0)


if __name__ == "__main__":
    sys.exit(main())
