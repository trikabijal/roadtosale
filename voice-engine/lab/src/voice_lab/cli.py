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
    from voice_lab.synthesis.elevenlabs import ElevenLabsSynthesisProvider
    from voice_lab.synthesis.noise import NoiseOverlayProvider

    print("voice-lab synth: invoking ElevenLabs + noise providers...")
    try:
        ElevenLabsSynthesisProvider()
        NoiseOverlayProvider()
        raise NotImplementedError(
            "Synthesis providers are stubs. Resolve OQ3 (ElevenLabs API key + "
            "voice IDs) and OQ7 (noise track) before running synth."
        )
    except NotImplementedError as exc:
        print(f"synth not yet wired: {exc}", file=sys.stderr)
        return 2


def _try_import_catalog() -> Any | None:
    try:
        from vehicle_feature_catalog import VehicleFeatureCatalog  # type: ignore
        return VehicleFeatureCatalog
    except Exception:
        return None


def _cmd_run(args: argparse.Namespace) -> int:
    strategy_names: list[str] = [s.strip() for s in args.strategies.split(",") if s.strip()]
    if not strategy_names:
        print("--strategies is required (comma-separated)", file=sys.stderr)
        return 2

    engine = VoiceEngineLab.load()
    available = set(engine.list_strategies())
    for name in strategy_names:
        if name not in available:
            print(
                f"Unknown strategy '{name}'. Known: {sorted(available)}",
                file=sys.stderr,
            )
            return 2

    # Catalog is best-effort. If unavailable, the run can only use cue atoms
    # supplied via --cue-atoms-file (future enhancement). For v1 with the mock
    # strategy and no atoms, we just confirm wiring and report empty results.
    catalog_cls = _try_import_catalog()
    if catalog_cls is None and args.data_dir is not None:
        print(
            "vehicle_feature_catalog is not importable in this venv. "
            "Run `pip install -e ../vehicle-feature-catalog/src/python` first.",
            file=sys.stderr,
        )
        return 2

    run_id = "run-" + datetime.now(timezone.utc).strftime("%Y%m%d-%H%M")
    out_dir = Path(args.reports_dir) / run_id

    # In v1 the CLI is plumbing only; full script composition lives behind
    # the catalog + cue-pack files that another agent is wiring. For now,
    # the CLI confirms strategy availability and writes an empty report.
    write_summary(
        out_dir,
        run_id=run_id,
        per_strategy={name: [] for name in strategy_names},
        latency_by_strategy={},
    )
    for name in strategy_names:
        write_results_csv(out_dir, name, [])
        write_false_positives_csv(out_dir, name, [])

    # Touch each strategy so non-mock stubs surface their friendly errors.
    for name in strategy_names:
        if name == "mock":
            continue
        strategy = engine.get_strategy(name)
        try:
            list(strategy.transcribe(Path("/dev/null")))
        except NotImplementedError as exc:
            print(f"[{name}] not implemented: {exc}", file=sys.stderr)
        except Exception as exc:
            print(f"[{name}] error: {exc}", file=sys.stderr)

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

    p_synth = sub.add_parser("synth", help="Synthesize audio fixtures")
    p_synth.set_defaults(func=_cmd_synth)

    p_run = sub.add_parser("run", help="Run a strategy comparison matrix")
    p_run.add_argument("--strategies", required=True, help="Comma-separated strategy names")
    p_run.add_argument("--data-dir", default=None, help="Path to vehicle catalog data dir")
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
