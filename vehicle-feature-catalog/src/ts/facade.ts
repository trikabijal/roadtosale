import { Feature, Make, Model, Trim, ValidationResult } from './entities.js';
import { NotFoundError } from './errors.js';
import { CatalogIndexes, buildIndexes } from './indexes.js';
import { YAMLLoader } from './loader.js';
import { validateCatalog } from './validator.js';

export class VehicleFeatureCatalog {
  private readonly idx: CatalogIndexes;

  private constructor(indexes: CatalogIndexes) {
    this.idx = indexes;
  }

  static async load(dataDir: string): Promise<VehicleFeatureCatalog> {
    const raw = await YAMLLoader.read_all(dataDir);
    const idx = buildIndexes(
      raw.makes,
      raw.models,
      raw.trims,
      raw.features,
      raw.trim_features,
    );
    return new VehicleFeatureCatalog(idx);
  }

  get_make(make_id: string): Make {
    const v = this.idx.makes_by_id.get(make_id);
    if (v === undefined) {
      throw new NotFoundError(`Make not found: '${make_id}'`);
    }
    return v;
  }

  get_model(model_id: string): Model {
    const v = this.idx.models_by_id.get(model_id);
    if (v === undefined) {
      throw new NotFoundError(`Model not found: '${model_id}'`);
    }
    return v;
  }

  get_trim(trim_id: string): Trim {
    const v = this.idx.trims_by_id.get(trim_id);
    if (v === undefined) {
      throw new NotFoundError(`Trim not found: '${trim_id}'`);
    }
    return v;
  }

  get_feature(feature_id: string): Feature {
    const v = this.idx.features_by_id.get(feature_id);
    if (v === undefined) {
      throw new NotFoundError(`Feature not found: '${feature_id}'`);
    }
    return v;
  }

  list_makes(): Make[] {
    return Array.from(this.idx.makes_by_id.values());
  }

  list_models(make_id?: string | null): Model[] {
    if (make_id == null) {
      return Array.from(this.idx.models_by_id.values());
    }
    return [...(this.idx.models_by_make.get(make_id) ?? [])];
  }

  list_trims(model_id?: string | null): Trim[] {
    if (model_id == null) {
      return Array.from(this.idx.trims_by_id.values());
    }
    return [...(this.idx.trims_by_model.get(model_id) ?? [])];
  }

  list_features(filters?: {
    brand_scope?: string | null;
    category?: string | null;
  }): Feature[] {
    const brandScope = filters?.brand_scope ?? null;
    const category = filters?.category ?? null;
    const out: Feature[] = [];
    for (const f of this.idx.features_by_id.values()) {
      if (brandScope != null && f.brand_scope !== brandScope) continue;
      if (category != null && f.category !== category) continue;
      out.push(f);
    }
    return out;
  }

  list_features_for_trim(
    trim_id: string,
    availability: ReadonlyArray<'standard' | 'optional'> = ['standard'],
  ): Feature[] {
    if (!this.idx.trims_by_id.has(trim_id)) {
      throw new NotFoundError(`Trim not found: '${trim_id}'`);
    }
    const wanted = new Set<string>(availability);
    const cells = this.idx.features_by_trim.get(trim_id) ?? [];
    const seen = new Set<string>();
    const out: Feature[] = [];
    for (const tf of cells) {
      if (!wanted.has(tf.availability)) continue;
      if (seen.has(tf.feature_id)) continue;
      const feat = this.idx.features_by_id.get(tf.feature_id);
      if (feat === undefined) continue;
      seen.add(tf.feature_id);
      out.push(feat);
    }
    return out;
  }

  list_trims_with_feature(feature_id: string): Trim[] {
    if (!this.idx.features_by_id.has(feature_id)) {
      throw new NotFoundError(`Feature not found: '${feature_id}'`);
    }
    const cells = this.idx.trims_by_feature.get(feature_id) ?? [];
    const seen = new Set<string>();
    const out: Trim[] = [];
    for (const tf of cells) {
      if (tf.availability === 'unavailable') continue;
      if (seen.has(tf.trim_id)) continue;
      const trim = this.idx.trims_by_id.get(tf.trim_id);
      if (trim === undefined) continue;
      seen.add(tf.trim_id);
      out.push(trim);
    }
    return out;
  }

  validate(): ValidationResult {
    const trimFeatures = [...this.idx.features_by_trim.values()].flat();
    return validateCatalog(
      Array.from(this.idx.makes_by_id.values()),
      Array.from(this.idx.models_by_id.values()),
      Array.from(this.idx.trims_by_id.values()),
      Array.from(this.idx.features_by_id.values()),
      trimFeatures,
    );
  }
}
