"""Tier-3 extraction coverage closing pending ledger items.

* S-EXT-6: ExtractedBrochure.is_useful() gating (trims + rows + cells>0).
* S-EXT-5: real-PDF extract() end-to-end against the committed tiny_brochure
           fixture (skips only if it's genuinely absent).
* S-HN-10: the free-text fallback (`X is standard on A and B`) emits cells
           from a prose-only press release.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

_CATALOG_ROOT = Path(__file__).resolve().parents[3]
if str(_CATALOG_ROOT) not in sys.path:
    sys.path.insert(0, str(_CATALOG_ROOT))

from scrapers.honda_us.extract import (  # noqa: E402
    BrochureExtractor,
    ExtractedBrochure,
    FeatureRow,
)
from scrapers.honda_us.extract_hondanews import extract_from_hondanews_html  # noqa: E402

_TINY_PDF = (
    _CATALOG_ROOT / "tests" / "python" / "scrapers" / "fixtures" / "tiny_brochure.pdf"
)


# ----------------------------------------------------------------- S-EXT-6


def test_is_useful_requires_trims_rows_and_cells() -> None:
    # No trims -> not useful.
    assert not ExtractedBrochure(pdf_path=Path("x"), trims=[], feature_rows=[]).is_useful()
    # Trims but no rows -> not useful.
    assert not ExtractedBrochure(
        pdf_path=Path("x"), trims=["LX"], feature_rows=[]
    ).is_useful()
    # Rows present but zero cells -> not useful.
    assert not ExtractedBrochure(
        pdf_path=Path("x"),
        trims=["LX"],
        feature_rows=[FeatureRow(label="Heated Seats", availability_by_trim={})],
    ).is_useful()
    # Trims + rows + at least one cell -> useful.
    assert ExtractedBrochure(
        pdf_path=Path("x"),
        trims=["LX"],
        feature_rows=[
            FeatureRow(label="Heated Seats", availability_by_trim={"LX": "standard"})
        ],
    ).is_useful()


# ----------------------------------------------------------------- S-EXT-5


def test_extract_real_pdf_end_to_end() -> None:
    if not _TINY_PDF.exists():
        pytest.skip(f"No PDF fixture committed at {_TINY_PDF}")
    result = BrochureExtractor().extract(_TINY_PDF)
    # The committed synthetic brochure has a trim x feature grid.
    assert result.is_useful(), result.warnings
    assert len(result.trims) >= 2
    assert result.cell_count > 0


# ----------------------------------------------------------------- S-HN-10


def test_hondanews_free_text_fallback(tmp_path: Path) -> None:
    html = (
        "<html><head><title>2026 Honda Civic Press Release</title></head>"
        "<body><article>"
        "<h1>2026 Honda Civic</h1>"
        "<p>The 2026 Civic arrives this fall. "
        "Wireless Apple CarPlay is standard on Sport and Touring. "
        "Heated Front Seats is available on Touring. "
        "Honda Sensing is standard on Sport and Touring.</p>"
        "</article></body></html>"
    )
    path = tmp_path / "civic.html"
    path.write_text(html, encoding="utf-8")

    extracted = extract_from_hondanews_html(path)
    # Prose-only layout -> free-text fallback -> low confidence.
    assert extracted.extraction_confidence == "low"
    assert extracted.is_useful()
    labels = {r.label for r in extracted.feature_rows}
    assert "Wireless Apple CarPlay" in labels
    assert "Honda Sensing" in labels
    # Trim-scoped availability: Heated Front Seats only reaches Touring.
    heated = next(r for r in extracted.feature_rows if r.label == "Heated Front Seats")
    assert set(heated.availability_by_trim) == {"Touring"}
