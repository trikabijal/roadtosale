"""Emit catalog YAML from extracted brochure data.

Responsibilities:

* Resolve each extracted feature label to an existing feature ID under
  ``data/features/universal/`` or ``data/features/honda/``. Match by
  normalized name / display_name / synonyms (case-insensitive, whitespace
  collapsed). When no match exists, create a new Honda-scoped feature file
  with a conservative cue-phrases seed.

* Write one model YAML per Honda model and one trim YAML per trim under
  ``data/models/honda/`` and ``data/trims/honda/<model>/<year>/``.

* APPEND new matrix entries to ``data/matrix/honda.yaml`` — never overwrite
  the existing CR-V Hybrid AWD entries.

All filesystem writes are idempotent and safe to rerun.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Mapping

import yaml

from .extract import ExtractedBrochure, FeatureRow

log = logging.getLogger(__name__)


# Map common brochure section / row labels to *display names* that already
# exist under ``data/features/``. The right-hand side must be looked up by
# normalized name; we do not invent IDs here.
LABEL_TO_DISPLAY_NAME: dict[str, str] = {
    # Connectivity
    "wireless apple carplay": "Wireless Apple CarPlay",
    "apple carplay wireless": "Wireless Apple CarPlay",
    "wireless android auto": "Wireless Android Auto",
    "android auto wireless": "Wireless Android Auto",
    "wireless phone charger": "Wireless Phone Charger",
    "wireless charging": "Wireless Phone Charger",
    # Comfort
    "heated front seats": "Heated Front Seats",
    "heated seats": "Heated Front Seats",
    "heated steering wheel": "Heated Steering Wheel",
    "ventilated front seats": "Ventilated Front Seats",
    "panoramic moonroof": "Panoramic Moonroof",
    "moonroof": "Panoramic Moonroof",
    # Convenience
    "hands free power tailgate": "Hands-Free Power Tailgate",
    "hands-free power tailgate": "Hands-Free Power Tailgate",
    "hands-free access power tailgate": "Hands-Free Power Tailgate",
    "power tailgate": "Hands-Free Power Tailgate",
    # Driver assistance / safety
    "honda sensing": "Honda Sensing",
    "honda sensing 360": "Honda Sensing 360+",
    "honda sensing 360+": "Honda Sensing 360+",
    # Lighting
    "led headlights": "LED Headlights",
    # Audio
    "bose premium audio": "Bose Premium Audio (Honda)",
    "bose premium sound": "Bose Premium Audio (Honda)",
    "bose audio": "Bose Premium Audio (Honda)",
    # HUD
    "head-up display": "Head-Up Display",
    "head up display": "Head-Up Display",
    # Drive
    "real time awd": "Real Time AWD",
    "real-time awd": "Real Time AWD",
    "i-vtm4 awd": "Real Time AWD",  # rough mapping for SUV/truck lineup
    # Telematics
    "hondalink": "HondaLink",
}


@dataclass
class EmitPlan:
    """A dry-run preview of what emit() would write."""

    new_feature_files: list[Path] = field(default_factory=list)
    model_file: Path | None = None
    trim_files: list[Path] = field(default_factory=list)
    matrix_entries_added: int = 0
    unmatched_labels: list[str] = field(default_factory=list)


@dataclass
class EmitResult:
    """The actual side effects of a non-dry-run."""

    new_feature_files: list[Path] = field(default_factory=list)
    model_file: Path | None = None
    trim_files: list[Path] = field(default_factory=list)
    matrix_entries_added: int = 0
    matrix_cells_added: int = 0
    unmatched_labels: list[str] = field(default_factory=list)
    skipped_rows: list[str] = field(default_factory=list)


class CatalogEmitter:
    """Materialize extracted brochure data into the YAML catalog."""

    def __init__(self, data_dir: Path, year: int = 2026) -> None:
        self.data_dir = Path(data_dir)
        self.year = year
        self._features_dir = self.data_dir / "features"
        self._honda_features_dir = self._features_dir / "honda"
        self._universal_features_dir = self._features_dir / "universal"
        self._matrix_path = self.data_dir / "matrix" / "honda.yaml"
        self._known_features: dict[str, dict] = {}
        self._display_to_id: dict[str, str] = {}
        self._reload_known_features()

    # ------------------------------------------------------------------ public

    def emit(
        self,
        model_slug: str,
        model_display_name: str,
        body_style: str | None,
        extracted: ExtractedBrochure,
        dry_run: bool = False,
    ) -> EmitResult:
        result = EmitResult()
        if not extracted.is_useful():
            result.unmatched_labels.append(
                f"<extraction was not useful for {model_slug}: trims={extracted.trims}, "
                f"rows={len(extracted.feature_rows)}>"
            )
            return result

        # 1. Materialize / look up features.
        row_to_feature_id: dict[int, str] = {}
        for i, row in enumerate(extracted.feature_rows):
            fid = self._resolve_or_create_feature(row.label, dry_run=dry_run, result=result)
            if fid is not None:
                row_to_feature_id[i] = fid
            else:
                result.unmatched_labels.append(row.label)

        # 2. Build trim id list and write model + trim YAML.
        clean_trims = [self._clean_trim_name(t) for t in extracted.trims]
        trim_slugs = [self._slugify(t) for t in clean_trims]
        # Drop duplicate trim slugs (some PDFs repeat columns).
        seen: set[str] = set()
        unique_indexed: list[tuple[int, str, str]] = []
        for idx, (display, slug) in enumerate(zip(clean_trims, trim_slugs)):
            if not slug or slug in seen:
                continue
            seen.add(slug)
            unique_indexed.append((idx, display, slug))

        model_id = f"honda.{model_slug}"
        model_file = self.data_dir / "models" / "honda" / f"{model_slug}.yaml"
        model_payload = {
            "id": model_id,
            "make_id": "honda",
            "name": model_display_name,
            "year": self.year,
        }
        if body_style:
            model_payload["body_style"] = body_style

        if not dry_run:
            self._write_yaml(
                model_file,
                model_payload,
                header=f"# Auto-emitted from Honda US {self.year} brochure. Manual review recommended.",
            )
        result.model_file = model_file

        trim_ids_by_extracted_idx: dict[int, str] = {}
        for idx, display, slug in unique_indexed:
            trim_id = f"{model_id}.{self.year}.{slug}"
            trim_ids_by_extracted_idx[idx] = trim_id
            trim_file = (
                self.data_dir
                / "trims"
                / "honda"
                / model_slug
                / str(self.year)
                / f"{slug}.yaml"
            )
            trim_payload = {
                "id": trim_id,
                "model_id": model_id,
                "name": display,
            }
            if not dry_run:
                self._write_yaml(
                    trim_file,
                    trim_payload,
                    header=f"# Auto-emitted from Honda US {self.year} brochure. Manual review recommended.",
                )
            result.trim_files.append(trim_file)

        # 3. Build matrix entries. Group by trim_id so each trim has one entry.
        entries_by_trim: dict[str, list[dict]] = {}
        for row_idx, row in enumerate(extracted.feature_rows):
            fid = row_to_feature_id.get(row_idx)
            if fid is None:
                continue
            for extracted_trim_idx, availability in self._iter_availability(row, extracted.trims):
                trim_id = trim_ids_by_extracted_idx.get(extracted_trim_idx)
                if trim_id is None:
                    continue
                entries_by_trim.setdefault(trim_id, []).append(
                    {"feature_id": fid, "availability": availability}
                )

        # Deduplicate cells per (trim, feature) — keep the strongest availability.
        for trim_id, cells in entries_by_trim.items():
            entries_by_trim[trim_id] = self._dedupe_cells(cells)
            result.matrix_cells_added += len(entries_by_trim[trim_id])

        if not dry_run and entries_by_trim:
            self._append_matrix_entries(entries_by_trim)
        result.matrix_entries_added = len(entries_by_trim)
        return result

    # ------------------------------------------------------------------ internals

    def _reload_known_features(self) -> None:
        self._known_features.clear()
        self._display_to_id.clear()
        for d in (self._universal_features_dir, self._honda_features_dir):
            if not d.exists():
                continue
            for path in sorted(d.glob("*.yaml")):
                try:
                    with path.open("r", encoding="utf-8") as fh:
                        data = yaml.safe_load(fh)
                except Exception as exc:  # noqa: BLE001
                    log.warning("Could not load %s: %s", path, exc)
                    continue
                if not isinstance(data, dict):
                    continue
                fid = data.get("id")
                if not fid:
                    continue
                self._known_features[str(fid)] = data
                display = self._norm(data.get("display_name") or "")
                if display:
                    self._display_to_id.setdefault(display, str(fid))
                for syn in data.get("synonyms") or []:
                    self._display_to_id.setdefault(self._norm(str(syn)), str(fid))
                for cue in data.get("cue_phrases") or []:
                    self._display_to_id.setdefault(self._norm(str(cue)), str(fid))

    def _resolve_or_create_feature(
        self, label: str, dry_run: bool, result: EmitResult
    ) -> str | None:
        if not label or not label.strip():
            return None
        norm = self._norm(label)
        # 1. Direct mapping by curated alias.
        mapped_display = LABEL_TO_DISPLAY_NAME.get(norm)
        if mapped_display is not None:
            fid = self._display_to_id.get(self._norm(mapped_display))
            if fid is not None:
                return fid
        # 2. Exact match against known display/synonym/cue normalized strings.
        if norm in self._display_to_id:
            return self._display_to_id[norm]
        # 3. Substring match — handle "Heated front seats with two settings"
        #    matching "Heated Front Seats".
        for known_norm, fid in self._display_to_id.items():
            if known_norm and (known_norm in norm or norm in known_norm):
                if abs(len(known_norm) - len(norm)) <= max(8, len(known_norm)):
                    return fid

        # 4. Could not match — create a new Honda-scoped feature.
        slug = self._slugify(label)
        if not slug:
            return None
        new_id = f"honda.feature.{slug}"
        if new_id in self._known_features:
            # Already created in a previous call this run.
            return new_id
        target = self._honda_features_dir / f"{slug}.yaml"
        if target.exists():
            # Filename collision: load and adopt its id.
            try:
                with target.open("r", encoding="utf-8") as fh:
                    existing = yaml.safe_load(fh)
                if isinstance(existing, dict) and existing.get("id"):
                    eid = str(existing["id"])
                    self._known_features[eid] = existing
                    self._display_to_id.setdefault(
                        self._norm(existing.get("display_name") or ""), eid
                    )
                    return eid
            except Exception:  # noqa: BLE001
                pass

        display_name = self._title_case(label)
        category = self._guess_category(label)
        payload = {
            "id": new_id,
            "display_name": display_name,
            "category": category,
            "brand_scope": "honda",
            "cue_phrases": [display_name],
            "synonyms": [label.strip()] if label.strip() != display_name else [],
        }
        if not dry_run:
            self._write_yaml(
                target,
                payload,
                header=(
                    "# Auto-emitted from Honda US brochure. cue_phrases is a seed — expand "
                    "with rep-natural variants before relying on voice matching."
                ),
            )
            self._known_features[new_id] = payload
            self._display_to_id[self._norm(display_name)] = new_id
            result.new_feature_files.append(target)
        else:
            result.new_feature_files.append(target)
        return new_id

    def _append_matrix_entries(self, entries_by_trim: Mapping[str, list[dict]]) -> None:
        existing: dict = {"make_id": "honda", "entries": []}
        if self._matrix_path.exists():
            try:
                with self._matrix_path.open("r", encoding="utf-8") as fh:
                    loaded = yaml.safe_load(fh)
                if isinstance(loaded, dict):
                    existing = loaded
                if not isinstance(existing.get("entries"), list):
                    existing["entries"] = []
            except Exception as exc:  # noqa: BLE001
                log.warning("Could not parse %s — starting fresh: %s", self._matrix_path, exc)
        # Build a map of existing entries by trim_id so we can merge instead of duplicate.
        existing_by_trim: dict[str, dict] = {}
        for entry in existing["entries"]:
            if isinstance(entry, dict) and entry.get("trim_id"):
                existing_by_trim[str(entry["trim_id"])] = entry
        for trim_id, cells in entries_by_trim.items():
            if trim_id in existing_by_trim:
                old_cells = existing_by_trim[trim_id].get("features") or []
                merged = self._dedupe_cells(list(old_cells) + cells)
                existing_by_trim[trim_id]["features"] = merged
            else:
                new_entry = {"trim_id": trim_id, "features": cells}
                existing["entries"].append(new_entry)
                existing_by_trim[trim_id] = new_entry

        self._matrix_path.parent.mkdir(parents=True, exist_ok=True)
        header = (
            "# Honda US 2026 trim x feature matrix. Manually-seeded CR-V Hybrid AWD\n"
            "# entries are preserved here alongside scraper-emitted entries; both must\n"
            "# be reviewed against the published brochure before being trusted.\n"
        )
        with self._matrix_path.open("w", encoding="utf-8") as fh:
            fh.write(header)
            yaml.safe_dump(
                existing,
                fh,
                sort_keys=False,
                allow_unicode=True,
                default_flow_style=False,
            )

    @staticmethod
    def _dedupe_cells(cells: Iterable[dict]) -> list[dict]:
        # Stronger availability wins: standard > optional > unavailable.
        rank = {"standard": 2, "optional": 1, "unavailable": 0}
        best: dict[str, dict] = {}
        for cell in cells:
            fid = cell.get("feature_id")
            avail = cell.get("availability")
            if not fid or avail not in rank:
                continue
            prev = best.get(str(fid))
            if prev is None or rank[avail] > rank[prev["availability"]]:
                best[str(fid)] = {"feature_id": str(fid), "availability": avail}
        # Stable order: by feature_id.
        return [best[k] for k in sorted(best)]

    def _iter_availability(
        self, row: FeatureRow, extracted_trims: list[str]
    ) -> list[tuple[int, str]]:
        out: list[tuple[int, str]] = []
        for extracted_idx, trim_name in enumerate(extracted_trims):
            avail = row.availability_by_trim.get(trim_name)
            if avail is None:
                continue
            out.append((extracted_idx, avail))
        return out

    @staticmethod
    def _write_yaml(path: Path, payload: dict, header: str | None = None) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w", encoding="utf-8") as fh:
            if header:
                if not header.endswith("\n"):
                    header += "\n"
                fh.write(header)
            yaml.safe_dump(
                payload,
                fh,
                sort_keys=False,
                allow_unicode=True,
                default_flow_style=False,
            )

    @staticmethod
    def _norm(s: str) -> str:
        s = s.lower()
        s = re.sub(r"[®™]", "", s)  # ®, ™
        s = re.sub(r"[\s\-_/]+", " ", s)
        s = re.sub(r"[^\w\s]", "", s)
        return s.strip()

    @staticmethod
    def _slugify(s: str) -> str:
        s = s.lower()
        s = re.sub(r"[®™]", "", s)
        s = re.sub(r"[^\w\s-]+", "", s)
        s = re.sub(r"[\s_]+", "-", s)
        s = re.sub(r"-+", "-", s).strip("-")
        return s

    @staticmethod
    def _clean_trim_name(s: str) -> str:
        s = re.sub(r"\s+", " ", s).strip()
        s = re.sub(r"[®™]", "", s)
        # Strip drivetrain suffixes Honda sometimes glues onto headers.
        s = re.sub(r"\s+(2WD|FWD|AWD|4WD)$", "", s, flags=re.IGNORECASE).strip()
        return s

    @staticmethod
    def _title_case(s: str) -> str:
        s = re.sub(r"\s+", " ", s).strip()
        # Preserve simple acronyms like AWD, LED, HUD, USB.
        ACRONYMS = {"AWD", "FWD", "LED", "HUD", "USB", "GPS", "AM/FM", "HD", "4WD"}
        out: list[str] = []
        for token in s.split(" "):
            if token.upper() in ACRONYMS:
                out.append(token.upper())
            elif "-" in token:
                out.append("-".join(p.capitalize() for p in token.split("-")))
            else:
                out.append(token.capitalize() if token else token)
        return " ".join(out)

    @staticmethod
    def _guess_category(label: str) -> str:
        low = label.lower()
        if any(w in low for w in ("carplay", "android auto", "bluetooth", "wifi", "wireless charge", "wireless phone", "hondalink")):
            return "connectivity"
        if any(w in low for w in ("sensing", "lane", "collision", "brake", "blind spot", "cross traffic", "adaptive cruise", "airbag", "abs")):
            return "driver_assistance"
        if any(w in low for w in ("audio", "speaker", "bose", "subwoofer", "premium sound", "stereo")):
            return "audio"
        if any(w in low for w in ("heated", "ventilated", "moonroof", "sunroof", "leather", "climate", "tri-zone", "dual-zone", "seat")):
            return "comfort"
        if any(w in low for w in ("headlight", "fog light", "led", "drl", "daytime running")):
            return "lighting"
        if any(w in low for w in ("tailgate", "remote start", "smart entry", "walk-away", "auto-lock", "keyless")):
            return "convenience"
        if any(w in low for w in ("awd", "4wd", "drive mode", "i-vtm", "snow", "sport mode")):
            return "drivetrain"
        return "general"
