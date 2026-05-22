"""Catalog integrity validator. Pure function over already-indexed state."""

from __future__ import annotations

from .entities import (
    Feature,
    Make,
    Model,
    Trim,
    TrimFeature,
    ValidationError,
    ValidationResult,
    ValidationWarning,
)


class Validator:
    @classmethod
    def check(
        cls,
        makes: list[Make],
        models: list[Model],
        trims: list[Trim],
        features: list[Feature],
        trim_features: list[TrimFeature],
    ) -> ValidationResult:
        errors: list[ValidationError] = []
        warnings: list[ValidationWarning] = []

        cls._check_duplicate_ids(makes, models, trims, features, errors)

        trim_ids = {t.id for t in trims}
        feature_ids = {f.id for f in features}

        for tf in trim_features:
            if tf.feature_id not in feature_ids:
                errors.append(
                    ValidationError(
                        code="matrix_unknown_feature",
                        message=(
                            f"TrimFeature references unknown feature_id "
                            f"{tf.feature_id!r} (trim {tf.trim_id})"
                        ),
                        entity_id=tf.feature_id,
                    )
                )
            if tf.trim_id not in trim_ids:
                errors.append(
                    ValidationError(
                        code="matrix_unknown_trim",
                        message=(
                            f"TrimFeature references unknown trim_id "
                            f"{tf.trim_id!r} (feature {tf.feature_id})"
                        ),
                        entity_id=tf.trim_id,
                    )
                )

        for f in features:
            if not f.cue_phrases:
                errors.append(
                    ValidationError(
                        code="feature_empty_cue_phrases",
                        message=f"Feature {f.id!r} has empty cue_phrases",
                        entity_id=f.id,
                    )
                )

        trims_with_any_feature = {tf.trim_id for tf in trim_features}
        for t in trims:
            if t.id not in trims_with_any_feature:
                errors.append(
                    ValidationError(
                        code="trim_has_no_features",
                        message=f"Trim {t.id!r} has zero TrimFeature entries",
                        entity_id=t.id,
                    )
                )

        model_ids = {m.id for m in models}
        make_ids = {m.id for m in makes}
        for mo in models:
            if mo.make_id not in make_ids:
                warnings.append(
                    ValidationWarning(
                        code="model_unknown_make",
                        message=f"Model {mo.id!r} references unknown make_id {mo.make_id!r}",
                        entity_id=mo.id,
                    )
                )
        for t in trims:
            if t.model_id not in model_ids:
                warnings.append(
                    ValidationWarning(
                        code="trim_unknown_model",
                        message=f"Trim {t.id!r} references unknown model_id {t.model_id!r}",
                        entity_id=t.id,
                    )
                )

        return ValidationResult(
            is_valid=not errors,
            errors=errors,
            warnings=warnings,
        )

    @staticmethod
    def _check_duplicate_ids(
        makes: list[Make],
        models: list[Model],
        trims: list[Trim],
        features: list[Feature],
        errors: list[ValidationError],
    ) -> None:
        seen: dict[str, str] = {}
        for kind, items in (
            ("Make", makes),
            ("Model", models),
            ("Trim", trims),
            ("Feature", features),
        ):
            for item in items:
                if item.id in seen:
                    errors.append(
                        ValidationError(
                            code="duplicate_id",
                            message=(
                                f"ID {item.id!r} claimed by both "
                                f"{seen[item.id]} and {kind}"
                            ),
                            entity_id=item.id,
                        )
                    )
                else:
                    seen[item.id] = kind
