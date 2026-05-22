import {
  Feature,
  Make,
  Model,
  Trim,
  TrimFeature,
  ValidationError,
  ValidationResult,
  ValidationWarning,
} from './entities.js';

export function validateCatalog(
  makes: Make[],
  models: Model[],
  trims: Trim[],
  features: Feature[],
  trimFeatures: TrimFeature[],
): ValidationResult {
  const errors: ValidationError[] = [];
  const warnings: ValidationWarning[] = [];

  checkDuplicateIds(makes, models, trims, features, errors);

  const trimIds = new Set(trims.map((t) => t.id));
  const featureIds = new Set(features.map((f) => f.id));

  for (const tf of trimFeatures) {
    if (!featureIds.has(tf.feature_id)) {
      errors.push({
        code: 'matrix_unknown_feature',
        message:
          `TrimFeature references unknown feature_id '${tf.feature_id}' ` +
          `(trim ${tf.trim_id})`,
        entity_id: tf.feature_id,
      });
    }
    if (!trimIds.has(tf.trim_id)) {
      errors.push({
        code: 'matrix_unknown_trim',
        message:
          `TrimFeature references unknown trim_id '${tf.trim_id}' ` +
          `(feature ${tf.feature_id})`,
        entity_id: tf.trim_id,
      });
    }
  }

  for (const f of features) {
    if (f.cue_phrases.length === 0) {
      errors.push({
        code: 'feature_empty_cue_phrases',
        message: `Feature '${f.id}' has empty cue_phrases`,
        entity_id: f.id,
      });
    }
  }

  const trimsWithAnyFeature = new Set(trimFeatures.map((tf) => tf.trim_id));
  for (const t of trims) {
    if (!trimsWithAnyFeature.has(t.id)) {
      errors.push({
        code: 'trim_has_no_features',
        message: `Trim '${t.id}' has zero TrimFeature entries`,
        entity_id: t.id,
      });
    }
  }

  const modelIds = new Set(models.map((m) => m.id));
  const makeIds = new Set(makes.map((m) => m.id));
  for (const mo of models) {
    if (!makeIds.has(mo.make_id)) {
      warnings.push({
        code: 'model_unknown_make',
        message: `Model '${mo.id}' references unknown make_id '${mo.make_id}'`,
        entity_id: mo.id,
      });
    }
  }
  for (const t of trims) {
    if (!modelIds.has(t.model_id)) {
      warnings.push({
        code: 'trim_unknown_model',
        message: `Trim '${t.id}' references unknown model_id '${t.model_id}'`,
        entity_id: t.id,
      });
    }
  }

  return {
    is_valid: errors.length === 0,
    errors,
    warnings,
    format_errors() {
      if (this.is_valid && this.errors.length === 0) {
        return 'Catalog is valid.';
      }
      const lines = [`Catalog validation failed with ${this.errors.length} error(s):`];
      for (const err of this.errors) {
        const entity = err.entity_id ? ` (${err.entity_id})` : '';
        lines.push(`  [${err.code}]${entity} ${err.message}`);
      }
      if (this.warnings.length > 0) {
        lines.push(`Warnings (${this.warnings.length}):`);
        for (const w of this.warnings) {
          const entity = w.entity_id ? ` (${w.entity_id})` : '';
          lines.push(`  [${w.code}]${entity} ${w.message}`);
        }
      }
      return lines.join('\n');
    },
  };
}

function checkDuplicateIds(
  makes: Make[],
  models: Model[],
  trims: Trim[],
  features: Feature[],
  errors: ValidationError[],
): void {
  const seen = new Map<string, string>();
  const buckets: Array<[string, Array<{ id: string }>]> = [
    ['Make', makes],
    ['Model', models],
    ['Trim', trims],
    ['Feature', features],
  ];
  for (const [kind, items] of buckets) {
    for (const item of items) {
      const prior = seen.get(item.id);
      if (prior !== undefined) {
        errors.push({
          code: 'duplicate_id',
          message: `ID '${item.id}' claimed by both ${prior} and ${kind}`,
          entity_id: item.id,
        });
      } else {
        seen.set(item.id, kind);
      }
    }
  }
}
