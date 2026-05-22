from __future__ import annotations

from pathlib import Path

import pytest

from vehicle_feature_catalog.errors import LoadError
from vehicle_feature_catalog.loader import YAMLLoader


FIXTURES = Path(__file__).resolve().parents[1] / "fixtures"


def test_read_all_returns_all_entities() -> None:
    raw = YAMLLoader.read_all(FIXTURES)

    assert {m.id for m in raw.makes} == {"honda", "toyota"}
    assert {m.id for m in raw.models} == {"honda.civic", "toyota.corolla"}
    assert {t.id for t in raw.trims} == {
        "honda.civic.2026.sport",
        "honda.civic.2026.touring",
        "toyota.corolla.2026.le",
    }
    assert {f.id for f in raw.features} == {
        "universal.feature.wireless_apple_carplay",
        "universal.feature.heated_front_seats",
        "universal.feature.lane_keep_assist",
        "honda.feature.honda_sensing_360plus",
    }
    assert len(raw.trim_features) == 9


def test_msrp_range_is_parsed_as_tuple() -> None:
    raw = YAMLLoader.read_all(FIXTURES)
    sport = next(t for t in raw.trims if t.id == "honda.civic.2026.sport")
    assert sport.msrp_range == (25000, 27000)


def test_feature_cue_phrases_parsed() -> None:
    raw = YAMLLoader.read_all(FIXTURES)
    carplay = next(
        f for f in raw.features if f.id == "universal.feature.wireless_apple_carplay"
    )
    assert "wireless Apple CarPlay" in carplay.cue_phrases


def test_missing_data_dir_raises_load_error(tmp_path: Path) -> None:
    with pytest.raises(LoadError):
        YAMLLoader.read_all(tmp_path / "nope")


def test_malformed_yaml_raises_load_error(tmp_path: Path) -> None:
    makes = tmp_path / "makes"
    makes.mkdir()
    (makes / "broken.yaml").write_text("id: honda\n  bad indent: yes\n", encoding="utf-8")
    with pytest.raises(LoadError):
        YAMLLoader.read_all(tmp_path)


def test_missing_required_key_raises_load_error(tmp_path: Path) -> None:
    makes = tmp_path / "makes"
    makes.mkdir()
    (makes / "honda.yaml").write_text("id: honda\nname: Honda\n", encoding="utf-8")
    with pytest.raises(LoadError):
        YAMLLoader.read_all(tmp_path)


def test_invalid_availability_raises_load_error(tmp_path: Path) -> None:
    (tmp_path / "matrix").mkdir()
    (tmp_path / "matrix" / "x.yaml").write_text(
        "entries:\n"
        "  - trim_id: a\n"
        "    features:\n"
        "      - { feature_id: b, availability: someday }\n",
        encoding="utf-8",
    )
    with pytest.raises(LoadError):
        YAMLLoader.read_all(tmp_path)
