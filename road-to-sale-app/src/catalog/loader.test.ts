/**
 * loader.test.ts — Catalog loader integration tests
 *
 * Tests run against the real bundle.json (no mocks). This verifies both the
 * loader API contract and that the compiled bundle was produced correctly.
 *
 * Real IDs sourced from bundle.json (as of 2026-05-25):
 *   Make:   "honda"
 *   Models: "honda.accord", "honda.civic"
 *   Trim:   "honda.civic.2026.lx"            (only unavailable features)
 *           "honda.cr-v-hybrid-awd.2026.sport" (7 standard features)
 */

import { catalogLoader } from './loader';
import type { Make, Model, Trim, Feature } from './loader';

// ─── list_makes ───────────────────────────────────────────────────────────────

describe('list_makes', () => {
  it('returns an array with at least one entry', () => {
    const makes = catalogLoader.list_makes();
    expect(Array.isArray(makes)).toBe(true);
    expect(makes.length).toBeGreaterThanOrEqual(1);
  });

  it('includes Honda in the makes list', () => {
    const makes = catalogLoader.list_makes();
    const honda = makes.find((m: Make) => m.id === 'honda');
    expect(honda).toBeDefined();
    expect(honda!.name).toBe('Honda');
  });

  it('each make has id and name', () => {
    const makes = catalogLoader.list_makes();
    for (const make of makes) {
      expect(typeof make.id).toBe('string');
      expect(make.id.length).toBeGreaterThan(0);
      expect(typeof make.name).toBe('string');
      expect(make.name.length).toBeGreaterThan(0);
    }
  });
});

// ─── list_models ──────────────────────────────────────────────────────────────

describe('list_models', () => {
  it('returns all models when called with no argument', () => {
    const models = catalogLoader.list_models();
    expect(Array.isArray(models)).toBe(true);
    expect(models.length).toBeGreaterThanOrEqual(5);
  });

  it('returns only Honda models when filtered by honda make_id', () => {
    const hondaModels = catalogLoader.list_models('honda');
    expect(hondaModels.length).toBeGreaterThan(0);
    for (const model of hondaModels) {
      expect(model.make_id).toBe('honda');
    }
  });

  it('each model has year as a number', () => {
    const models = catalogLoader.list_models();
    for (const model of models) {
      expect(typeof model.year).toBe('number');
    }
  });

  it('honda.accord model exists and has expected fields', () => {
    const models = catalogLoader.list_models('honda');
    const accord = models.find((m: Model) => m.id === 'honda.accord');
    expect(accord).toBeDefined();
    expect(accord!.make_id).toBe('honda');
    expect(accord!.name).toBe('Accord');
    expect(accord!.year).toBe(2026);
  });

  it('honda.civic model exists', () => {
    const models = catalogLoader.list_models('honda');
    const civic = models.find((m: Model) => m.id === 'honda.civic');
    expect(civic).toBeDefined();
    expect(civic!.name).toBe('Civic');
  });

  it('returns empty array for an unknown make_id', () => {
    const result = catalogLoader.list_models('unknown-make-xyz');
    expect(Array.isArray(result)).toBe(true);
    expect(result.length).toBe(0);
  });
});

// ─── list_trims ───────────────────────────────────────────────────────────────

describe('list_trims', () => {
  it('returns all trims when called with no argument', () => {
    const trims = catalogLoader.list_trims();
    expect(Array.isArray(trims)).toBe(true);
    expect(trims.length).toBeGreaterThanOrEqual(10);
  });

  it('returns trims for honda.civic filtered by model_id', () => {
    const trims = catalogLoader.list_trims('honda.civic');
    expect(trims.length).toBeGreaterThan(0);
    for (const trim of trims) {
      expect(trim.model_id).toBe('honda.civic');
    }
  });

  it('each trim has id, model_id, name — but no year field', () => {
    const trims = catalogLoader.list_trims('honda.civic');
    for (const trim of trims) {
      expect(typeof trim.id).toBe('string');
      expect(typeof trim.model_id).toBe('string');
      expect(typeof trim.name).toBe('string');
      // Trim does NOT carry a year field (year lives on Model)
      expect((trim as unknown as Record<string, unknown>)['year']).toBeUndefined();
    }
  });

  it('honda.civic.2026.lx trim exists with correct model_id', () => {
    const trims = catalogLoader.list_trims('honda.civic');
    const lx = trims.find((t: Trim) => t.id === 'honda.civic.2026.lx');
    expect(lx).toBeDefined();
    expect(lx!.model_id).toBe('honda.civic');
    expect(lx!.name).toBe('LX');
  });

  it('returns empty array for an unknown model_id', () => {
    const result = catalogLoader.list_trims('unknown-model-xyz');
    expect(Array.isArray(result)).toBe(true);
    expect(result.length).toBe(0);
  });
});

// ─── get_make ─────────────────────────────────────────────────────────────────

describe('get_make', () => {
  it('returns the Honda make for id "honda"', () => {
    const make = catalogLoader.get_make('honda');
    expect(make).toBeDefined();
    expect(make.id).toBe('honda');
    expect(make.name).toBe('Honda');
  });

  it('throws for an unknown make id', () => {
    expect(() => catalogLoader.get_make('unknown-make-xyz')).toThrow();
  });
});

// ─── get_model ────────────────────────────────────────────────────────────────

describe('get_model', () => {
  it('returns the correct model for honda.accord', () => {
    const model = catalogLoader.get_model('honda.accord');
    expect(model.id).toBe('honda.accord');
    expect(model.make_id).toBe('honda');
    expect(model.name).toBe('Accord');
    expect(model.year).toBe(2026);
  });

  it('throws for an unknown model id', () => {
    expect(() => catalogLoader.get_model('unknown-model-xyz')).toThrow();
  });
});

// ─── get_trim ─────────────────────────────────────────────────────────────────

describe('get_trim', () => {
  it('returns the correct trim for honda.civic.2026.lx', () => {
    const trim = catalogLoader.get_trim('honda.civic.2026.lx');
    expect(trim.id).toBe('honda.civic.2026.lx');
    expect(trim.model_id).toBe('honda.civic');
    expect(trim.name).toBe('LX');
  });

  it('throws for an unknown trim id', () => {
    expect(() => catalogLoader.get_trim('unknown-trim-xyz')).toThrow();
  });
});

// ─── get_feature ──────────────────────────────────────────────────────────────

describe('get_feature', () => {
  it('returns the correct feature for a known feature id', () => {
    const feature = catalogLoader.get_feature('honda.feature.honda_sensing');
    expect(feature.id).toBe('honda.feature.honda_sensing');
    expect(typeof feature.display_name).toBe('string');
    expect(feature.display_name.length).toBeGreaterThan(0);
  });

  it('throws for an unknown feature id', () => {
    expect(() => catalogLoader.get_feature('unknown-feature-xyz')).toThrow();
  });
});

// ─── list_features_for_trim ───────────────────────────────────────────────────

describe('list_features_for_trim', () => {
  // honda.cr-v-hybrid-awd.2026.sport has 7 standard features in the bundle
  const CRV_SPORT_TRIM_ID = 'honda.cr-v-hybrid-awd.2026.sport';

  it('returns an array of features for a known trim', () => {
    const features = catalogLoader.list_features_for_trim(CRV_SPORT_TRIM_ID);
    expect(Array.isArray(features)).toBe(true);
    expect(features.length).toBeGreaterThan(0);
  });

  it('each feature has id, display_name, category, brand_scope', () => {
    const features = catalogLoader.list_features_for_trim(CRV_SPORT_TRIM_ID);
    for (const feature of features) {
      expect(typeof feature.id).toBe('string');
      expect(feature.id.length).toBeGreaterThan(0);
      expect(typeof feature.display_name).toBe('string');
      expect(feature.display_name.length).toBeGreaterThan(0);
      expect(typeof feature.category).toBe('string');
      expect(typeof feature.brand_scope).toBe('string');
    }
  });

  it('default call returns only standard-availability features', () => {
    // The default availability filter is ['standard']; all 7 features for this
    // trim are standard so the result should be 7.
    const features = catalogLoader.list_features_for_trim(CRV_SPORT_TRIM_ID);
    expect(features.length).toBe(7);
  });

  it('explicit ["standard"] filter returns the same set as the default', () => {
    const defaultFeatures = catalogLoader.list_features_for_trim(CRV_SPORT_TRIM_ID);
    const explicitFeatures = catalogLoader.list_features_for_trim(CRV_SPORT_TRIM_ID, ['standard']);
    expect(explicitFeatures.length).toBe(defaultFeatures.length);
  });

  it('honda_sensing is among the standard features for CR-V Sport', () => {
    const features = catalogLoader.list_features_for_trim(CRV_SPORT_TRIM_ID, ['standard']);
    const sensing = features.find((f: Feature) => f.id === 'honda.feature.honda_sensing');
    expect(sensing).toBeDefined();
  });

  it('returns empty array for a trim with no matching availability', () => {
    // honda.civic.2026.lx has only "unavailable" cells; requesting standard yields []
    const features = catalogLoader.list_features_for_trim('honda.civic.2026.lx', ['standard']);
    expect(Array.isArray(features)).toBe(true);
    expect(features.length).toBe(0);
  });

  it('returns empty array for an unknown trim id', () => {
    const features = catalogLoader.list_features_for_trim('unknown-trim-xyz');
    expect(Array.isArray(features)).toBe(true);
    expect(features.length).toBe(0);
  });
});

// ─── Bundle integrity ─────────────────────────────────────────────────────────

describe('Bundle integrity', () => {
  it('has at least 1 make', () => {
    expect(catalogLoader.list_makes().length).toBeGreaterThanOrEqual(1);
  });

  it('has at least 5 models', () => {
    expect(catalogLoader.list_models().length).toBeGreaterThanOrEqual(5);
  });

  it('has at least 10 trims', () => {
    expect(catalogLoader.list_trims().length).toBeGreaterThanOrEqual(10);
  });

  it('has both Honda and Toyota makes', () => {
    const makes = catalogLoader.list_makes();
    const ids = makes.map((m: Make) => m.id);
    expect(ids).toContain('honda');
    expect(ids).toContain('toyota');
  });

  it('generatedAt is a non-empty string', () => {
    expect(typeof catalogLoader.generatedAt).toBe('string');
    expect(catalogLoader.generatedAt.length).toBeGreaterThan(0);
  });
});
