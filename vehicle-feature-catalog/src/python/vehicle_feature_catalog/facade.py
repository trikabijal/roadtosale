"""Public facade for the vehicle feature catalog.

This is the only file external code should import from (via the package
`__init__.py` re-export).
"""

from __future__ import annotations

from pathlib import Path
from typing import Iterable

from .entities import Feature, Make, Model, Trim, ValidationResult
from .errors import NotFoundError
from .indexes import CatalogIndexes
from .loader import YAMLLoader
from .validator import Validator


class VehicleFeatureCatalog:
    """Read-only in-memory catalog of Makes, Models, Trims, Features, and the matrix."""

    def __init__(self, indexes: CatalogIndexes) -> None:
        self._idx = indexes

    @classmethod
    def load(cls, data_dir: str | Path) -> "VehicleFeatureCatalog":
        path = Path(data_dir)
        raw = YAMLLoader.read_all(path)
        idx = CatalogIndexes.build(
            makes=raw.makes,
            models=raw.models,
            trims=raw.trims,
            features=raw.features,
            trim_features=raw.trim_features,
        )
        return cls(idx)

    def get_make(self, make_id: str) -> Make:
        try:
            return self._idx.makes_by_id[make_id]
        except KeyError:
            raise NotFoundError(f"Make not found: {make_id!r}") from None

    def get_model(self, model_id: str) -> Model:
        try:
            return self._idx.models_by_id[model_id]
        except KeyError:
            raise NotFoundError(f"Model not found: {model_id!r}") from None

    def get_trim(self, trim_id: str) -> Trim:
        try:
            return self._idx.trims_by_id[trim_id]
        except KeyError:
            raise NotFoundError(f"Trim not found: {trim_id!r}") from None

    def get_feature(self, feature_id: str) -> Feature:
        try:
            return self._idx.features_by_id[feature_id]
        except KeyError:
            raise NotFoundError(f"Feature not found: {feature_id!r}") from None

    def list_makes(self) -> list[Make]:
        return list(self._idx.makes_by_id.values())

    def list_models(self, make_id: str | None = None) -> list[Model]:
        if make_id is None:
            return list(self._idx.models_by_id.values())
        return list(self._idx.models_by_make.get(make_id, []))

    def list_trims(self, model_id: str | None = None) -> list[Trim]:
        if model_id is None:
            return list(self._idx.trims_by_id.values())
        return list(self._idx.trims_by_model.get(model_id, []))

    def list_features(
        self,
        brand_scope: str | None = None,
        category: str | None = None,
    ) -> list[Feature]:
        out: list[Feature] = []
        for f in self._idx.features_by_id.values():
            if brand_scope is not None and f.brand_scope != brand_scope:
                continue
            if category is not None and f.category != category:
                continue
            out.append(f)
        return out

    def list_features_for_trim(
        self,
        trim_id: str,
        availability: Iterable[str] = ("standard",),
    ) -> list[Feature]:
        if trim_id not in self._idx.trims_by_id:
            raise NotFoundError(f"Trim not found: {trim_id!r}")
        wanted = set(availability)
        out: list[Feature] = []
        seen_feature_ids: set[str] = set()
        for tf in self._idx.features_by_trim.get(trim_id, []):
            if tf.availability not in wanted:
                continue
            if tf.feature_id in seen_feature_ids:
                continue
            feat = self._idx.features_by_id.get(tf.feature_id)
            if feat is None:
                continue
            seen_feature_ids.add(tf.feature_id)
            out.append(feat)
        return out

    def list_trims_with_feature(self, feature_id: str) -> list[Trim]:
        if feature_id not in self._idx.features_by_id:
            raise NotFoundError(f"Feature not found: {feature_id!r}")
        out: list[Trim] = []
        seen_trim_ids: set[str] = set()
        for tf in self._idx.trims_by_feature.get(feature_id, []):
            if tf.availability == "unavailable":
                continue
            if tf.trim_id in seen_trim_ids:
                continue
            trim = self._idx.trims_by_id.get(tf.trim_id)
            if trim is None:
                continue
            seen_trim_ids.add(tf.trim_id)
            out.append(trim)
        return out

    def validate(self) -> ValidationResult:
        trim_features = [
            tf for cells in self._idx.features_by_trim.values() for tf in cells
        ]
        return Validator.check(
            makes=list(self._idx.makes_by_id.values()),
            models=list(self._idx.models_by_id.values()),
            trims=list(self._idx.trims_by_id.values()),
            features=list(self._idx.features_by_id.values()),
            trim_features=trim_features,
        )
