from __future__ import annotations

from pathlib import Path

import pytest

from vehicle_feature_catalog import VehicleFeatureCatalog
from vehicle_feature_catalog.entities import (
    Feature,
    Make,
    Model,
    Trim,
    TrimFeature,
)
from vehicle_feature_catalog.validator import Validator


INVALID_FIXTURES = Path(__file__).resolve().parents[1] / "fixtures-invalid"


def test_invalid_fixture_surfaces_all_relevant_errors() -> None:
    catalog = VehicleFeatureCatalog.load(INVALID_FIXTURES)
    result = catalog.validate()
    assert not result.is_valid

    codes = {e.code for e in result.errors}
    assert "matrix_unknown_feature" in codes
    assert "matrix_unknown_trim" in codes
    assert "feature_empty_cue_phrases" in codes
    assert "trim_has_no_features" in codes


def test_format_errors_lists_each_finding() -> None:
    catalog = VehicleFeatureCatalog.load(INVALID_FIXTURES)
    result = catalog.validate()
    out = result.format_errors()
    assert "matrix_unknown_feature" in out
    assert "trim_has_no_features" in out


def test_duplicate_id_is_reported() -> None:
    makes = [Make(id="honda", name="Honda", country="JP")]
    models = [
        Model(id="honda.civic", make_id="honda", name="Civic", year=2026),
        Model(id="honda.civic", make_id="honda", name="Civic Dup", year=2026),
    ]
    trims = [Trim(id="honda.civic.2026.sport", model_id="honda.civic", name="Sport")]
    features = [
        Feature(
            id="universal.feature.x",
            display_name="X",
            category="c",
            brand_scope="universal",
            cue_phrases=["x"],
            synonyms=[],
        )
    ]
    matrix = [
        TrimFeature(
            trim_id="honda.civic.2026.sport",
            feature_id="universal.feature.x",
            availability="standard",
        )
    ]

    result = Validator.check(makes, models, trims, features, matrix)
    assert not result.is_valid
    assert any(e.code == "duplicate_id" for e in result.errors)


def test_happy_catalog_validates() -> None:
    fixtures = Path(__file__).resolve().parents[1] / "fixtures"
    catalog = VehicleFeatureCatalog.load(fixtures)
    result = catalog.validate()
    assert result.is_valid, result.format_errors()


def test_empty_cue_phrases_alone_is_an_error() -> None:
    makes = [Make(id="honda", name="Honda", country="JP")]
    models = [Model(id="honda.civic", make_id="honda", name="Civic", year=2026)]
    trims = [Trim(id="honda.civic.2026.sport", model_id="honda.civic", name="Sport")]
    features = [
        Feature(
            id="universal.feature.empty",
            display_name="Empty",
            category="comfort",
            brand_scope="universal",
            cue_phrases=[],
            synonyms=[],
        )
    ]
    matrix = [
        TrimFeature(
            trim_id="honda.civic.2026.sport",
            feature_id="universal.feature.empty",
            availability="standard",
        )
    ]
    result = Validator.check(makes, models, trims, features, matrix)
    assert not result.is_valid
    assert any(e.code == "feature_empty_cue_phrases" for e in result.errors)


def test_trim_without_features_is_an_error() -> None:
    makes = [Make(id="honda", name="Honda", country="JP")]
    models = [Model(id="honda.civic", make_id="honda", name="Civic", year=2026)]
    trims = [
        Trim(id="honda.civic.2026.sport", model_id="honda.civic", name="Sport"),
        Trim(id="honda.civic.2026.orphan", model_id="honda.civic", name="Orphan"),
    ]
    features = [
        Feature(
            id="f.x",
            display_name="X",
            category="c",
            brand_scope="universal",
            cue_phrases=["x"],
            synonyms=[],
        )
    ]
    matrix = [
        TrimFeature(
            trim_id="honda.civic.2026.sport",
            feature_id="f.x",
            availability="standard",
        )
    ]
    result = Validator.check(makes, models, trims, features, matrix)
    assert not result.is_valid
    orphan_errors = [
        e for e in result.errors if e.code == "trim_has_no_features"
    ]
    assert len(orphan_errors) == 1
    assert orphan_errors[0].entity_id == "honda.civic.2026.orphan"
