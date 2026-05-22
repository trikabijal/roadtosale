"""End-to-end test of the emit step.

Builds a synthetic extracted brochure, runs CatalogEmitter, then loads the
resulting data directory through ``VehicleFeatureCatalog.load(...)`` and
asserts ``validate()`` passes.
"""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

import pytest

_CATALOG_ROOT = Path(__file__).resolve().parents[3]
if str(_CATALOG_ROOT) not in sys.path:
    sys.path.insert(0, str(_CATALOG_ROOT))

_SRC = _CATALOG_ROOT / "src" / "python"
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

from scrapers.honda_us.emit import CatalogEmitter  # noqa: E402
from scrapers.honda_us.extract import ExtractedBrochure, FeatureRow  # noqa: E402
from vehicle_feature_catalog import VehicleFeatureCatalog  # noqa: E402


CATALOG_DATA_DIR = _CATALOG_ROOT / "data"


@pytest.fixture
def tmp_data_dir(tmp_path: Path) -> Path:
    """Copy the real catalog data/ into a temp dir so we can mutate freely."""
    target = tmp_path / "data"
    shutil.copytree(CATALOG_DATA_DIR, target)
    return target


def _make_extracted() -> ExtractedBrochure:
    """A synthetic Civic brochure extraction — small but realistic."""
    rows = [
        FeatureRow(
            label="Heated Front Seats",
            availability_by_trim={"LX": "unavailable", "EX": "standard", "Touring": "standard"},
        ),
        FeatureRow(
            label="Wireless Apple CarPlay",
            availability_by_trim={"LX": "standard", "EX": "standard", "Touring": "standard"},
        ),
        FeatureRow(
            label="Honda Sensing",
            availability_by_trim={"LX": "standard", "EX": "standard", "Touring": "standard"},
        ),
        FeatureRow(
            label="Premium Trunk Liner",  # unmapped — should be created as honda.feature.*
            availability_by_trim={"LX": "unavailable", "EX": "optional", "Touring": "standard"},
        ),
    ]
    return ExtractedBrochure(
        pdf_path=Path("/dev/null/fake-civic.pdf"),
        model_hint="2026 Civic",
        trims=["LX", "EX", "Touring"],
        feature_rows=rows,
    )


def test_emit_produces_valid_catalog(tmp_data_dir: Path):
    emitter = CatalogEmitter(data_dir=tmp_data_dir, year=2026)
    extracted = _make_extracted()
    result = emitter.emit(
        model_slug="civic",
        model_display_name="Civic",
        body_style="compact_car",
        extracted=extracted,
        dry_run=False,
    )

    # Files materialized.
    assert result.model_file is not None and result.model_file.exists()
    assert len(result.trim_files) == 3
    for tf in result.trim_files:
        assert tf.exists()
    # The "Premium Trunk Liner" label should have produced a new feature file.
    assert any("trunk-liner" in str(p) or "trunk_liner" in str(p) for p in result.new_feature_files)
    for nf in result.new_feature_files:
        assert nf.exists()

    # Matrix should now contain Civic entries AND preserve CR-V Hybrid AWD.
    catalog = VehicleFeatureCatalog.load(tmp_data_dir)
    civic_lx = catalog.get_trim("honda.civic.2026.lx")
    assert civic_lx.name == "LX"
    cr_v_sport = catalog.get_trim("honda.cr-v-hybrid-awd.2026.sport")
    assert cr_v_sport.name == "Sport"

    # Civic Touring should have Heated Front Seats standard.
    touring_features = {f.id for f in catalog.list_features_for_trim("honda.civic.2026.touring")}
    assert "universal.feature.heated_front_seats" in touring_features
    assert "universal.feature.wireless_apple_carplay" in touring_features
    assert "honda.feature.honda_sensing" in touring_features

    # And the catalog as a whole must validate.
    validation = catalog.validate()
    assert validation.is_valid, validation.format_errors()


def test_emit_dry_run_does_not_write(tmp_data_dir: Path):
    emitter = CatalogEmitter(data_dir=tmp_data_dir, year=2026)
    extracted = _make_extracted()
    before = {p.relative_to(tmp_data_dir) for p in tmp_data_dir.rglob("*") if p.is_file()}
    result = emitter.emit(
        model_slug="civic",
        model_display_name="Civic",
        body_style="compact_car",
        extracted=extracted,
        dry_run=True,
    )
    after = {p.relative_to(tmp_data_dir) for p in tmp_data_dir.rglob("*") if p.is_file()}
    assert before == after, "Dry run wrote files to disk"
    assert result.matrix_cells_added > 0
