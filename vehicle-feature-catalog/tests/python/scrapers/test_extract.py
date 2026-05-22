"""Tests for the PDF extraction step.

These tests exercise pure helpers; the end-to-end pdfplumber extraction is
skipped when no fixture PDF is available, since shipping a Honda brochure
verbatim into the repo isn't sensible.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

_CATALOG_ROOT = Path(__file__).resolve().parents[3]
if str(_CATALOG_ROOT) not in sys.path:
    sys.path.insert(0, str(_CATALOG_ROOT))

from scrapers.honda_us.extract import BrochureExtractor, FeatureRow  # noqa: E402


def test_cell_to_availability_glyphs():
    ext = BrochureExtractor()
    assert ext._cell_to_availability("●") == "standard"
    assert ext._cell_to_availability("S") == "standard"
    assert ext._cell_to_availability("○") == "optional"
    assert ext._cell_to_availability("O") == "optional"
    assert ext._cell_to_availability("—") == "unavailable"
    assert ext._cell_to_availability("") is None
    assert ext._cell_to_availability("    ") is None
    assert ext._cell_to_availability("Standard") == "standard"
    assert ext._cell_to_availability("Optional") == "optional"


def test_normalize_label_strips_footnote_markers():
    assert BrochureExtractor._normalize_label("Heated Front Seats *") == "Heated Front Seats"
    assert BrochureExtractor._normalize_label("Wireless Apple CarPlay®2") == "Wireless Apple CarPlay®"
    # Internal whitespace collapses.
    assert BrochureExtractor._normalize_label("LED   Headlights") == "LED Headlights"


def test_detect_header_row_picks_trim_row():
    ext = BrochureExtractor(min_trims=3)
    table = [
        ["Feature", "Sport", "Sport-L", "Sport Touring"],
        ["Heated Front Seats", "", "●", "●"],
        ["LED Headlights", "●", "●", "●"],
    ]
    idx, trims = ext._detect_header_row(table)
    assert idx == 0
    assert trims == ["Sport", "Sport-L", "Sport Touring"]


def test_absorb_rows_builds_feature_rows():
    ext = BrochureExtractor(min_trims=2, min_rows=1)
    table = [
        ["Feature", "Sport", "Touring"],
        ["Heated Front Seats", "", "●"],
        ["LED Headlights", "●", "●"],
        ["", "", ""],  # blank line should be skipped
    ]
    rows: list[FeatureRow] = []
    trims = ["Sport", "Touring"]
    ext._absorb_rows(table[1:], trims, rows)
    labels = [r.label for r in rows]
    assert "Heated Front Seats" in labels
    assert "LED Headlights" in labels
    led = next(r for r in rows if r.label == "LED Headlights")
    assert led.availability_by_trim == {"Sport": "standard", "Touring": "standard"}
    heated = next(r for r in rows if r.label == "Heated Front Seats")
    assert heated.availability_by_trim == {"Touring": "standard"}


def test_extract_real_pdf_skipped_when_no_fixture():
    fixture = Path(__file__).resolve().parent / "fixtures" / "tiny_brochure.pdf"
    if not fixture.exists():
        pytest.skip(
            f"No PDF fixture committed at {fixture}; shipping a verbatim Honda "
            "brochure into the repo is not desirable. Run the CLI against a "
            "real download to exercise this path end-to-end."
        )
    ext = BrochureExtractor()
    result = ext.extract(fixture)
    # Even a tiny synthetic fixture should at least produce *something*.
    assert result.trims or result.feature_rows
