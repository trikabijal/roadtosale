"""Tests for the hondanews.com saved-HTML extractor.

The committed fixture at ``fixtures/tiny_press_release.html`` mirrors what
we observe in real Honda press releases: a "Trim Levels" heading + list,
a comparison table with mixed glyph / word availability cells, and a
couple of per-trim "Standard Equipment" lists.
"""

from __future__ import annotations

import sys
from pathlib import Path

_CATALOG_ROOT = Path(__file__).resolve().parents[3]
if str(_CATALOG_ROOT) not in sys.path:
    sys.path.insert(0, str(_CATALOG_ROOT))

from scrapers.honda_us.extract_hondanews import (  # noqa: E402
    ExtractedHondanewsModel,
    ExtractedModel,
    _cell_text_to_availability,
    _clean_trim_name,
    _normalize_label,
    extract_from_hondanews_html,
)


FIXTURE = Path(__file__).resolve().parent / "fixtures" / "tiny_press_release.html"


def test_alias_extracted_model_matches_subclass():
    """The scraper spec refers to ExtractedModel; we expose it as an alias."""
    assert ExtractedModel is ExtractedHondanewsModel


def test_extract_returns_expected_trims_from_table():
    result = extract_from_hondanews_html(FIXTURE)
    assert result.trims == ["LX", "Sport", "EX", "Touring"]


def test_extract_returns_feature_rows_with_availability():
    result = extract_from_hondanews_html(FIXTURE)
    labels = {r.label for r in result.feature_rows}
    assert {"Honda Sensing", "Wireless Apple CarPlay", "Heated Front Seats", "LED Headlights"} <= labels

    by_label = {r.label: r.availability_by_trim for r in result.feature_rows}

    # Glyph: filled bullet on every trim.
    assert by_label["Honda Sensing"] == {
        "LX": "standard", "Sport": "standard", "EX": "standard", "Touring": "standard",
    }
    # Mixed cells: em-dash unavailable, S standard, literal "Standard".
    assert by_label["Wireless Apple CarPlay"] == {
        "LX": "unavailable", "Sport": "standard", "EX": "standard", "Touring": "standard",
    }
    # Hollow bullet optional, O optional, literal "Standard".
    assert by_label["Heated Front Seats"] == {
        "LX": "unavailable", "Sport": "optional", "EX": "optional", "Touring": "standard",
    }
    # Double bullet "●●" still maps to standard.
    assert by_label["Wireless Phone Charger"]["Touring"] == "standard"
    assert by_label["Wireless Phone Charger"]["EX"] == "optional"


def test_extract_picks_table_over_per_trim_lists_when_richer():
    result = extract_from_hondanews_html(FIXTURE)
    # The fixture's table has 5 rows x 4 trims; the per-trim lists are tiny.
    # We expect the table to win (>= 5 cells => high confidence).
    assert result.extraction_confidence == "high"
    assert len(result.feature_rows) >= 5


def test_cell_text_to_availability_handles_known_values():
    assert _cell_text_to_availability("●") == "standard"
    assert _cell_text_to_availability("○") == "optional"
    assert _cell_text_to_availability("—") == "unavailable"
    assert _cell_text_to_availability("S") == "standard"
    assert _cell_text_to_availability("O") == "optional"
    assert _cell_text_to_availability("Standard") == "standard"
    assert _cell_text_to_availability("optional") == "optional"
    assert _cell_text_to_availability("N/A") == "unavailable"
    assert _cell_text_to_availability("●●") == "standard"
    assert _cell_text_to_availability("") is None
    assert _cell_text_to_availability("   ") is None


def test_normalize_label_strips_footnote_markers():
    assert _normalize_label("Heated Front Seats *") == "Heated Front Seats"
    assert _normalize_label("LED   Headlights") == "LED Headlights"
    assert _normalize_label("Honda Sensing  2") == "Honda Sensing"


def test_clean_trim_name_strips_drivetrain_suffix():
    assert _clean_trim_name("Sport Touring AWD") == "Sport Touring"
    assert _clean_trim_name("EX FWD") == "EX"
    assert _clean_trim_name("LX") == "LX"


def test_missing_file_does_not_raise():
    bogus = FIXTURE.parent / "this-file-does-not-exist.html"
    result = extract_from_hondanews_html(bogus)
    assert result.trims == []
    assert result.feature_rows == []
    assert result.extraction_confidence == "low"
    assert any("not found" in w.lower() for w in result.warnings)


def test_missing_sections_yields_low_confidence(tmp_path: Path):
    """A press release with no table and no per-trim lists yields low confidence."""
    html = (
        "<html><body><article>"
        "<h1>2026 Honda Civic</h1>"
        "<p>Just some boilerplate text with no structure.</p>"
        "</article></body></html>"
    )
    target = tmp_path / "no_structure.html"
    target.write_text(html, encoding="utf-8")
    result = extract_from_hondanews_html(target)
    assert result.extraction_confidence == "low"
    assert any("manual review" in w.lower() or "feature rows" in w.lower() for w in result.warnings)


def test_per_trim_lists_only_layout(tmp_path: Path):
    """When there is no comparison table, per-trim lists become the source."""
    html = """
    <html><body><article>
      <h1>2026 Honda Pilot Press Kit</h1>
      <h2>Trim Levels</h2>
      <ul>
        <li>Sport</li>
        <li>Touring</li>
      </ul>
      <h2>Sport Standard Equipment</h2>
      <ul>
        <li>Honda Sensing</li>
        <li>LED Headlights</li>
      </ul>
      <h2>Touring Standard Equipment</h2>
      <ul>
        <li>Honda Sensing</li>
        <li>LED Headlights</li>
        <li>Heated Front Seats</li>
        <li>Wireless Phone Charger</li>
      </ul>
    </article></body></html>
    """
    target = tmp_path / "lists_only.html"
    target.write_text(html, encoding="utf-8")
    result = extract_from_hondanews_html(target)
    assert "Sport" in result.trims
    assert "Touring" in result.trims
    labels = {r.label for r in result.feature_rows}
    assert "Honda Sensing" in labels
    assert "Heated Front Seats" in labels
    by_label = {r.label: r.availability_by_trim for r in result.feature_rows}
    assert by_label["Honda Sensing"]["Sport"] == "standard"
    assert by_label["Honda Sensing"]["Touring"] == "standard"
    # Heated Front Seats is Touring-only; Sport must not appear in its row.
    assert "Sport" not in by_label["Heated Front Seats"]


def test_model_hint_guessed_from_title():
    result = extract_from_hondanews_html(FIXTURE)
    assert result.model_hint is not None
    assert "Civic" in result.model_hint
