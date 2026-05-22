"""Ingestion CLI — `voice-lab ingest-youtube`.

Reads a manifest of public Honda dealer walkaround videos, fetches each
transcript, builds a script YAML, and writes it under
`fixtures/scripts/youtube/<script-id>.yaml`.

Idempotent. Re-runs skip videos whose output already exists unless
`--force` is supplied.

The manifest schema is:

    sources:
      - video_id: <11-char YouTube id>
        title: <human readable>
        url: https://www.youtube.com/watch?v=<id>
        target_trim_id: <catalog trim id>      # required
        notes: <optional>
        fair_use_note: <optional, used as source.fair_use_note in output>

The CLI does NOT validate target_trim_id against the catalog — that is
the orchestrator's job. We pass the trim id through unchanged so a human
can flag placeholder trims in the manifest and fix them later.
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import yaml

from voice_lab.ingestion.script_builder import (
    BuiltScript,
    build_script,
    load_cue_index,
)
from voice_lab.ingestion.youtube import (
    TranscriptFetchError,
    TranscriptSegment,
    TranscriptUnavailableError,
    fetch_transcript,
)

# Default location of the universal workflow cue pack relative to the lab root.
DEFAULT_WORKFLOW_CUES = Path("cue-packs") / "universal_workflow_cues.yaml"
DEFAULT_OUTPUT_DIR = Path("fixtures") / "scripts" / "youtube"
DEFAULT_FEATURE_CATALOG = Path("../../vehicle-feature-catalog/data/features")
DEFAULT_FAIR_USE_NOTE = (
    "Public dealer walkaround used for internal STT evaluation under fair use."
)


@dataclass(frozen=True)
class ManifestSource:
    video_id: str
    title: str
    url: str
    target_trim_id: str
    notes: str | None
    fair_use_note: str


@dataclass(frozen=True)
class IngestResult:
    video_id: str
    status: str  # "ingested" | "skipped" | "no_transcript" | "fetch_error"
    output_path: Path | None
    detail: str | None
    segments_written: int


def _resolve_lab_root() -> Path:
    """Return the lab root (the directory containing `cue-packs/`)."""
    # src/voice_lab/ingestion/cli.py -> lab root is parents[3]
    return Path(__file__).resolve().parents[3]


def _load_manifest(path: Path) -> list[ManifestSource]:
    with path.open("r", encoding="utf-8") as f:
        doc = yaml.safe_load(f) or {}
    raw_sources = doc.get("sources") or []
    out: list[ManifestSource] = []
    for entry in raw_sources:
        video_id = str(entry.get("video_id") or "").strip()
        target_trim_id = str(entry.get("target_trim_id") or "").strip()
        if not video_id or not target_trim_id:
            raise ValueError(
                f"Manifest entry missing video_id or target_trim_id: {entry!r}"
            )
        out.append(
            ManifestSource(
                video_id=video_id,
                title=str(entry.get("title") or f"YouTube transcript {video_id}"),
                url=str(entry.get("url") or f"https://www.youtube.com/watch?v={video_id}"),
                target_trim_id=target_trim_id,
                notes=entry.get("notes"),
                fair_use_note=str(entry.get("fair_use_note") or DEFAULT_FAIR_USE_NOTE),
            )
        )
    return out


def _discover_feature_paths(catalog_root: Path) -> list[Path]:
    """Find all feature YAMLs under the catalog. Best-effort — missing dir is OK."""
    if not catalog_root.exists():
        return []
    paths: list[Path] = []
    for sub in catalog_root.iterdir():
        if sub.is_dir():
            paths.extend(sorted(sub.glob("*.yaml")))
    return paths


def _make_script_id(video_id: str) -> str:
    safe = "".join(c if c.isalnum() else "_" for c in video_id)
    return f"youtube_{safe}"


def _build_source_block(source: ManifestSource, fetched_at: str) -> dict[str, Any]:
    return {
        "type": "youtube_transcript",
        "url": source.url,
        "video_id": source.video_id,
        "fetched_at": fetched_at,
        "fair_use_note": source.fair_use_note,
    }


def _write_script_yaml(out_path: Path, script: BuiltScript) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    header = (
        "# Auto-generated from a public YouTube dealer walkaround transcript.\n"
        "# Step boundaries and cue annotations are best-effort — review before\n"
        "# relying on them for scoring.\n"
    )
    body = yaml.safe_dump(
        script.to_yaml_dict(),
        sort_keys=False,
        allow_unicode=True,
        width=100,
    )
    out_path.write_text(header + body, encoding="utf-8")


def ingest_one(
    source: ManifestSource,
    *,
    cue_index: list,
    output_dir: Path,
    force: bool,
    transcript_fetcher: Any = fetch_transcript,
) -> IngestResult:
    """Fetch + build + write one source. Pure-ish; transcript_fetcher is a seam."""
    script_id = _make_script_id(source.video_id)
    out_path = output_dir / f"{script_id}.yaml"

    if out_path.exists() and not force:
        return IngestResult(
            video_id=source.video_id,
            status="skipped",
            output_path=out_path,
            detail="output exists; use --force to overwrite",
            segments_written=0,
        )

    try:
        segments: list[TranscriptSegment] = transcript_fetcher(source.video_id)
    except TranscriptUnavailableError as exc:
        return IngestResult(
            video_id=source.video_id,
            status="no_transcript",
            output_path=None,
            detail=str(exc),
            segments_written=0,
        )
    except TranscriptFetchError as exc:
        return IngestResult(
            video_id=source.video_id,
            status="fetch_error",
            output_path=None,
            detail=str(exc),
            segments_written=0,
        )

    fetched_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    built = build_script(
        transcript=segments,
        cue_index=cue_index,
        script_id=script_id,
        target_trim_id=source.target_trim_id,
        title=source.title,
        source=_build_source_block(source, fetched_at),
    )
    _write_script_yaml(out_path, built)

    return IngestResult(
        video_id=source.video_id,
        status="ingested",
        output_path=out_path,
        detail=None,
        segments_written=len(built.segments),
    )


def run_ingest_youtube(
    *,
    manifest_path: Path,
    output_dir: Path,
    workflow_cues_path: Path,
    feature_catalog_root: Path,
    force: bool,
    transcript_fetcher: Any = fetch_transcript,
) -> list[IngestResult]:
    """High-level entry point. Useful for tests."""
    if not manifest_path.exists():
        raise FileNotFoundError(f"Manifest not found: {manifest_path}")
    if not workflow_cues_path.exists():
        raise FileNotFoundError(f"Workflow cues not found: {workflow_cues_path}")

    sources = _load_manifest(manifest_path)
    feature_paths = _discover_feature_paths(feature_catalog_root)
    cue_index = load_cue_index(workflow_cues_path, feature_paths)

    results: list[IngestResult] = []
    for src in sources:
        result = ingest_one(
            src,
            cue_index=cue_index,
            output_dir=output_dir,
            force=force,
            transcript_fetcher=transcript_fetcher,
        )
        results.append(result)
    return results


def _format_summary(results: list[IngestResult]) -> str:
    counts: dict[str, int] = {}
    for r in results:
        counts[r.status] = counts.get(r.status, 0) + 1
    parts = [f"{k}={v}" for k, v in sorted(counts.items())]
    return "ingest summary: " + ", ".join(parts) if parts else "ingest summary: (no sources)"


def cmd_ingest_youtube(args: argparse.Namespace) -> int:
    lab_root = _resolve_lab_root()
    manifest_path = Path(args.manifest).resolve()
    output_dir = Path(args.output_dir).resolve() if args.output_dir else (
        lab_root / DEFAULT_OUTPUT_DIR
    )
    workflow_cues_path = Path(args.workflow_cues).resolve() if args.workflow_cues else (
        lab_root / DEFAULT_WORKFLOW_CUES
    )
    feature_catalog_root = Path(args.feature_catalog).resolve() if args.feature_catalog else (
        lab_root / DEFAULT_FEATURE_CATALOG
    )

    try:
        results = run_ingest_youtube(
            manifest_path=manifest_path,
            output_dir=output_dir,
            workflow_cues_path=workflow_cues_path,
            feature_catalog_root=feature_catalog_root,
            force=bool(args.force),
        )
    except FileNotFoundError as exc:
        print(f"ingest-youtube: {exc}", file=sys.stderr)
        return 2

    for r in results:
        if r.status == "ingested":
            print(
                f"[ingested] {r.video_id} -> {r.output_path} "
                f"({r.segments_written} segments)"
            )
        elif r.status == "skipped":
            print(f"[skipped]  {r.video_id} -> {r.output_path} ({r.detail})")
        elif r.status == "no_transcript":
            print(f"[no_transcript] {r.video_id}: {r.detail}", file=sys.stderr)
        elif r.status == "fetch_error":
            print(f"[fetch_error]   {r.video_id}: {r.detail}", file=sys.stderr)

    print(_format_summary(results))

    # Exit 0 if anything was ingested or everything was already cached.
    # Exit 1 only if we attempted everything and got zero usable output.
    usable = sum(
        1 for r in results if r.status in ("ingested", "skipped")
    )
    return 0 if usable > 0 or not results else 1


def add_subparser(subparsers: argparse._SubParsersAction) -> argparse.ArgumentParser:
    p = subparsers.add_parser(
        "ingest-youtube",
        help="Pull YouTube transcripts into the script YAML schema",
    )
    p.add_argument(
        "--manifest",
        required=True,
        help="Path to sources.yaml manifest listing video_id + target_trim_id",
    )
    p.add_argument(
        "--output-dir",
        default=None,
        help="Output dir for generated scripts (default: lab/fixtures/scripts/youtube)",
    )
    p.add_argument(
        "--workflow-cues",
        default=None,
        help="Path to universal_workflow_cues.yaml "
        "(default: lab/cue-packs/universal_workflow_cues.yaml)",
    )
    p.add_argument(
        "--feature-catalog",
        default=None,
        help="Path to vehicle-feature-catalog/data/features (default: ../../vehicle-feature-catalog/data/features)",
    )
    p.add_argument(
        "--force",
        action="store_true",
        help="Overwrite outputs that already exist",
    )
    p.set_defaults(func=cmd_ingest_youtube)
    return p
