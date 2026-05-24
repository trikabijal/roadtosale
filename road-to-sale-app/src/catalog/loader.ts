/**
 * loader.ts — Runtime vehicle catalog loader
 *
 * Loads the pre-compiled bundle.json (produced by build-catalog.ts) once at
 * module initialisation and exposes indexed lookup helpers.
 *
 * If bundle.json is missing the import will throw — run build.sh first.
 */

// ─── Entity types (inlined — mirrors vehicle-feature-catalog/src/ts/entities.ts) ─

export interface Make {
  id: string;
  name: string;
  country: string;
}

export interface Model {
  id: string;
  make_id: string;
  name: string;
  year: number;
  body_style: string | null;
}

export interface Trim {
  id: string;
  model_id: string;
  name: string;
  msrp_range: [number, number] | null;
}

export interface Feature {
  id: string;
  display_name: string;
  category: string;
  brand_scope: string;
  cue_phrases: string[];
  synonyms: string[];
}

export type Availability = 'standard' | 'optional' | 'unavailable';

// ─── Bundle shape ─────────────────────────────────────────────────────────────

interface TrimFeatureCell {
  trim_id: string;
  feature_id: string;
  availability: Availability;
}

interface CatalogBundle {
  makes: Make[];
  models: Model[];
  trims: Trim[];
  features: Feature[];
  trimFeatures: TrimFeatureCell[];
  generatedAt: string;
}

// ─── Load bundle ──────────────────────────────────────────────────────────────

// eslint-disable-next-line @typescript-eslint/no-require-imports
const catalog = require('./bundle.json') as CatalogBundle;

// ─── Build indexes once at module init ────────────────────────────────────────

const makesById = new Map<string, Make>(catalog.makes.map(m => [m.id, m]));
const modelsById = new Map<string, Model>(catalog.models.map(m => [m.id, m]));
const trimsById = new Map<string, Trim>(catalog.trims.map(t => [t.id, t]));
const featuresById = new Map<string, Feature>(catalog.features.map(f => [f.id, f]));

const modelsByMake = new Map<string, Model[]>();
catalog.models.forEach(m => {
  const arr = modelsByMake.get(m.make_id) ?? [];
  arr.push(m);
  modelsByMake.set(m.make_id, arr);
});

const trimsByModel = new Map<string, Trim[]>();
catalog.trims.forEach(t => {
  const arr = trimsByModel.get(t.model_id) ?? [];
  arr.push(t);
  trimsByModel.set(t.model_id, arr);
});

const featuresByTrim = new Map<string, Array<{ feature_id: string; availability: Availability }>>();
catalog.trimFeatures.forEach(tf => {
  const arr = featuresByTrim.get(tf.trim_id) ?? [];
  arr.push({ feature_id: tf.feature_id, availability: tf.availability });
  featuresByTrim.set(tf.trim_id, arr);
});

// ─── Public API ───────────────────────────────────────────────────────────────

export const catalogLoader = {
  list_makes(): Make[] {
    return Array.from(makesById.values());
  },

  list_models(make_id?: string | null): Model[] {
    if (!make_id) return Array.from(modelsById.values());
    return modelsByMake.get(make_id) ?? [];
  },

  list_trims(model_id?: string | null): Trim[] {
    if (!model_id) return Array.from(trimsById.values());
    return trimsByModel.get(model_id) ?? [];
  },

  list_features_for_trim(trim_id: string, availability: Availability[] = ['standard']): Feature[] {
    const avail = new Set<string>(availability);
    const cells = featuresByTrim.get(trim_id) ?? [];
    return cells
      .filter(c => avail.has(c.availability))
      .map(c => featuresById.get(c.feature_id))
      .filter((f): f is Feature => f !== undefined);
  },

  get_make(id: string): Make {
    const m = makesById.get(id);
    if (!m) throw new Error(`Make not found: ${id}`);
    return m;
  },

  get_model(id: string): Model {
    const m = modelsById.get(id);
    if (!m) throw new Error(`Model not found: ${id}`);
    return m;
  },

  get_trim(id: string): Trim {
    const t = trimsById.get(id);
    if (!t) throw new Error(`Trim not found: ${id}`);
    return t;
  },

  get_feature(id: string): Feature {
    const f = featuresById.get(id);
    if (!f) throw new Error(`Feature not found: ${id}`);
    return f;
  },

  /** Metadata about when the bundle was compiled. */
  get generatedAt(): string {
    return catalog.generatedAt;
  },
};

export type CatalogLoader = typeof catalogLoader;
