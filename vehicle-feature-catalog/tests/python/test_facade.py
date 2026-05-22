from __future__ import annotations

from pathlib import Path

import pytest

from vehicle_feature_catalog import (
    NotFoundError,
    VehicleFeatureCatalog,
)


FIXTURES = Path(__file__).resolve().parents[1] / "fixtures"


@pytest.fixture(scope="module")
def catalog() -> VehicleFeatureCatalog:
    return VehicleFeatureCatalog.load(FIXTURES)


def test_load_returns_catalog(catalog: VehicleFeatureCatalog) -> None:
    assert isinstance(catalog, VehicleFeatureCatalog)


def test_get_make(catalog: VehicleFeatureCatalog) -> None:
    honda = catalog.get_make("honda")
    assert honda.name == "Honda"
    assert honda.country == "JP"


def test_get_make_unknown_raises(catalog: VehicleFeatureCatalog) -> None:
    with pytest.raises(NotFoundError):
        catalog.get_make("ford")


def test_get_model_and_trim_and_feature(catalog: VehicleFeatureCatalog) -> None:
    assert catalog.get_model("honda.civic").name == "Civic"
    assert catalog.get_trim("honda.civic.2026.sport").name == "Sport"
    assert (
        catalog.get_feature("universal.feature.wireless_apple_carplay").display_name
        == "Wireless Apple CarPlay"
    )


def test_list_makes(catalog: VehicleFeatureCatalog) -> None:
    ids = {m.id for m in catalog.list_makes()}
    assert ids == {"honda", "toyota"}


def test_list_models_filtered(catalog: VehicleFeatureCatalog) -> None:
    honda_models = catalog.list_models(make_id="honda")
    assert [m.id for m in honda_models] == ["honda.civic"]
    all_models = catalog.list_models()
    assert {m.id for m in all_models} == {"honda.civic", "toyota.corolla"}


def test_list_trims_filtered(catalog: VehicleFeatureCatalog) -> None:
    civic_trims = catalog.list_trims(model_id="honda.civic")
    assert {t.id for t in civic_trims} == {
        "honda.civic.2026.sport",
        "honda.civic.2026.touring",
    }


def test_list_features_filter_brand_scope(catalog: VehicleFeatureCatalog) -> None:
    honda_features = catalog.list_features(brand_scope="honda")
    assert [f.id for f in honda_features] == ["honda.feature.honda_sensing_360plus"]


def test_list_features_filter_category(catalog: VehicleFeatureCatalog) -> None:
    comfort = catalog.list_features(category="comfort")
    assert {f.id for f in comfort} == {"universal.feature.heated_front_seats"}


def test_list_features_for_trim_standard_only(catalog: VehicleFeatureCatalog) -> None:
    feats = catalog.list_features_for_trim("honda.civic.2026.sport")
    ids = {f.id for f in feats}
    assert ids == {
        "universal.feature.wireless_apple_carplay",
        "universal.feature.lane_keep_assist",
    }


def test_list_features_for_trim_includes_optional_when_requested(
    catalog: VehicleFeatureCatalog,
) -> None:
    feats = catalog.list_features_for_trim(
        "honda.civic.2026.sport",
        availability=["standard", "optional"],
    )
    ids = {f.id for f in feats}
    assert ids == {
        "universal.feature.wireless_apple_carplay",
        "universal.feature.lane_keep_assist",
        "universal.feature.heated_front_seats",
    }


def test_list_features_for_unknown_trim_raises(catalog: VehicleFeatureCatalog) -> None:
    with pytest.raises(NotFoundError):
        catalog.list_features_for_trim("honda.nope")


def test_list_trims_with_feature(catalog: VehicleFeatureCatalog) -> None:
    trims = catalog.list_trims_with_feature("universal.feature.wireless_apple_carplay")
    assert {t.id for t in trims} == {
        "honda.civic.2026.sport",
        "honda.civic.2026.touring",
        "toyota.corolla.2026.le",
    }


def test_list_trims_with_unknown_feature_raises(catalog: VehicleFeatureCatalog) -> None:
    with pytest.raises(NotFoundError):
        catalog.list_trims_with_feature("universal.feature.unknown")


def test_validate_on_happy_catalog(catalog: VehicleFeatureCatalog) -> None:
    result = catalog.validate()
    assert result.is_valid, result.format_errors()
    assert result.errors == []
