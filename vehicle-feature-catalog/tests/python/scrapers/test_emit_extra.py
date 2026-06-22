"""Emit-level coverage closing pending ledger items.

* S-EMT-6: _dedupe_cells keeps the strongest availability on a repeated
           (trim, feature) pair (standard > optional > unavailable).
* S-EMT-7: emitting a low-confidence ExtractedModel annotates the written
           matrix entries with extraction_confidence: low / needs_review:
           true, and the loader still ignores those extra keys (catalog
           reloads + validates).

The dedupe helper is a documented pure staticmethod, exercised directly. The
needs_review case drives emit() and asserts the observable side effect — the
YAML on disk plus a clean reload/validate through the facade.
"""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

import pytest
import yaml

_CATALOG_ROOT = Path(__file__).resolve().parents[3]
if str(_CATALOG_ROOT) not in sys.path:
    sys.path.insert(0, str(_CATALOG_ROOT))
_SRC = _CATALOG_ROOT / "src" / "python"
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

from scrapers.honda_us.emit import CatalogEmitter  # noqa: E402
from scrapers.honda_us.extract import FeatureRow  # noqa: E402
from scrapers.honda_us.extract_hondanews import ExtractedModel  # noqa: E402
from vehicle_feature_catalog import VehicleFeatureCatalog  # noqa: E402

_REAL_DATA = _CATALOG_ROOT / "data"


@pytest.fixture
def tmp_data_dir(tmp_path: Path) -> Path:
    target = tmp_path / "data"
    shutil.copytree(_REAL_DATA, target)
    return target


# ----------------------------------------------------------------- S-EMT-6


def test_dedupe_cells_stronger_availability_wins() -> None:
    cells = [
        {"feature_id": "universal.feature.heated_front_seats", "availability": "unavailable"},
        {"feature_id": "universal.feature.heated_front_seats", "availability": "optional"},
        {"feature_id": "universal.feature.heated_front_seats", "availability": "standard"},
        {"feature_id": "universal.feature.wireless_apple_carplay", "availability": "optional"},
        {"feature_id": "universal.feature.wireless_apple_carplay", "availability": "unavailable"},
    ]
    deduped = CatalogEmitter._dedupe_cells(cells)
    by_id = {c["feature_id"]: c["availability"] for c in deduped}

    assert by_id["universal.feature.heated_front_seats"] == "standard"
    assert by_id["universal.feature.wireless_apple_carplay"] == "optional"
    # One cell per feature_id.
    assert len(deduped) == 2


def test_dedupe_cells_drops_unknown_availability() -> None:
    cells = [
        {"feature_id": "f.x", "availability": "standard"},
        {"feature_id": "f.x", "availability": "someday"},  # bad value, ignored
        {"availability": "standard"},  # no feature_id, ignored
    ]
    deduped = CatalogEmitter._dedupe_cells(cells)
    assert deduped == [{"feature_id": "f.x", "availability": "standard"}]


# ----------------------------------------------------------------- S-EMT-7


def _low_conf_extracted() -> ExtractedModel:
    rows = [
        FeatureRow(
            label="Wireless Apple CarPlay",
            availability_by_trim={"LX": "standard", "EX": "standard"},
        ),
        FeatureRow(
            label="Honda Sensing",
            availability_by_trim={"LX": "standard", "EX": "standard"},
        ),
    ]
    return ExtractedModel(
        pdf_path=Path("/dev/null/fake-civic.html"),
        model_hint="2026 Civic",
        trims=["LX", "EX"],
        feature_rows=rows,
        extraction_confidence="low",
    )


def test_low_confidence_emit_sets_needs_review(tmp_data_dir: Path) -> None:
    emitter = CatalogEmitter(data_dir=tmp_data_dir, year=2026)
    result = emitter.emit(
        model_slug="civic",
        model_display_name="Civic",
        body_style="compact_car",
        extracted=_low_conf_extracted(),
        dry_run=False,
    )
    assert result.matrix_cells_added > 0

    # Only the trims this emit actually wrote ("LX", "EX") get the review flag.
    # Pre-existing untouched Civic entries (e.g. Sport) must be left alone.
    emitted_trim_ids = {"honda.civic.2026.lx", "honda.civic.2026.ex"}
    matrix = yaml.safe_load((tmp_data_dir / "matrix" / "honda.yaml").read_text())
    touched = [
        e for e in matrix["entries"] if e.get("trim_id") in emitted_trim_ids
    ]
    assert len(touched) == len(emitted_trim_ids), "emit did not write both trims"
    for entry in touched:
        assert entry.get("needs_review") is True
        assert entry.get("extraction_confidence") == "low"

    # The loader must ignore the extra keys — catalog still loads + validates.
    catalog = VehicleFeatureCatalog.load(tmp_data_dir)
    assert catalog.validate().is_valid, catalog.validate().format_errors()


def test_high_confidence_emit_has_no_needs_review(tmp_data_dir: Path) -> None:
    """Control: the default (high) confidence path writes no review flag."""
    extracted = _low_conf_extracted()
    extracted.extraction_confidence = "high"
    emitter = CatalogEmitter(data_dir=tmp_data_dir, year=2026)
    emitter.emit(
        model_slug="civic",
        model_display_name="Civic",
        body_style="compact_car",
        extracted=extracted,
        dry_run=False,
    )
    emitted_trim_ids = {"honda.civic.2026.lx", "honda.civic.2026.ex"}
    matrix = yaml.safe_load((tmp_data_dir / "matrix" / "honda.yaml").read_text())
    touched = [
        e for e in matrix["entries"] if e.get("trim_id") in emitted_trim_ids
    ]
    assert len(touched) == len(emitted_trim_ids)
    for entry in touched:
        assert "needs_review" not in entry
        assert "extraction_confidence" not in entry
