"""Command-line entry point for the Honda US brochure scraper.

Usage:
    python -m scrapers.honda_us.cli [--models civic,accord] [--year 2026] [--dry-run]

By default, scrapes all 8 Honda US models still missing from the catalog.
"""

from __future__ import annotations

import argparse
import logging
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

from . import DEFAULT_MODELS, DEFAULT_YEAR
from .discover import BrochureDiscoverer
from .download import BrochureDownloader
from .emit import CatalogEmitter
from .extract import BrochureExtractor
from .extract_hondanews import extract_from_hondanews_html


# Hand-curated display names and body styles per slug, so we can write the
# Model YAML without depending on whatever the brochure cover happens to say.
MODEL_META: dict[str, tuple[str, str]] = {
    "civic": ("Civic", "compact_car"),
    "accord": ("Accord", "midsize_car"),
    "hr-v": ("HR-V", "subcompact_suv"),
    "pilot": ("Pilot", "midsize_suv"),
    "passport": ("Passport", "midsize_suv"),
    "odyssey": ("Odyssey", "minivan"),
    "ridgeline": ("Ridgeline", "midsize_truck"),
    "prologue": ("Prologue", "midsize_ev_suv"),
}


@dataclass
class RunReport:
    discovered: dict[str, str]
    download_paths: dict[str, Path]
    emitted_models: list[str]
    failed_models: dict[str, str]
    total_new_feature_files: int
    total_matrix_cells: int

    def format(self) -> str:
        lines = []
        lines.append("Honda US brochure scraper — run report")
        lines.append("=" * 60)
        lines.append(f"Discovered URLs ({len(self.discovered)}):")
        for slug, url in self.discovered.items():
            lines.append(f"  {slug}: {url}")
        lines.append(f"Downloaded PDFs ({len(self.download_paths)}):")
        for slug, path in self.download_paths.items():
            lines.append(f"  {slug}: {path}")
        lines.append(f"Emitted models ({len(self.emitted_models)}): {', '.join(self.emitted_models) or '<none>'}")
        if self.failed_models:
            lines.append(f"Failed models ({len(self.failed_models)}):")
            for slug, reason in self.failed_models.items():
                lines.append(f"  {slug}: {reason}")
        lines.append(f"New feature files: {self.total_new_feature_files}")
        lines.append(f"Total matrix cells emitted: {self.total_matrix_cells}")
        return "\n".join(lines)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="honda_us_scraper", description=__doc__)
    p.add_argument(
        "--models",
        default=",".join(DEFAULT_MODELS),
        help="Comma-separated model slugs to scrape (default: all 8 missing models).",
    )
    p.add_argument("--year", type=int, default=DEFAULT_YEAR, help="Model year (default: 2026).")
    p.add_argument(
        "--source",
        choices=("brochure-pdf", "hondanews-html"),
        default="brochure-pdf",
        help=(
            "Where the data comes from. 'brochure-pdf' (default) preserves the "
            "original discover-download-extract pipeline against automobiles.honda.com "
            "PDFs. 'hondanews-html' reads operator-saved press-release HTML files "
            "from --hondanews-dir."
        ),
    )
    p.add_argument(
        "--hondanews-dir",
        type=Path,
        default=Path(__file__).resolve().parents[2] / "data-cache" / "hondanews" / "2026",
        help=(
            "Directory containing operator-saved hondanews.com press releases "
            "named <slug>.html. Only used when --source=hondanews-html."
        ),
    )
    p.add_argument(
        "--data-dir",
        type=Path,
        default=Path(__file__).resolve().parents[2] / "data",
        help="Path to the catalog data/ directory.",
    )
    p.add_argument(
        "--cache-dir",
        type=Path,
        default=Path(__file__).resolve().parents[2] / "data-cache",
        help="Path to the brochure cache directory.",
    )
    p.add_argument(
        "--dry-run",
        action="store_true",
        help="Run discover+download+extract, but do not write to data/.",
    )
    p.add_argument(
        "--skip-discover",
        action="store_true",
        help="Skip the discover step. Useful when re-extracting from cached PDFs.",
    )
    p.add_argument(
        "--from-pdf",
        metavar="SLUG=PATH",
        action="append",
        default=[],
        help=(
            "Override discovery+download for a specific slug by pointing to a local PDF. "
            "Repeatable. Example: --from-pdf civic=/tmp/civic.pdf"
        ),
    )
    p.add_argument(
        "--verbose",
        "-v",
        action="store_true",
        help="Enable INFO logging.",
    )
    return p


def _run_hondanews_html(
    args: argparse.Namespace,
    slugs: list[str],
    emitter: CatalogEmitter,
) -> int:
    """Ingest saved hondanews.com press-release HTML files.

    For each requested slug we expect ``<hondanews-dir>/<slug>.html``. Missing
    files are skipped with a clear per-model message; the batch continues.
    """
    hondanews_dir: Path = args.hondanews_dir
    print(f"Source: hondanews-html (reading from {hondanews_dir})")

    if not hondanews_dir.exists():
        print(
            f"No files found: {hondanews_dir} does not exist. "
            f"Save press-release HTML files there as <slug>.html and re-run.",
            file=sys.stderr,
        )
        return 0

    emitted_models: list[str] = []
    failed: dict[str, str] = {}
    download_paths: dict[str, Path] = {}
    total_new_feature_files = 0
    total_matrix_cells = 0

    for slug in slugs:
        html_path = hondanews_dir / f"{slug}.html"
        if not html_path.exists():
            msg = f"hondanews: no HTML at {html_path} (skip)"
            print(f"[{slug}] {msg}")
            failed[slug] = msg
            continue
        download_paths[slug] = html_path
        try:
            extracted = extract_from_hondanews_html(html_path)
        except Exception as exc:  # noqa: BLE001
            failed[slug] = f"extract crashed: {exc}"
            continue
        if not extracted.is_useful():
            failed[slug] = (
                "extract: not useful — "
                + "; ".join(extracted.warnings[-2:] or ["no diagnostic"])
            )
            continue
        display_name, body_style = MODEL_META[slug]
        result = emitter.emit(
            model_slug=slug,
            model_display_name=display_name,
            body_style=body_style,
            extracted=extracted,
            dry_run=args.dry_run,
        )
        if result.model_file is None and not result.matrix_cells_added:
            failed[slug] = "emit produced no output"
            continue
        emitted_models.append(slug)
        total_new_feature_files += len(result.new_feature_files)
        total_matrix_cells += result.matrix_cells_added
        confidence = getattr(extracted, "extraction_confidence", "high")
        print(
            f"[{slug}] trims={len(result.trim_files)}, "
            f"matrix_entries={result.matrix_entries_added}, "
            f"cells={result.matrix_cells_added}, "
            f"new_features={len(result.new_feature_files)}, "
            f"unmatched={len(result.unmatched_labels)}, "
            f"confidence={confidence}"
        )

    if not download_paths:
        print(
            f"No <slug>.html files found in {hondanews_dir} for any of: {slugs}. "
            "Operator should save press-release pages there and re-run."
        )

    report = RunReport(
        discovered={},
        download_paths=download_paths,
        emitted_models=emitted_models,
        failed_models=failed,
        total_new_feature_files=total_new_feature_files,
        total_matrix_cells=total_matrix_cells,
    )
    print()
    print(report.format())
    # Exit 0 even when nothing was emitted -- "no files cached yet" is the
    # documented pre-condition for this source, not a failure.
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    logging.basicConfig(
        level=logging.INFO if args.verbose else logging.WARNING,
        format="%(levelname)s %(name)s: %(message)s",
    )

    slugs = [s.strip() for s in args.models.split(",") if s.strip()]
    unknown = [s for s in slugs if s not in MODEL_META]
    if unknown:
        print(f"Unknown model slug(s): {unknown}. Known: {sorted(MODEL_META)}", file=sys.stderr)
        return 2

    emitter = CatalogEmitter(data_dir=args.data_dir, year=args.year)

    # Branch: hondanews HTML source uses a totally different ingestion path
    # (no network, no PDFs). Hand off and return.
    if args.source == "hondanews-html":
        return _run_hondanews_html(args, slugs, emitter)

    discoverer = BrochureDiscoverer(year=args.year)
    downloader = BrochureDownloader(cache_root=args.cache_dir, year=args.year)
    extractor = BrochureExtractor()

    # Parse --from-pdf overrides up front.
    pdf_overrides: dict[str, Path] = {}
    for entry in args.from_pdf:
        if "=" not in entry:
            print(f"Bad --from-pdf entry {entry!r}: expected slug=path", file=sys.stderr)
            return 2
        slug, path = entry.split("=", 1)
        slug = slug.strip()
        p = Path(path).expanduser().resolve()
        if not p.exists():
            print(f"Bad --from-pdf path: {p} does not exist", file=sys.stderr)
            return 2
        pdf_overrides[slug] = p

    # 1. Discover (or skip and use cached files).
    discovered: dict[str, str] = {}
    failed: dict[str, str] = {}
    if pdf_overrides or args.skip_discover:
        if pdf_overrides:
            print(f"Using {len(pdf_overrides)} local PDF override(s); discovery limited to remaining slugs.")
        if args.skip_discover:
            print("Skipping discovery; using cached PDFs for non-override slugs.")
        remaining = [s for s in slugs if s not in pdf_overrides]
        if remaining and not args.skip_discover:
            disc = discoverer.discover(remaining)
            discovered.update(disc.found)
            failed.update({s: f"discover: {msg}" for s, msg in disc.errors.items()})
    else:
        disc = discoverer.discover(slugs)
        discovered.update(disc.found)
        failed.update({s: f"discover: {msg}" for s, msg in disc.errors.items()})

    # 2. Download (skip for override slugs — already a path).
    download_paths: dict[str, Path] = dict(pdf_overrides)
    if args.skip_discover:
        for s in slugs:
            if s in download_paths:
                continue
            cp = downloader.target_path(s)
            if cp.exists() and cp.stat().st_size > 0:
                download_paths[s] = cp
            else:
                failed.setdefault(s, f"download: no cached PDF at {cp}")
    elif discovered:
        dl = downloader.download_all(discovered)
        download_paths.update(dl.downloaded)
        failed.update({s: f"download: {msg}" for s, msg in dl.errors.items()})

    # 3. Extract + 4. Emit.
    emitted_models: list[str] = []
    total_new_feature_files = 0
    total_matrix_cells = 0
    for slug in slugs:
        if slug not in download_paths:
            if slug not in failed:
                failed[slug] = "no PDF available"
            continue
        try:
            extracted = extractor.extract(download_paths[slug])
        except Exception as exc:  # noqa: BLE001
            failed[slug] = f"extract crashed: {exc}"
            continue
        if not extracted.is_useful():
            failed[slug] = (
                "extract: not useful — "
                + "; ".join(extracted.warnings[-2:] or ["no diagnostic"])
            )
            continue
        display_name, body_style = MODEL_META[slug]
        result = emitter.emit(
            model_slug=slug,
            model_display_name=display_name,
            body_style=body_style,
            extracted=extracted,
            dry_run=args.dry_run,
        )
        if result.model_file is None and not result.matrix_cells_added:
            failed[slug] = "emit produced no output"
            continue
        emitted_models.append(slug)
        total_new_feature_files += len(result.new_feature_files)
        total_matrix_cells += result.matrix_cells_added
        print(
            f"[{slug}] trims={len(result.trim_files)}, "
            f"matrix_entries={result.matrix_entries_added}, "
            f"cells={result.matrix_cells_added}, "
            f"new_features={len(result.new_feature_files)}, "
            f"unmatched={len(result.unmatched_labels)}"
        )

    report = RunReport(
        discovered=discovered,
        download_paths=download_paths,
        emitted_models=emitted_models,
        failed_models=failed,
        total_new_feature_files=total_new_feature_files,
        total_matrix_cells=total_matrix_cells,
    )
    print()
    print(report.format())
    return 0 if emitted_models else 1


if __name__ == "__main__":
    sys.exit(main())
