"""YAML loader.

Walks `data_dir` and produces lists of parsed entities. Pure I/O + parsing.
Validation, indexing, and de-duplication are downstream concerns.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

from .entities import Feature, Make, Model, Trim, TrimFeature
from .errors import LoadError


@dataclass
class RawCatalog:
    makes: list[Make]
    models: list[Model]
    trims: list[Trim]
    features: list[Feature]
    trim_features: list[TrimFeature]


class YAMLLoader:
    @classmethod
    def read_all(cls, data_dir: Path) -> RawCatalog:
        if not data_dir.exists():
            raise LoadError(f"data_dir does not exist: {data_dir}")
        if not data_dir.is_dir():
            raise LoadError(f"data_dir is not a directory: {data_dir}")

        makes = cls._load_makes(data_dir / "makes")
        models = cls._load_models(data_dir / "models")
        trims = cls._load_trims(data_dir / "trims")
        features = cls._load_features(data_dir / "features")
        trim_features = cls._load_matrix(data_dir / "matrix")

        return RawCatalog(
            makes=makes,
            models=models,
            trims=trims,
            features=features,
            trim_features=trim_features,
        )

    @classmethod
    def _load_makes(cls, makes_dir: Path) -> list[Make]:
        out: list[Make] = []
        for path in cls._iter_yaml_files(makes_dir):
            data = cls._read_yaml(path)
            cls._require_keys(path, data, ["id", "name", "country"])
            out.append(
                Make(
                    id=str(data["id"]),
                    name=str(data["name"]),
                    country=str(data["country"]),
                )
            )
        return out

    @classmethod
    def _load_models(cls, models_dir: Path) -> list[Model]:
        out: list[Model] = []
        for path in cls._iter_yaml_files(models_dir):
            data = cls._read_yaml(path)
            cls._require_keys(path, data, ["id", "make_id", "name", "year"])
            body_style = data.get("body_style")
            out.append(
                Model(
                    id=str(data["id"]),
                    make_id=str(data["make_id"]),
                    name=str(data["name"]),
                    year=int(data["year"]),
                    body_style=str(body_style) if body_style is not None else None,
                )
            )
        return out

    @classmethod
    def _load_trims(cls, trims_dir: Path) -> list[Trim]:
        out: list[Trim] = []
        for path in cls._iter_yaml_files(trims_dir):
            data = cls._read_yaml(path)
            cls._require_keys(path, data, ["id", "model_id", "name"])
            msrp_range_raw = data.get("msrp_range")
            msrp_range: tuple[int, int] | None
            if msrp_range_raw is None:
                msrp_range = None
            else:
                if not (isinstance(msrp_range_raw, (list, tuple)) and len(msrp_range_raw) == 2):
                    raise LoadError(
                        f"{path}: msrp_range must be a 2-element list, got {msrp_range_raw!r}"
                    )
                msrp_range = (int(msrp_range_raw[0]), int(msrp_range_raw[1]))
            out.append(
                Trim(
                    id=str(data["id"]),
                    model_id=str(data["model_id"]),
                    name=str(data["name"]),
                    msrp_range=msrp_range,
                )
            )
        return out

    @classmethod
    def _load_features(cls, features_dir: Path) -> list[Feature]:
        out: list[Feature] = []
        for path in cls._iter_yaml_files(features_dir):
            data = cls._read_yaml(path)
            cls._require_keys(
                path,
                data,
                ["id", "display_name", "category", "brand_scope"],
            )
            cue_phrases = data.get("cue_phrases") or []
            synonyms = data.get("synonyms") or []
            if not isinstance(cue_phrases, list):
                raise LoadError(f"{path}: cue_phrases must be a list")
            if not isinstance(synonyms, list):
                raise LoadError(f"{path}: synonyms must be a list")
            out.append(
                Feature(
                    id=str(data["id"]),
                    display_name=str(data["display_name"]),
                    category=str(data["category"]),
                    brand_scope=str(data["brand_scope"]),
                    cue_phrases=[str(p) for p in cue_phrases],
                    synonyms=[str(s) for s in synonyms],
                )
            )
        return out

    @classmethod
    def _load_matrix(cls, matrix_dir: Path) -> list[TrimFeature]:
        out: list[TrimFeature] = []
        if not matrix_dir.exists():
            return out
        for path in cls._iter_yaml_files(matrix_dir):
            data = cls._read_yaml(path)
            entries = data.get("entries")
            if entries is None:
                raise LoadError(f"{path}: matrix file missing top-level 'entries' key")
            if not isinstance(entries, list):
                raise LoadError(f"{path}: 'entries' must be a list")
            for entry in entries:
                if not isinstance(entry, dict):
                    raise LoadError(f"{path}: matrix entry must be a mapping")
                trim_id = entry.get("trim_id")
                features = entry.get("features") or []
                if not trim_id:
                    raise LoadError(f"{path}: matrix entry missing trim_id")
                if not isinstance(features, list):
                    raise LoadError(f"{path}: features in entry must be a list")
                for cell in features:
                    if not isinstance(cell, dict):
                        raise LoadError(
                            f"{path}: feature cell must be a mapping, got {cell!r}"
                        )
                    feature_id = cell.get("feature_id")
                    availability = cell.get("availability")
                    if not feature_id:
                        raise LoadError(
                            f"{path}: feature cell missing feature_id under trim {trim_id}"
                        )
                    if availability not in ("standard", "optional", "unavailable"):
                        raise LoadError(
                            f"{path}: invalid availability {availability!r} for "
                            f"{trim_id} / {feature_id}"
                        )
                    out.append(
                        TrimFeature(
                            trim_id=str(trim_id),
                            feature_id=str(feature_id),
                            availability=availability,
                        )
                    )
        return out

    @staticmethod
    def _iter_yaml_files(root: Path) -> list[Path]:
        if not root.exists():
            return []
        return sorted(p for p in root.rglob("*.yaml") if p.is_file())

    @staticmethod
    def _read_yaml(path: Path) -> dict[str, Any]:
        try:
            with path.open("r", encoding="utf-8") as fh:
                data = yaml.safe_load(fh)
        except yaml.YAMLError as exc:
            raise LoadError(f"YAML parse error in {path}: {exc}") from exc
        except OSError as exc:
            raise LoadError(f"Failed to read {path}: {exc}") from exc
        if data is None:
            raise LoadError(f"{path}: file is empty")
        if not isinstance(data, dict):
            raise LoadError(f"{path}: top-level YAML must be a mapping, got {type(data).__name__}")
        return data

    @staticmethod
    def _require_keys(path: Path, data: dict[str, Any], keys: list[str]) -> None:
        missing = [k for k in keys if k not in data]
        if missing:
            raise LoadError(f"{path}: missing required key(s): {', '.join(missing)}")
