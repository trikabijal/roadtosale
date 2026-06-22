"""Python half of the cross-language parity harness (C-PAR-1, C-PAR-2).

Both this suite and tests/ts/parity.test.ts read the SAME
tests/parity/expectations.json and assert the SAME results on the SAME
fixtures. If the Python facade drifts from the shared contract, this test
fails; if the TS facade drifts, the TS test fails. Drift is no longer caught
only by hand-keeping two mirrored suites green.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from vehicle_feature_catalog import VehicleFeatureCatalog


_CATALOG_ROOT = Path(__file__).resolve().parents[2]
_FIXTURES = _CATALOG_ROOT / "tests" / "fixtures"
_FIXTURES_INVALID = _CATALOG_ROOT / "tests" / "fixtures-invalid"
_EXPECTATIONS = _CATALOG_ROOT / "tests" / "parity" / "expectations.json"


@pytest.fixture(scope="module")
def expected() -> dict:
    return json.loads(_EXPECTATIONS.read_text(encoding="utf-8"))


@pytest.fixture(scope="module")
def catalog() -> VehicleFeatureCatalog:
    return VehicleFeatureCatalog.load(_FIXTURES)


def _ids(entities) -> list[str]:
    return sorted(e.id for e in entities)


def test_parity_facade_method_set(catalog: VehicleFeatureCatalog) -> None:
    """The Python facade exposes the shared public method set."""
    for name in (
        "get_make", "get_model", "get_trim", "get_feature",
        "list_makes", "list_models", "list_trims", "list_features",
        "list_features_for_trim", "list_trims_with_feature", "validate",
    ):
        assert callable(getattr(catalog, name)), f"missing facade method {name}"


def test_parity_query_results(catalog: VehicleFeatureCatalog, expected: dict) -> None:
    f = expected["fixtures"]
    assert _ids(catalog.list_makes()) == f["list_makes"]
    assert _ids(catalog.list_models()) == f["list_models_all"]
    assert _ids(catalog.list_models(make_id="honda")) == f["list_models_honda"]
    assert _ids(catalog.list_trims(model_id="honda.civic")) == f["list_trims_honda_civic"]
    assert _ids(catalog.list_features(brand_scope="honda")) == f["list_features_brand_honda"]
    assert _ids(catalog.list_features(category="comfort")) == f["list_features_category_comfort"]
    assert (
        _ids(catalog.list_features_for_trim("honda.civic.2026.sport"))
        == f["list_features_for_trim_sport_standard"]
    )
    assert (
        _ids(
            catalog.list_features_for_trim(
                "honda.civic.2026.sport", availability=["standard", "optional"]
            )
        )
        == f["list_features_for_trim_sport_standard_optional"]
    )
    assert (
        _ids(catalog.list_trims_with_feature("universal.feature.wireless_apple_carplay"))
        == f["list_trims_with_carplay"]
    )


def test_parity_validator_error_codes(expected: dict) -> None:
    catalog = VehicleFeatureCatalog.load(_FIXTURES_INVALID)
    codes = sorted({e.code for e in catalog.validate().errors})
    assert codes == expected["fixtures_invalid"]["error_codes"]
