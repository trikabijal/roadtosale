"""voice-lab CLI.

Subcommands:
  synth              synthesize fixtures (ElevenLabs)
  run                run a comparison matrix
  validate-catalog   delegates to vehicle-feature-catalog validator
  ingest-youtube     pull YouTube transcripts into script YAML schema
  fetch-youtube-audio download YouTube audio as 16 kHz mono WAV

Directory layout (new structure):
  sources/scripts/          hand-written test scripts
  sources/youtube/          YouTube source scripts (auto-generated)
  data/audio/synthesized/{script_id}__{voice}/clean.wav
  data/audio/youtube/{video_id}/clean.wav
  data/transcripts/{strategy}/{audio_id}.jsonl   (transcript cache)
  runs/results/{run_id}/    run outputs
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


def _lab_root() -> Path:
    return Path(__file__).resolve().parents[2]


def _cmd_synth(args: argparse.Namespace) -> int:
    import yaml as _yaml
    from voice_lab.synthesis.base import SynthesisRequest
    from voice_lab.synthesis.elevenlabs import ElevenLabsSynthesisProvider, SynthesisError

    lab_root = _lab_root()
    scripts_dir = Path(args.scripts_dir) if args.scripts_dir else lab_root / "sources" / "scripts"
    voices_path = Path(args.voices) if args.voices else lab_root / "cue-packs" / "accent_voices.yaml"
    out_base = Path(args.output_dir) if args.output_dir else lab_root / "data" / "audio" / "synthesized"

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
        text = "\n\n".join(
            seg.get("text", "") for seg in (script.get("segments") or []) if seg.get("text")
        )
        if not text.strip():
            continue
        for v in voices:
            asset_name = f"{script['id']}__{v['id']}"
            out_dir = out_base / asset_name
            out_dir.mkdir(parents=True, exist_ok=True)
            out_path = out_dir / "clean.wav"
            already = out_path.exists() and out_path.stat().st_size > 0
            try:
                provider.synthesize(SynthesisRequest(
                    script_id=script["id"], text=text,
                    voice_id=v["voice_id"], output_path=out_path,
                ))
            except SynthesisError as exc:
                print(f"[{asset_name}] FAILED: {exc}", file=sys.stderr)
                n_failed += 1
                continue
            if already:
                n_skipped += 1
                print(f"[{asset_name}] skipped (exists)")
            else:
                n_ok += 1
                print(f"[{asset_name}] -> {out_path}")
    print(f"\nsynthesized={n_ok} skipped={n_skipped} failed={n_failed}", file=sys.stderr)
    return 0 if n_failed == 0 else 1


def _try_import_catalog() -> Any | None:
    try:
        from vehicle_feature_catalog import VehicleFeatureCatalog  # type: ignore
        return VehicleFeatureCatalog
    except Exception:
        return None


def _load_workflow_cue_atoms(path: Path) -> list:
    from voice_lab.types import CueAtom
    import yaml as _yaml
    if not path.exists():
        return []
    raw = _yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    return [
        CueAtom(
            id=c["id"], display_name=c.get("display_name", c["id"]),
            source="workflow", cue_phrases=list(c.get("cue_phrases", [])),
            synonyms=list(c.get("synonyms", [])),
            metadata={"category": c.get("category", "")},
        )
        for c in (raw.get("cues") or [])
    ]


def _build_scripts_from_yaml(
    script_yaml_paths: list[Path],
    audio_base: Path,
) -> list:
    """Build LabScript entries from a list of script YAML files.

    Handles two source types:
      - youtube_transcript: audio at audio_base/youtube/{video_id}/clean.wav
      - hand_written / synthesized: audio at audio_base/synthesized/{script_id}__{voice}/clean.wav
    """
    import yaml as _yaml
    from voice_lab.orchestrator import LabScript
    from voice_lab.scoring.classify import ExpectedCue

    scripts = []
    for script_yaml in sorted(script_yaml_paths):
        script = _yaml.safe_load(script_yaml.read_text(encoding="utf-8"))
        if not isinstance(script, dict) or "id" not in script:
            continue

        sid = script["id"]
        source_type = (script.get("source") or {}).get("type", "hand_written")

        # Collect expected cues and reference text from all segments
        expected: list[ExpectedCue] = []
        ref_texts: list[str] = []
        for seg in (script.get("segments") or []):
            seg_text = (seg.get("text") or "").strip()
            if seg_text:
                ref_texts.append(seg_text)
            for ec in (seg.get("expected_cues") or []):
                expected.append(ExpectedCue(
                    cue_id=ec["cue_id"],
                    expected_timestamp_ms=ec.get("approx_ms"),  # keep timestamps
                ))
        reference_text = " ".join(ref_texts)
        negatives = list(script.get("negative_cues") or [])

        if source_type == "youtube_transcript":
            video_id = (script.get("source") or {}).get("video_id") or sid.replace("youtube_", "")
            wav = audio_base / "youtube" / video_id / "clean.wav"
            if not wav.exists():
                print(f"  [skip] audio not found: {wav}", flush=True)
                continue
            audio_id = f"youtube/{video_id}/clean"
            scripts.append(LabScript(
                id=f"youtube::{video_id}",
                audio_path=wav,
                expected_cues=expected,
                negative_cues=negatives,
                audio_id=audio_id,
                script_id=sid,
                noise_level="clean",
                reference_text=reference_text,
            ))
        else:
            # synthesized or hand_written — look for all voice variants
            for wav in sorted((audio_base / "synthesized").glob(f"{sid}__*/clean.wav")):
                voice_name = wav.parent.name.split("__", 1)[1] if "__" in wav.parent.name else wav.parent.name
                asset_name = f"{sid}__{voice_name}"
                audio_id = f"synthesized/{asset_name}/clean"
                scripts.append(LabScript(
                    id=f"{sid}::{voice_name}",
                    audio_path=wav,
                    expected_cues=expected,
                    negative_cues=negatives,
                    audio_id=audio_id,
                    script_id=sid,
                    noise_level="clean",
                    reference_text=reference_text,
                ))

    return scripts


def _cmd_run(args: argparse.Namespace) -> int:
    import yaml as _yaml
    from voice_lab.types import CueAtom
    from voice_lab.orchestrator import Orchestrator

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

    lab_root = _lab_root()
    audio_base = Path(args.audio_base) if getattr(args, "audio_base", None) else lab_root / "data" / "audio"
    transcript_cache_dir = (
        Path(args.transcript_cache) if getattr(args, "transcript_cache", None)
        else lab_root / "data" / "transcripts"
    )

    # Build cue atom set
    workflow_cues_path = lab_root / "cue-packs" / "universal_workflow_cues.yaml"
    cue_atoms = _load_workflow_cue_atoms(workflow_cues_path)
    catalog_cls = _try_import_catalog()
    if catalog_cls is not None:
        try:
            cat_data_dir = (
                Path(args.data_dir) if args.data_dir
                else lab_root.parent.parent / "vehicle-feature-catalog" / "data"
            )
            catalog = catalog_cls.load(cat_data_dir)
            for f in catalog.list_features():
                cue_atoms.append(CueAtom(
                    id=f.id, display_name=f.display_name, source="feature",
                    cue_phrases=list(f.cue_phrases), synonyms=list(f.synonyms),
                    metadata={"feature_id": f.id, "category": f.category,
                              "brand_scope": f.brand_scope},
                ))
        except Exception as exc:
            print(f"warning: catalog load failed, workflow cues only: {exc}", file=sys.stderr)

    # Collect script YAMLs — either from explicit --scripts-dir or from both sources dirs
    if getattr(args, "scripts_dir", None):
        script_yaml_paths = sorted(Path(args.scripts_dir).glob("*.yaml"))
    else:
        # default: all scripts from both sources directories
        script_yaml_paths = (
            sorted((lab_root / "sources" / "scripts").glob("*.yaml"))
            + sorted((lab_root / "sources" / "youtube").glob("youtube_*.yaml"))
        )

    scripts = _build_scripts_from_yaml(script_yaml_paths, audio_base)

    if not scripts:
        print(
            f"No (script × audio) pairs found under {audio_base}.\n"
            "Run `voice-lab synth` for synthesized audio or "
            "`voice-lab fetch-youtube-audio` for YouTube audio.",
            file=sys.stderr,
        )
        return 2

    use_semantic = getattr(args, "semantic", False)
    semantic_threshold = float(getattr(args, "semantic_threshold", 0.65))

    print(
        f"Running {len(strategy_names)} strategies × {len(scripts)} fixtures = "
        f"{len(strategy_names) * len(scripts)} transcriptions "
        f"against {len(cue_atoms)} cue atoms "
        f"(semantic={'yes @'+str(semantic_threshold) if use_semantic else 'no'}) ..."
    )

    orch = Orchestrator(
        engine=engine,
        cue_atoms=cue_atoms,
        scripts=scripts,
        strategy_names=strategy_names,
        use_semantic=use_semantic,
        semantic_threshold=semantic_threshold,
        transcript_cache_dir=transcript_cache_dir,
    )
    run_id = "run-" + datetime.now(timezone.utc).strftime("%Y%m%d-%H%M")
    try:
        run = orch.run(run_id=run_id)
    except Exception as exc:
        print(f"orchestrator failed: {exc}", file=sys.stderr)
        return 1

    out_dir = Path(args.reports_dir) / run_id
    write_summary(
        out_dir, run_id=run_id,
        per_strategy=run.classifications_by_strategy,
        latency_by_strategy=run.latency_by_strategy,
        engine_metrics_by_strategy=run.engine_metrics_by_strategy,
        timing_by_strategy=run.timing_by_strategy,
    )
    for name in strategy_names:
        results = [r for r in run.per_script_results if r.strategy_name == name]
        all_cls = [c for r in results for c in r.classifications]
        write_results_csv(out_dir, name, all_cls)
        false_pos = [c for c in all_cls if c.outcome == "false_positive"]
        write_false_positives_csv(out_dir, name, false_pos)

    cache_hits = sum(
        1 for r in run.per_script_results if r.cache_hit
    )
    cache_misses = len(run.per_script_results) - cache_hits
    print(
        f"report written to {out_dir}/summary.md  "
        f"(cache hits={cache_hits} misses={cache_misses})"
    )
    return 0


def _cmd_validate_catalog(args: argparse.Namespace) -> int:
    repo_root = Path(__file__).resolve().parents[4]
    validator = repo_root / "vehicle-feature-catalog" / "scripts" / "validate.py"
    if not validator.exists():
        print(f"Catalog validator not found at {validator}.", file=sys.stderr)
        return 2
    result = subprocess.run([sys.executable, str(validator), "--data-dir", str(args.data_dir)])
    return result.returncode


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="voice-lab")
    sub = parser.add_subparsers(dest="command", required=True)

    # ── synth ────────────────────────────────────────────────────────────────
    p_synth = sub.add_parser("synth", help="Synthesize audio fixtures (ElevenLabs)")
    p_synth.add_argument("--scripts-dir", default=None)
    p_synth.add_argument("--voices", default=None)
    p_synth.add_argument("--output-dir", default=None)
    p_synth.set_defaults(func=_cmd_synth)

    # ── run ──────────────────────────────────────────────────────────────────
    p_run = sub.add_parser("run", help="Run a strategy comparison matrix")
    p_run.add_argument("--strategies", required=True,
                       help="Comma-separated strategy names")
    p_run.add_argument("--data-dir", default=None,
                       help="Path to vehicle catalog data dir")
    p_run.add_argument("--scripts-dir", default=None,
                       help="Override script YAML directory (default: sources/scripts + sources/youtube)")
    p_run.add_argument("--audio-base", default=None,
                       help="Base dir for audio (default: data/audio/). "
                            "Expects synthesized/ and youtube/ subdirs.")
    p_run.add_argument("--transcript-cache", default=None,
                       help="Transcript cache dir (default: data/transcripts/). "
                            "Set to empty string to disable caching.")
    p_run.add_argument("--reports-dir", default="runs/results",
                       help="Directory under which run-* subfolders are written")
    p_run.add_argument("--semantic", action="store_true", default=False,
                       help="Enable semantic embedding matching")
    p_run.add_argument("--semantic-threshold", type=float, default=0.65,
                       help="Cosine similarity threshold (default: 0.65)")
    p_run.set_defaults(func=_cmd_run)

    # ── validate-catalog ─────────────────────────────────────────────────────
    p_val = sub.add_parser("validate-catalog", help="Validate the vehicle catalog")
    p_val.add_argument("--data-dir", required=True)
    p_val.set_defaults(func=_cmd_validate_catalog)

    # ── ingest-youtube + fetch-youtube-audio (from ingestion.cli) ────────────
    from voice_lab.ingestion.cli import add_subparser as _add_ingest_subparser
    _add_ingest_subparser(sub)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return int(args.func(args) or 0)


if __name__ == "__main__":
    sys.exit(main())
