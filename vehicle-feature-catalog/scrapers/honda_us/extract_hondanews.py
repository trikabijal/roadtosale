"""Extract structured trim-vs-feature data from hondanews.com press releases.

Hondanews is fronted by Akamai Bot Manager and 403s most automated requests.
The operator therefore saves each press release as HTML from their browser
("File -> Save Page As -> Web Page, Complete" or "...Source") into

    data-cache/hondanews/2026/<slug>.html

and this module consumes those saved files. We deliberately do not fetch
anything over the network here -- the discovery step lives elsewhere.

What hondanews press releases contain (per Honda's standard format):

* Section headings: "Trim Levels", "Powertrain", "Dimensions", "Features",
  "Standard Equipment", etc.
* A trim x feature comparison table (most useful) or, more often, a series
  of "Standard Equipment" lists grouped by trim.
* Honda-branded feature names (Honda Sensing, Real-Time AWD, Wireless Apple
  CarPlay, etc.) we already cover in the alias table reused from emit.py.

The exact HTML structure varies year over year and across browsers' "save
page" implementations (Chrome, Firefox, and Safari each emit slightly
different DOMs but all preserve the press-release article body). The
extractor therefore relies on structural heuristics rather than CSS classes:

1. Trim list discovery:
   * heading text containing "Trim Levels", "Available Trims", "Trims"
     -> next sibling list or table
   * fallback: first table whose header row reads as 2+ short trim-like
     tokens

2. Feature discovery:
   * tables with trim columns and feature rows -> matrix cells
   * "Standard Equipment", "Features" headings followed by <ul> lists
     scoped to a single trim -> per-trim "standard" availability
   * "Available" / "Optional" headings -> "optional" availability

3. Cell interpretation: filled / hollow bullet glyphs, check marks, S/O
   letters, and the literal words "standard" / "optional" / "n/a".

When the structure is ambiguous (no matrix table, no per-trim feature
lists), we extract any "feature : trim(s)" pairs we can find from the
article text and tag the result with ``extraction_confidence='low'`` so
the emit step can surface a manual-review flag on the matrix entry.

Public surface:

    extract_from_hondanews_html(html_path) -> ExtractedHondanewsModel
        Drop-in compatible with emit.py's expectations -- it is a subclass
        of ExtractedBrochure with one extra ``extraction_confidence`` field.

    ExtractedModel
        Alias of ExtractedHondanewsModel, matching the name used in the
        scraper spec.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

from bs4 import BeautifulSoup, Tag

from .extract import (
    NEGATIVE_GLYPHS,
    OPTIONAL_GLYPHS,
    STANDARD_GLYPHS,
    Availability,
    ExtractedBrochure,
    FeatureRow,
)

log = logging.getLogger(__name__)


ExtractionConfidence = Literal["high", "medium", "low"]


@dataclass
class ExtractedHondanewsModel(ExtractedBrochure):
    """Hondanews-flavoured extraction result.

    Adds ``extraction_confidence`` to the base ExtractedBrochure so the emit
    step can flag low-confidence rows in the YAML output. Emit reads this
    via ``getattr(...)`` so legacy ExtractedBrochure instances still work.
    """

    extraction_confidence: ExtractionConfidence = "high"


# Alias to match the name used in the scraper spec / docs.
ExtractedModel = ExtractedHondanewsModel


# Tag-name sets we look at when walking the DOM.
_HEADING_TAGS = ("h1", "h2", "h3", "h4", "h5", "h6")
_TRIM_HEADING_PATTERNS = (
    re.compile(r"\btrim\s*levels?\b", re.IGNORECASE),
    re.compile(r"\bavailable\s+trims?\b", re.IGNORECASE),
    re.compile(r"\btrims?\b", re.IGNORECASE),
    re.compile(r"\bmodel\s+lineup\b", re.IGNORECASE),
)
_FEATURE_HEADING_PATTERNS = (
    re.compile(r"\bstandard\s+(equipment|features?)\b", re.IGNORECASE),
    re.compile(r"\bfeatures?\b", re.IGNORECASE),
    re.compile(r"\bequipment\b", re.IGNORECASE),
)
_OPTIONAL_HEADING_PATTERNS = (
    re.compile(r"\boptional\s+(equipment|features?)\b", re.IGNORECASE),
    re.compile(r"\bavailable\s+(equipment|features?|options?)\b", re.IGNORECASE),
)

# Honda's standard 2026 model word list -- we use this only as a tie-breaker
# when guessing the model name from the document title.
_MODEL_NAME_HINTS = (
    "Accord", "Civic", "HR-V", "Pilot", "Passport", "Odyssey", "Ridgeline",
    "Prologue", "CR-V", "Insight", "Fit",
)


def extract_from_hondanews_html(html_path: Path) -> ExtractedHondanewsModel:
    """Parse a saved-page HTML file from hondanews.com.

    Returns an ExtractedHondanewsModel that is drop-in compatible with the
    existing emit step (ExtractedBrochure subclass).
    """
    result = ExtractedHondanewsModel(pdf_path=html_path)
    try:
        raw = html_path.read_text(encoding="utf-8", errors="ignore")
    except FileNotFoundError:
        result.warnings.append(f"file not found: {html_path}")
        result.extraction_confidence = "low"
        return result
    except OSError as exc:
        result.warnings.append(f"could not read {html_path}: {exc}")
        result.extraction_confidence = "low"
        return result

    soup = BeautifulSoup(raw, "html.parser")
    article = _find_article_root(soup)

    result.model_hint = _guess_model_hint(soup)

    # Strategy 1: trim x feature comparison table.
    trims_t, rows_t = _extract_from_comparison_table(article)
    # Strategy 2: per-trim "Standard Equipment" lists.
    trims_l, rows_l = _extract_from_per_trim_lists(article)

    # Pick whichever strategy returned more cells; merge feature rows by
    # label when both contribute (uncommon but possible).
    cells_t = sum(len(r.availability_by_trim) for r in rows_t)
    cells_l = sum(len(r.availability_by_trim) for r in rows_l)
    if cells_t >= cells_l and trims_t:
        result.trims = trims_t
        result.feature_rows = rows_t
        result.extraction_confidence = "high" if cells_t >= 5 else "medium"
    elif trims_l:
        result.trims = trims_l
        result.feature_rows = rows_l
        result.extraction_confidence = "medium" if cells_l >= 5 else "low"
    else:
        # Fallback path: salvage what we can from free text.
        trims_f, rows_f = _extract_fallback(article)
        if trims_f and rows_f:
            result.trims = trims_f
            result.feature_rows = rows_f
        result.extraction_confidence = "low"
        result.warnings.append(
            "Neither a comparison table nor per-trim feature lists were "
            "found. Falling back to free-text scan. Manual review required."
        )

    # If we have a comparison table but the cell count is small, still
    # mark medium/low.
    if not result.feature_rows:
        result.warnings.append(
            "No feature rows recovered. Press release may use a layout "
            "this extractor does not yet understand."
        )
        result.extraction_confidence = "low"

    return result


# --------------------------------------------------------------------------- helpers


def _find_article_root(soup: BeautifulSoup) -> Tag:
    """Pick the most likely container for the press release body.

    Falls back to ``<body>`` (and ultimately the whole soup) when nothing
    looks article-shaped. We never raise here -- a missing article tag is
    common in browser-saved HTML.
    """
    for selector in ("article", "main", "div.press-release", "div.article-body"):
        node = soup.select_one(selector)
        if node is not None:
            return node
    body = soup.find("body")
    if isinstance(body, Tag):
        return body
    # soup itself behaves like a Tag for find_all purposes.
    return soup


def _guess_model_hint(soup: BeautifulSoup) -> str | None:
    """Best-effort guess at the model name from <title> or first <h1>."""
    title = soup.find("title")
    candidates: list[str] = []
    if isinstance(title, Tag) and title.string:
        candidates.append(title.string.strip())
    h1 = soup.find("h1")
    if isinstance(h1, Tag):
        text = h1.get_text(" ", strip=True)
        if text:
            candidates.append(text)
    for cand in candidates:
        for hint in _MODEL_NAME_HINTS:
            if re.search(rf"\b{re.escape(hint)}\b", cand):
                return cand
    return candidates[0] if candidates else None


# --------------------------------------------------------------------------- comparison table


def _extract_from_comparison_table(
    root: Tag,
) -> tuple[list[str], list[FeatureRow]]:
    """Find the first <table> that looks like a feature matrix and parse it."""
    for table in root.find_all("table"):
        if not isinstance(table, Tag):
            continue
        trims, rows = _parse_table_as_matrix(table)
        if trims and rows:
            return trims, rows
    return [], []


def _parse_table_as_matrix(table: Tag) -> tuple[list[str], list[FeatureRow]]:
    rows = _table_rows_as_text(table)
    if len(rows) < 2:
        return [], []

    header_idx, trims = _detect_table_header(rows)
    if header_idx is None or not trims:
        return [], []

    feature_rows: list[FeatureRow] = []
    for raw_row in rows[header_idx + 1 :]:
        if not raw_row:
            continue
        label = raw_row[0].strip()
        if not label:
            continue
        if _is_section_header_text(label):
            continue
        cells = raw_row[1:]
        if len(cells) < len(trims):
            cells = cells + [""] * (len(trims) - len(cells))
        elif len(cells) > len(trims):
            cells = cells[: len(trims)]
        availability_by_trim: dict[str, Availability] = {}
        for trim_name, cell in zip(trims, cells):
            avail = _cell_text_to_availability(cell)
            if avail is not None:
                availability_by_trim[trim_name] = avail
        if availability_by_trim:
            feature_rows.append(
                FeatureRow(
                    label=_normalize_label(label),
                    availability_by_trim=availability_by_trim,
                )
            )
    return trims, feature_rows


def _table_rows_as_text(table: Tag) -> list[list[str]]:
    """Render the table as a list-of-lists of stripped cell text."""
    out: list[list[str]] = []
    for tr in table.find_all("tr"):
        if not isinstance(tr, Tag):
            continue
        cells: list[str] = []
        for cell in tr.find_all(("th", "td")):
            if not isinstance(cell, Tag):
                continue
            text = cell.get_text(" ", strip=True)
            cells.append(text)
        if any(c for c in cells):
            out.append(cells)
    return out


def _detect_table_header(
    rows: list[list[str]],
) -> tuple[int | None, list[str]]:
    """Identify the row whose right-hand cells read as trim names.

    Looks at the first 4 rows -- press releases occasionally float a title
    cell before the actual header row.
    """
    for idx, row in enumerate(rows[:4]):
        tail = [c for c in row[1:] if c]
        if len(tail) < 2:
            continue
        # If the whole tail is just glyphs / availability words, this is a
        # data row, not a header.
        if all(_cell_text_to_availability(c) is not None for c in tail):
            continue
        looks_like_trims = sum(
            1
            for c in tail
            if 1 <= len(c.split()) <= 5 and len(c) <= 40 and "." not in c
        )
        if looks_like_trims >= 2:
            return idx, tail
    return None, []


# --------------------------------------------------------------------------- per-trim lists


def _extract_from_per_trim_lists(
    root: Tag,
) -> tuple[list[str], list[FeatureRow]]:
    """Walk the article: each "<TrimName> Standard Equipment" heading is
    followed by a <ul> whose <li>s are features marked "standard" for that
    trim. "Optional Equipment" headings under a trim mark "optional".
    """
    trim_list = _extract_trim_list_from_heading(root)
    # Collect: trim_name -> {feature_label -> availability}
    per_trim: dict[str, dict[str, Availability]] = {}
    current_trim: str | None = None
    current_avail: Availability = "standard"

    for el in root.descendants:
        if not isinstance(el, Tag):
            continue
        if el.name in _HEADING_TAGS:
            text = el.get_text(" ", strip=True)
            if not text:
                continue
            trim_match = _heading_names_trim(text, trim_list)
            if trim_match is not None:
                current_trim = trim_match
                # Reset availability to standard unless the heading itself
                # says "Optional".
                if any(p.search(text) for p in _OPTIONAL_HEADING_PATTERNS):
                    current_avail = "optional"
                else:
                    current_avail = "standard"
                per_trim.setdefault(current_trim, {})
                continue
            # Heading without a trim name -- maybe a sub-heading scoping
            # availability under the current trim ("Optional Equipment").
            if current_trim is not None:
                if any(p.search(text) for p in _OPTIONAL_HEADING_PATTERNS):
                    current_avail = "optional"
                elif any(p.search(text) for p in _FEATURE_HEADING_PATTERNS):
                    current_avail = "standard"
        elif el.name == "ul" and current_trim is not None:
            for li in el.find_all("li", recursive=False):
                if not isinstance(li, Tag):
                    continue
                label = li.get_text(" ", strip=True)
                if not label:
                    continue
                label = _normalize_label(label)
                if not label or len(label) > 200:
                    continue
                per_trim[current_trim][label] = current_avail

    if not per_trim:
        return [], []

    # Preserve original trim ordering when we have it.
    if trim_list:
        ordered_trims = [t for t in trim_list if t in per_trim]
        for t in per_trim:
            if t not in ordered_trims:
                ordered_trims.append(t)
    else:
        ordered_trims = list(per_trim.keys())

    # Invert to feature-rows.
    all_labels: list[str] = []
    seen_labels: set[str] = set()
    for trim in ordered_trims:
        for label in per_trim[trim]:
            key = label.lower()
            if key not in seen_labels:
                seen_labels.add(key)
                all_labels.append(label)
    feature_rows: list[FeatureRow] = []
    for label in all_labels:
        availability_by_trim: dict[str, Availability] = {}
        for trim in ordered_trims:
            avail = per_trim[trim].get(label)
            if avail is not None:
                availability_by_trim[trim] = avail
        if availability_by_trim:
            feature_rows.append(
                FeatureRow(
                    label=label,
                    availability_by_trim=availability_by_trim,
                )
            )
    return ordered_trims, feature_rows


def _extract_trim_list_from_heading(root: Tag) -> list[str]:
    """Find a "Trim Levels" heading and harvest the following list/table."""
    for heading in root.find_all(_HEADING_TAGS):
        if not isinstance(heading, Tag):
            continue
        text = heading.get_text(" ", strip=True)
        if not text:
            continue
        if not any(p.search(text) for p in _TRIM_HEADING_PATTERNS):
            continue
        # Walk forward through next siblings until we find a <ul>, <ol>, or
        # <table>.
        node = heading.find_next_sibling()
        hops = 0
        while node is not None and hops < 5:
            if isinstance(node, Tag):
                if node.name in ("ul", "ol"):
                    trims = [
                        li.get_text(" ", strip=True)
                        for li in node.find_all("li", recursive=False)
                    ]
                    trims = [_clean_trim_name(t) for t in trims if t]
                    if trims:
                        return trims
                if node.name == "table":
                    table_rows = _table_rows_as_text(node)
                    if table_rows:
                        head = table_rows[0]
                        # Single-column "trim" table -> column 0 is trim names
                        if len(head) == 1:
                            trims = [r[0] for r in table_rows[1:] if r]
                        else:
                            trims = head
                        trims = [_clean_trim_name(t) for t in trims if t]
                        if trims:
                            return trims
            node = node.find_next_sibling()
            hops += 1
    return []


def _heading_names_trim(text: str, trim_list: list[str]) -> str | None:
    """If a heading text starts with (or equals) a known trim name, return it."""
    text_norm = re.sub(r"\s+", " ", text).strip()
    for trim in trim_list:
        # Exact: "Sport"
        if text_norm.lower() == trim.lower():
            return trim
        # Prefix: "Sport - Standard Equipment", "Sport Standard Equipment"
        pattern = re.compile(rf"^{re.escape(trim)}\b", re.IGNORECASE)
        if pattern.search(text_norm):
            return trim
    return None


# --------------------------------------------------------------------------- fallback


def _extract_fallback(root: Tag) -> tuple[list[str], list[FeatureRow]]:
    """Last-ditch extraction from prose paragraphs.

    Looks for sentences shaped like "X is standard on Y and Z" or
    "X is available on Y". We're conservative: we emit cells only when both
    sides of the verb are clearly identifiable.
    """
    text = root.get_text("\n", strip=True)
    # Split into roughly sentence-shaped chunks.
    sentences = re.split(r"(?<=[\.\!\?])\s+", text)
    trims_seen: list[str] = []
    rows_by_label: dict[str, dict[str, Availability]] = {}

    # Detect "X is standard on A, B" / "X is available on A".
    pat = re.compile(
        r"(?P<feature>[A-Z][A-Za-z0-9®™\-\s]{2,80})\s+is\s+"
        r"(?P<avail>standard|available|optional)\s+on\s+"
        r"(?P<trims>[A-Z][A-Za-z0-9\-\s,]+?)(?:\.|$)",
        re.IGNORECASE,
    )
    for sent in sentences:
        for m in pat.finditer(sent):
            feature = _normalize_label(m.group("feature"))
            avail_word = m.group("avail").lower()
            availability: Availability = (
                "standard" if avail_word == "standard" else "optional"
            )
            trim_blob = m.group("trims").strip()
            # "A, B and C" / "A and B"
            trim_blob = re.sub(r"\s+and\s+", ", ", trim_blob, flags=re.IGNORECASE)
            trims = [_clean_trim_name(t) for t in trim_blob.split(",") if t.strip()]
            trims = [t for t in trims if t and len(t) <= 40]
            if not trims:
                continue
            row = rows_by_label.setdefault(feature, {})
            for t in trims:
                if t not in trims_seen:
                    trims_seen.append(t)
                row[t] = availability

    if not rows_by_label:
        return [], []
    feature_rows = [
        FeatureRow(label=label, availability_by_trim=dict(by_trim))
        for label, by_trim in rows_by_label.items()
    ]
    return trims_seen, feature_rows


# --------------------------------------------------------------------------- cell parsing


_DOUBLE_BULLET_RE = re.compile(r"^[●■◆•]{2,}$")
_GLYPH_NORMALIZE_RE = re.compile(r"[\s ]+")


def _cell_text_to_availability(cell: str) -> Availability | None:
    """Map a comparison-table cell's text to an Availability value.

    Honda press releases use the same glyph set as their brochures (filled
    bullet = standard, hollow bullet = optional), plus the literal words
    "Standard" / "Optional" / "N/A" and the single-letter S/O abbreviations.
    """
    if cell is None:
        return None
    stripped = _GLYPH_NORMALIZE_RE.sub(" ", cell).strip()
    if not stripped:
        return None
    # Some cells contain repeated glyphs ("●●") to mean "double-package" --
    # we still treat them as standard.
    if _DOUBLE_BULLET_RE.match(stripped.replace(" ", "")):
        return "standard"
    if stripped in STANDARD_GLYPHS:
        return "standard"
    if stripped in OPTIONAL_GLYPHS:
        return "optional"
    if stripped in NEGATIVE_GLYPHS:
        return "unavailable"
    low = stripped.lower()
    if low in ("standard", "std", "yes", "included"):
        return "standard"
    if low in ("optional", "opt", "available", "package", "pkg"):
        return "optional"
    if low in ("not available", "n/a", "na", "none", "no"):
        return "unavailable"
    # Some saved pages keep a trailing footnote marker; strip it and retry.
    bare = re.sub(r"[*†‡\d]+$", "", stripped).strip()
    if bare and bare != stripped:
        return _cell_text_to_availability(bare)
    return None


# --------------------------------------------------------------------------- text utilities


_FOOTNOTE_RE = re.compile(r"[\*†‡\d]{1,3}$")
_WS_RE = re.compile(r"\s+")


def _normalize_label(label: str) -> str:
    label = _WS_RE.sub(" ", label).strip()
    label = _FOOTNOTE_RE.sub("", label).strip()
    return label


def _is_section_header_text(label: str) -> bool:
    letters = [c for c in label if c.isalpha()]
    if not letters:
        return False
    if all(c.isupper() for c in letters) and len(label) >= 4 and len(label.split()) <= 6:
        return True
    return False


def _clean_trim_name(s: str) -> str:
    s = _WS_RE.sub(" ", s).strip()
    s = re.sub(r"[®™]", "", s)
    # Strip drivetrain suffixes Honda sometimes glues onto trim names.
    s = re.sub(r"\s+(2WD|FWD|AWD|4WD)$", "", s, flags=re.IGNORECASE).strip()
    return s


# Re-export so ``from .extract_hondanews import FeatureRow`` works for tests
# that want to construct rows directly.
__all__ = [
    "ExtractedHondanewsModel",
    "ExtractedModel",
    "FeatureRow",
    "extract_from_hondanews_html",
]
