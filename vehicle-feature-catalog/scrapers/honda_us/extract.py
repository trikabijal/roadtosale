"""Extract structured trim-vs-feature data from Honda brochure PDFs.

Honda brochures share a rough layout: the last several pages are a "Features
& Specifications" matrix. Columns are trims (e.g. LX / Sport / EX-L / Touring);
rows are feature labels. Cells are typically:

* ``S`` or a filled bullet (●) — Standard
* ``O`` / ``A`` or hollow bullet (○) — Optional / Available
* blank or ``—`` — Not available

PDF table extraction is notoriously imperfect — Honda redesigns these tables
yearly and pdfplumber's heuristics struggle with merged headers and bullet
glyphs. This module tries a few strategies in order and returns whatever
structure it can recover. Callers should treat partial extraction as the
common case, not the exception.

Public surface:

    BrochureExtractor.extract(pdf_path) -> ExtractedBrochure
    ExtractedBrochure (.model_hint, .trims, .feature_rows)

A FeatureRow is a label plus a per-trim availability mapping.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Literal

import pdfplumber

log = logging.getLogger(__name__)


Availability = Literal["standard", "optional", "unavailable"]


# Glyphs Honda uses in matrix cells.
STANDARD_GLYPHS = {"●", "■", "◆", "✓", "▪", "S", "s"}
OPTIONAL_GLYPHS = {"○", "□", "◇", "O", "o", "A", "a"}
NEGATIVE_GLYPHS = {"—", "–", "-", "", "N/A", "n/a"}


@dataclass
class FeatureRow:
    label: str
    availability_by_trim: dict[str, Availability] = field(default_factory=dict)


@dataclass
class ExtractedBrochure:
    pdf_path: Path
    model_hint: str | None = None
    trims: list[str] = field(default_factory=list)
    feature_rows: list[FeatureRow] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def cell_count(self) -> int:
        return sum(len(r.availability_by_trim) for r in self.feature_rows)

    def is_useful(self) -> bool:
        return bool(self.trims) and bool(self.feature_rows) and self.cell_count > 0


class BrochureExtractor:
    """Best-effort PDF-to-matrix extractor."""

    def __init__(self, min_trims: int = 2, min_rows: int = 5) -> None:
        self.min_trims = min_trims
        self.min_rows = min_rows

    def extract(self, pdf_path: Path) -> ExtractedBrochure:
        result = ExtractedBrochure(pdf_path=pdf_path)
        try:
            with pdfplumber.open(str(pdf_path)) as pdf:
                pages = list(pdf.pages)
                result.model_hint = self._guess_model_from_metadata(pdf, pages)
                # Try the structured table extraction first across the last
                # 40% of pages (where Honda puts the spec grid).
                tail_start = max(0, int(len(pages) * 0.5))
                tail_pages = pages[tail_start:]
                tables = self._extract_tables(tail_pages)
                if not tables:
                    # Fall back to whole document.
                    tables = self._extract_tables(pages)
                merged = self._merge_matrix_tables(tables)
                if merged is not None:
                    result.trims = merged[0]
                    result.feature_rows = merged[1]
                else:
                    result.warnings.append(
                        "No matrix-shaped table found via pdfplumber.extract_tables; "
                        "falling back to text-line heuristic."
                    )
                    trims, rows = self._extract_from_text(pages)
                    result.trims = trims
                    result.feature_rows = rows
        except Exception as exc:  # noqa: BLE001 — extraction never crashes the run
            log.exception("Extraction failed for %s", pdf_path)
            result.warnings.append(f"pdfplumber raised: {exc}")
        if not result.is_useful():
            result.warnings.append(
                f"Extraction returned trims={len(result.trims)}, "
                f"rows={len(result.feature_rows)}, cells={result.cell_count}. "
                "Manual review recommended."
            )
        return result

    # ------------------------------------------------------------------ private

    def _guess_model_from_metadata(self, pdf: pdfplumber.PDF, pages: list) -> str | None:  # type: ignore[no-untyped-def]
        meta = pdf.metadata or {}
        for key in ("Title", "Subject"):
            val = meta.get(key)
            if val and isinstance(val, str):
                stripped = val.strip()
                if stripped:
                    return stripped
        # Try the first page text — usually the model name lives there.
        if pages:
            try:
                txt = pages[0].extract_text() or ""
                first_lines = [ln.strip() for ln in txt.splitlines() if ln.strip()][:5]
                if first_lines:
                    return first_lines[0]
            except Exception:  # noqa: BLE001
                pass
        return None

    def _extract_tables(self, pages: list) -> list[list[list[str]]]:  # type: ignore[no-untyped-def]
        tables: list[list[list[str]]] = []
        for page in pages:
            try:
                for raw in page.extract_tables() or []:
                    cleaned = self._clean_table(raw)
                    if cleaned:
                        tables.append(cleaned)
            except Exception as exc:  # noqa: BLE001
                log.debug("extract_tables failed on page: %s", exc)
        return tables

    @staticmethod
    def _clean_table(raw: list[list[object]]) -> list[list[str]]:
        cleaned: list[list[str]] = []
        for row in raw:
            cleaned_row = [
                (cell if isinstance(cell, str) else "")
                .replace("\n", " ")
                .strip()
                for cell in row
            ]
            # Drop fully-empty rows.
            if any(c for c in cleaned_row):
                cleaned.append(cleaned_row)
        return cleaned

    def _merge_matrix_tables(
        self, tables: list[list[list[str]]]
    ) -> tuple[list[str], list[FeatureRow]] | None:
        """Pick the first table that looks like a feature matrix and merge
        subsequent compatible tables into it (the matrix often spans pages).
        """
        for idx, table in enumerate(tables):
            header_idx, trim_names = self._detect_header_row(table)
            if header_idx is None or len(trim_names) < self.min_trims:
                continue
            log.info("Matrix candidate at table index %d, trims=%s", idx, trim_names)
            feature_rows: list[FeatureRow] = []
            self._absorb_rows(table[header_idx + 1 :], trim_names, feature_rows)
            # Try to absorb following tables with the same trim count.
            for follow in tables[idx + 1 :]:
                h2_idx, names2 = self._detect_header_row(follow)
                start = (h2_idx + 1) if h2_idx is not None and names2 == trim_names else 0
                self._absorb_rows(follow[start:], trim_names, feature_rows)
            if len(feature_rows) >= self.min_rows:
                return trim_names, feature_rows
        return None

    def _detect_header_row(
        self, table: list[list[str]]
    ) -> tuple[int | None, list[str]]:
        """Find a row whose right-hand cells look like trim names.

        A trim header row has:
          * a (possibly empty) leading cell — the row-label column
          * 2+ short, non-empty, non-glyph cells to the right
        """
        for idx, row in enumerate(table[:6]):
            non_empty = [c for c in row if c]
            if len(non_empty) < self.min_trims + 1:
                continue
            # The cells to the right (trim column headers) should be relatively
            # short text (a trim name like "Sport Touring" is at most ~3 words).
            tail = row[1:]
            tail_non_empty = [c for c in tail if c]
            if not tail_non_empty:
                continue
            if all(self._cell_to_availability(c) is not None for c in tail_non_empty):
                # Whole row is glyphs — not a header.
                continue
            # Trim names are usually 1-4 words, no period.
            looks_like_trims = sum(
                1
                for c in tail_non_empty
                if 1 <= len(c.split()) <= 5 and len(c) <= 40 and "." not in c
            )
            if looks_like_trims >= self.min_trims:
                return idx, tail_non_empty
        return None, []

    def _absorb_rows(
        self,
        rows: list[list[str]],
        trim_names: list[str],
        out: list[FeatureRow],
    ) -> None:
        for row in rows:
            if not row:
                continue
            label = (row[0] or "").strip()
            if not label or self._is_section_header(label):
                # Section header row (no cells in trim columns) is skipped.
                cells = row[1:]
                if not any(self._cell_to_availability(c) for c in cells):
                    continue
                if not label:
                    continue
            cells = row[1:]
            # Normalize cells to length of trim_names by padding with "".
            if len(cells) < len(trim_names):
                cells = cells + [""] * (len(trim_names) - len(cells))
            elif len(cells) > len(trim_names):
                cells = cells[: len(trim_names)]
            availability: dict[str, Availability] = {}
            for trim_name, cell in zip(trim_names, cells):
                avail = self._cell_to_availability(cell)
                if avail is not None:
                    availability[trim_name] = avail
            if availability:
                out.append(FeatureRow(label=self._normalize_label(label), availability_by_trim=availability))

    @staticmethod
    def _normalize_label(label: str) -> str:
        # Collapse whitespace and strip trailing footnote markers.
        label = re.sub(r"\s+", " ", label).strip()
        label = re.sub(r"[\*†‡\d]{1,3}$", "", label).strip()
        return label

    @staticmethod
    def _is_section_header(label: str) -> bool:
        # All caps with no lowercase letters is usually a section header.
        letters = [c for c in label if c.isalpha()]
        if not letters:
            return False
        if all(c.isupper() for c in letters) and len(label) >= 4 and len(label.split()) <= 6:
            return True
        return False

    @staticmethod
    def _cell_to_availability(cell: str) -> Availability | None:
        if cell is None:
            return None
        stripped = cell.strip()
        if not stripped:
            return None
        # Single-glyph cells.
        if stripped in STANDARD_GLYPHS:
            return "standard"
        if stripped in OPTIONAL_GLYPHS:
            return "optional"
        if stripped in NEGATIVE_GLYPHS:
            return "unavailable"
        # Words.
        low = stripped.lower()
        if low in ("standard", "std"):
            return "standard"
        if low in ("optional", "opt", "available", "package"):
            return "optional"
        if low in ("not available", "n/a", "na", "none"):
            return "unavailable"
        return None

    # ------------------------------------------------------------------ text fallback

    def _extract_from_text(self, pages: list) -> tuple[list[str], list[FeatureRow]]:  # type: ignore[no-untyped-def]
        """Very rough fallback when extract_tables produces nothing useful.

        Reads the raw text of the last few pages and looks for lines that end
        with a run of glyphs (one per trim).
        """
        if not pages:
            return [], []
        tail = pages[max(0, len(pages) - 6) :]
        lines: list[str] = []
        for page in tail:
            try:
                txt = page.extract_text() or ""
            except Exception:  # noqa: BLE001
                continue
            for ln in txt.splitlines():
                if ln.strip():
                    lines.append(ln.rstrip())
        # Try to find a header — a line with multiple short uppercase words.
        trim_names: list[str] = []
        for ln in lines:
            tokens = ln.split()
            if 2 <= len(tokens) <= 8 and all(t[:1].isalpha() for t in tokens):
                upper_tokens = [t for t in tokens if t[0].isupper()]
                if len(upper_tokens) >= 3:
                    trim_names = upper_tokens
                    break
        if not trim_names:
            return [], []
        rows: list[FeatureRow] = []
        glyph_re = re.compile(r"([●○■□◆◇✓SOA—–-])")
        for ln in lines:
            glyphs = glyph_re.findall(ln)
            if len(glyphs) >= len(trim_names):
                # Label is everything before the first glyph.
                first = glyph_re.search(ln)
                if not first:
                    continue
                label = ln[: first.start()].strip()
                if not label:
                    continue
                availability: dict[str, Availability] = {}
                for trim, g in zip(trim_names, glyphs[: len(trim_names)]):
                    avail = self._cell_to_availability(g)
                    if avail is not None:
                        availability[trim] = avail
                if availability:
                    rows.append(FeatureRow(label=self._normalize_label(label), availability_by_trim=availability))
        return trim_names, rows
