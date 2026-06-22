"""Additional facade/loader/validator coverage closing pending ledger items.

* C-LOAD-2: the shipped real data/ tree loads and validates.
* C-LOAD-3: a colliding ID across two entity types raises DuplicateIdError
            from load (not just a validate() finding).
* C-LIST-4: list_features(brand_scope, category) combined filter.
* C-MTX-5:  a matrix repeating a (trim, feature) pair de-dupes in both
            matrix queries.
* C-VAL-6:  parent-reference problems are warnings, not errors.
* C-LDR-5:  empty file, non-mapping top-level, and wrong-length msrp_range
            each raise LoadError.

Black-box: drives only the public facade entry points (load / get_* / list_*
/ validate) plus YAMLLoader for the loader-shape cases, mirroring the style of
the existing test_facade.py / test_loader.py suites.
"""

from __future__ import annotations

import shutil
from pathlib import Path

import pytest

from vehicle_feature_catalog import (
    DuplicateIdError,
    VehicleFeatureCatalog,
)
from vehicle_feature_catalog.errors import LoadError
from vehicle_feature_catalog.loader import YAMLLoader


_CATALOG_ROOT = Path(__file__).resolve().parents[2]
_REAL_DATA = _CATALOG_ROOT / "data"
_FIXTURES = Path(__file__).resolve().parents[1] / "fixtures"


def _write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


# ----------------------------------------------------------------- C-LOAD-2


def test_real_data_loads_and_validates() -> None:
    catalog = VehicleFeatureCatalog.load(_REAL_DATA)
    result = catalog.validate()
    assert result.is_valid, result.format_errors()
    # The shipped catalog has the hand-seeded CR-V Hybrid AWD lineup.
    assert catalog.get_trim("honda.cr-v-hybrid-awd.2026.sport").name == "Sport"


# ----------------------------------------------------------------- C-LOAD-3


def test_duplicate_id_raises_on_load(tmp_path: Path) -> None:
    """A make and a feature share an ID -> DuplicateIdError at index build."""
    data = tmp_path / "data"
    shutil.copytree(_FIXTURES, data)
    # Mint a feature whose id collides with an existing make id ("honda").
    _write(
        data / "features" / "universal" / "collision.yaml",
        "id: honda\n"  # collides with makes/honda.yaml
        "display_name: Collision\n"
        "category: comfort\n"
        "brand_scope: universal\n"
        "cue_phrases:\n  - boom\n"
        "synonyms: []\n",
    )
    with pytest.raises(DuplicateIdError):
        VehicleFeatureCatalog.load(data)


# ----------------------------------------------------------------- C-LIST-4


def test_list_features_combined_filters() -> None:
    catalog = VehicleFeatureCatalog.load(_FIXTURES)
    # honda + driver_assistance -> the honda sensing feature only.
    combined = catalog.list_features(
        brand_scope="honda", category="driver_assistance"
    )
    assert [f.id for f in combined] == ["honda.feature.honda_sensing_360plus"]
    # honda + comfort -> nothing (the comfort feature is universal-scoped).
    none = catalog.list_features(brand_scope="honda", category="comfort")
    assert none == []


# ----------------------------------------------------------------- C-MTX-5


def _build_repeat_matrix_catalog(root: Path) -> None:
    _write(root / "makes" / "honda.yaml", "id: honda\nname: Honda\ncountry: JP\n")
    _write(
        root / "models" / "honda" / "civic.yaml",
        "id: honda.civic\nmake_id: honda\nname: Civic\nyear: 2026\n",
    )
    _write(
        root / "trims" / "honda" / "civic" / "2026" / "sport.yaml",
        "id: honda.civic.2026.sport\nmodel_id: honda.civic\nname: Sport\n",
    )
    _write(
        root / "features" / "universal" / "carplay.yaml",
        "id: universal.feature.carplay\n"
        "display_name: Wireless Apple CarPlay\n"
        "category: connectivity\n"
        "brand_scope: universal\n"
        "cue_phrases:\n  - carplay\n"
        "synonyms: []\n",
    )
    # The SAME (trim, feature) cell appears twice.
    _write(
        root / "matrix" / "honda.yaml",
        "make_id: honda\n"
        "entries:\n"
        "  - trim_id: honda.civic.2026.sport\n"
        "    features:\n"
        "      - { feature_id: universal.feature.carplay, availability: standard }\n"
        "      - { feature_id: universal.feature.carplay, availability: standard }\n",
    )


def test_matrix_query_dedupes_repeated_cell(tmp_path: Path) -> None:
    data = tmp_path / "data"
    _build_repeat_matrix_catalog(data)
    catalog = VehicleFeatureCatalog.load(data)

    feats = catalog.list_features_for_trim("honda.civic.2026.sport")
    assert [f.id for f in feats] == ["universal.feature.carplay"]

    trims = catalog.list_trims_with_feature("universal.feature.carplay")
    assert [t.id for t in trims] == ["honda.civic.2026.sport"]


# ----------------------------------------------------------------- C-VAL-6


def _build_parent_warning_catalog(root: Path) -> None:
    # A make exists, but the model points at a DIFFERENT make_id, and the trim
    # points at a model_id that exists -> only the model warning fires.
    _write(root / "makes" / "honda.yaml", "id: honda\nname: Honda\ncountry: JP\n")
    _write(
        root / "models" / "honda" / "civic.yaml",
        "id: honda.civic\nmake_id: ghost-make\nname: Civic\nyear: 2026\n",
    )
    _write(
        root / "trims" / "honda" / "civic" / "2026" / "sport.yaml",
        "id: honda.civic.2026.sport\nmodel_id: ghost-model\nname: Sport\n",
    )
    _write(
        root / "features" / "universal" / "carplay.yaml",
        "id: universal.feature.carplay\n"
        "display_name: Wireless Apple CarPlay\n"
        "category: connectivity\n"
        "brand_scope: universal\n"
        "cue_phrases:\n  - carplay\n"
        "synonyms: []\n",
    )
    _write(
        root / "matrix" / "honda.yaml",
        "make_id: honda\n"
        "entries:\n"
        "  - trim_id: honda.civic.2026.sport\n"
        "    features:\n"
        "      - { feature_id: universal.feature.carplay, availability: standard }\n",
    )


def test_validate_emits_parent_warnings(tmp_path: Path) -> None:
    data = tmp_path / "data"
    _build_parent_warning_catalog(data)
    catalog = VehicleFeatureCatalog.load(data)
    result = catalog.validate()

    warning_codes = {w.code for w in result.warnings}
    assert "model_unknown_make" in warning_codes
    assert "trim_unknown_model" in warning_codes
    # Parent-ref problems are warnings only — they must NOT flip is_valid.
    assert result.is_valid, result.format_errors()
    assert result.errors == []


# ----------------------------------------------------------------- C-LDR-5


def test_loader_rejects_empty_file(tmp_path: Path) -> None:
    makes = tmp_path / "makes"
    makes.mkdir()
    (makes / "honda.yaml").write_text("", encoding="utf-8")
    with pytest.raises(LoadError):
        YAMLLoader.read_all(tmp_path)


def test_loader_rejects_non_mapping_top_level(tmp_path: Path) -> None:
    makes = tmp_path / "makes"
    makes.mkdir()
    (makes / "honda.yaml").write_text("- just\n- a\n- list\n", encoding="utf-8")
    with pytest.raises(LoadError):
        YAMLLoader.read_all(tmp_path)


def test_loader_rejects_wrong_length_msrp_range(tmp_path: Path) -> None:
    trims = tmp_path / "trims" / "honda" / "civic" / "2026"
    trims.mkdir(parents=True)
    (trims / "sport.yaml").write_text(
        "id: honda.civic.2026.sport\n"
        "model_id: honda.civic\n"
        "name: Sport\n"
        "msrp_range: [1, 2, 3]\n",
        encoding="utf-8",
    )
    with pytest.raises(LoadError):
        YAMLLoader.read_all(tmp_path)
