import { Feature, Make, Model, Trim, TrimFeature } from './entities.js';
import { DuplicateIdError } from './errors.js';

export interface CatalogIndexes {
  makes_by_id: Map<string, Make>;
  models_by_id: Map<string, Model>;
  trims_by_id: Map<string, Trim>;
  features_by_id: Map<string, Feature>;
  features_by_trim: Map<string, TrimFeature[]>;
  trims_by_feature: Map<string, TrimFeature[]>;
  models_by_make: Map<string, Model[]>;
  trims_by_model: Map<string, Trim[]>;
}

export function buildIndexes(
  makes: Make[],
  models: Model[],
  trims: Trim[],
  features: Feature[],
  trimFeatures: TrimFeature[],
): CatalogIndexes {
  const idx: CatalogIndexes = {
    makes_by_id: new Map(),
    models_by_id: new Map(),
    trims_by_id: new Map(),
    features_by_id: new Map(),
    features_by_trim: new Map(),
    trims_by_feature: new Map(),
    models_by_make: new Map(),
    trims_by_model: new Map(),
  };

  for (const m of makes) {
    if (idx.makes_by_id.has(m.id)) {
      throw new DuplicateIdError(`Duplicate Make id: ${m.id}`);
    }
    idx.makes_by_id.set(m.id, m);
  }

  for (const mo of models) {
    if (idx.models_by_id.has(mo.id)) {
      throw new DuplicateIdError(`Duplicate Model id: ${mo.id}`);
    }
    idx.models_by_id.set(mo.id, mo);
    push(idx.models_by_make, mo.make_id, mo);
  }

  for (const t of trims) {
    if (idx.trims_by_id.has(t.id)) {
      throw new DuplicateIdError(`Duplicate Trim id: ${t.id}`);
    }
    idx.trims_by_id.set(t.id, t);
    push(idx.trims_by_model, t.model_id, t);
  }

  for (const f of features) {
    if (idx.features_by_id.has(f.id)) {
      throw new DuplicateIdError(`Duplicate Feature id: ${f.id}`);
    }
    idx.features_by_id.set(f.id, f);
  }

  checkCrossEntityCollisions(idx);

  for (const tf of trimFeatures) {
    push(idx.features_by_trim, tf.trim_id, tf);
    push(idx.trims_by_feature, tf.feature_id, tf);
  }

  return idx;
}

function push<K, V>(map: Map<K, V[]>, key: K, value: V): void {
  const list = map.get(key);
  if (list === undefined) {
    map.set(key, [value]);
  } else {
    list.push(value);
  }
}

function checkCrossEntityCollisions(idx: CatalogIndexes): void {
  const seen = new Map<string, string>();
  const buckets: Array<[string, Map<string, unknown>]> = [
    ['Make', idx.makes_by_id],
    ['Model', idx.models_by_id],
    ['Trim', idx.trims_by_id],
    ['Feature', idx.features_by_id],
  ];
  for (const [kind, bucket] of buckets) {
    for (const id of bucket.keys()) {
      const prior = seen.get(id);
      if (prior !== undefined) {
        throw new DuplicateIdError(
          `ID ${JSON.stringify(id)} claimed by both ${prior} and ${kind}`,
        );
      }
      seen.set(id, kind);
    }
  }
}
