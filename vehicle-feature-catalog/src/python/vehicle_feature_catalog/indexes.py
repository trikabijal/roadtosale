"""In-memory indexes built once after load. Pure dict lookups thereafter."""

from __future__ import annotations

from dataclasses import dataclass, field

from .entities import Feature, Make, Model, Trim, TrimFeature
from .errors import DuplicateIdError


@dataclass
class CatalogIndexes:
    makes_by_id: dict[str, Make] = field(default_factory=dict)
    models_by_id: dict[str, Model] = field(default_factory=dict)
    trims_by_id: dict[str, Trim] = field(default_factory=dict)
    features_by_id: dict[str, Feature] = field(default_factory=dict)
    features_by_trim: dict[str, list[TrimFeature]] = field(default_factory=dict)
    trims_by_feature: dict[str, list[TrimFeature]] = field(default_factory=dict)
    models_by_make: dict[str, list[Model]] = field(default_factory=dict)
    trims_by_model: dict[str, list[Trim]] = field(default_factory=dict)

    @classmethod
    def build(
        cls,
        makes: list[Make],
        models: list[Model],
        trims: list[Trim],
        features: list[Feature],
        trim_features: list[TrimFeature],
    ) -> "CatalogIndexes":
        idx = cls()

        for m in makes:
            if m.id in idx.makes_by_id:
                raise DuplicateIdError(f"Duplicate Make id: {m.id}")
            idx.makes_by_id[m.id] = m

        for mo in models:
            if mo.id in idx.models_by_id:
                raise DuplicateIdError(f"Duplicate Model id: {mo.id}")
            idx.models_by_id[mo.id] = mo
            idx.models_by_make.setdefault(mo.make_id, []).append(mo)

        for t in trims:
            if t.id in idx.trims_by_id:
                raise DuplicateIdError(f"Duplicate Trim id: {t.id}")
            idx.trims_by_id[t.id] = t
            idx.trims_by_model.setdefault(t.model_id, []).append(t)

        for f in features:
            if f.id in idx.features_by_id:
                raise DuplicateIdError(f"Duplicate Feature id: {f.id}")
            idx.features_by_id[f.id] = f

        cls._check_cross_entity_id_collisions(idx)

        for tf in trim_features:
            idx.features_by_trim.setdefault(tf.trim_id, []).append(tf)
            idx.trims_by_feature.setdefault(tf.feature_id, []).append(tf)

        return idx

    @staticmethod
    def _check_cross_entity_id_collisions(idx: "CatalogIndexes") -> None:
        seen: dict[str, str] = {}
        for kind, bucket in (
            ("Make", idx.makes_by_id),
            ("Model", idx.models_by_id),
            ("Trim", idx.trims_by_id),
            ("Feature", idx.features_by_id),
        ):
            for entity_id in bucket:
                if entity_id in seen:
                    raise DuplicateIdError(
                        f"ID {entity_id!r} claimed by both {seen[entity_id]} and {kind}"
                    )
                seen[entity_id] = kind
