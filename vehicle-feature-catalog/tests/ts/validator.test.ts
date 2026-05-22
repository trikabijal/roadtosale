import { describe, expect, it } from 'vitest';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';

import { VehicleFeatureCatalog } from '../../src/ts/index.js';
import { validateCatalog } from '../../src/ts/validator.js';
import { Feature, Make, Model, Trim, TrimFeature } from '../../src/ts/entities.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const FIXTURES = path.resolve(__dirname, '..', 'fixtures');
const INVALID = path.resolve(__dirname, '..', 'fixtures-invalid');

describe('validator', () => {
  it('invalid fixture surfaces matrix + cue + orphan-trim errors', async () => {
    const catalog = await VehicleFeatureCatalog.load(INVALID);
    const result = catalog.validate();
    expect(result.is_valid).toBe(false);
    const codes = new Set(result.errors.map((e) => e.code));
    expect(codes.has('matrix_unknown_feature')).toBe(true);
    expect(codes.has('matrix_unknown_trim')).toBe(true);
    expect(codes.has('feature_empty_cue_phrases')).toBe(true);
    expect(codes.has('trim_has_no_features')).toBe(true);
  });

  it('format_errors prints each finding', async () => {
    const catalog = await VehicleFeatureCatalog.load(INVALID);
    const result = catalog.validate();
    const out = result.format_errors();
    expect(out).toContain('matrix_unknown_feature');
    expect(out).toContain('trim_has_no_features');
  });

  it('reports duplicate ids', () => {
    const makes: Make[] = [{ id: 'honda', name: 'Honda', country: 'JP' }];
    const models: Model[] = [
      {
        id: 'honda.civic',
        make_id: 'honda',
        name: 'Civic',
        year: 2026,
        body_style: null,
      },
      {
        id: 'honda.civic',
        make_id: 'honda',
        name: 'Civic Dup',
        year: 2026,
        body_style: null,
      },
    ];
    const trims: Trim[] = [
      {
        id: 'honda.civic.2026.sport',
        model_id: 'honda.civic',
        name: 'Sport',
        msrp_range: null,
      },
    ];
    const features: Feature[] = [
      {
        id: 'universal.feature.x',
        display_name: 'X',
        category: 'c',
        brand_scope: 'universal',
        cue_phrases: ['x'],
        synonyms: [],
      },
    ];
    const matrix: TrimFeature[] = [
      {
        trim_id: 'honda.civic.2026.sport',
        feature_id: 'universal.feature.x',
        availability: 'standard',
      },
    ];

    const result = validateCatalog(makes, models, trims, features, matrix);
    expect(result.is_valid).toBe(false);
    expect(result.errors.some((e) => e.code === 'duplicate_id')).toBe(true);
  });

  it('happy fixture validates', async () => {
    const catalog = await VehicleFeatureCatalog.load(FIXTURES);
    const result = catalog.validate();
    expect(result.is_valid).toBe(true);
  });

  it('empty cue_phrases is a standalone error', () => {
    const makes: Make[] = [{ id: 'honda', name: 'Honda', country: 'JP' }];
    const models: Model[] = [
      {
        id: 'honda.civic',
        make_id: 'honda',
        name: 'Civic',
        year: 2026,
        body_style: null,
      },
    ];
    const trims: Trim[] = [
      {
        id: 'honda.civic.2026.sport',
        model_id: 'honda.civic',
        name: 'Sport',
        msrp_range: null,
      },
    ];
    const features: Feature[] = [
      {
        id: 'universal.feature.empty',
        display_name: 'Empty',
        category: 'comfort',
        brand_scope: 'universal',
        cue_phrases: [],
        synonyms: [],
      },
    ];
    const matrix: TrimFeature[] = [
      {
        trim_id: 'honda.civic.2026.sport',
        feature_id: 'universal.feature.empty',
        availability: 'standard',
      },
    ];
    const result = validateCatalog(makes, models, trims, features, matrix);
    expect(result.is_valid).toBe(false);
    expect(result.errors.some((e) => e.code === 'feature_empty_cue_phrases')).toBe(true);
  });

  it('trim without features is an error', () => {
    const makes: Make[] = [{ id: 'honda', name: 'Honda', country: 'JP' }];
    const models: Model[] = [
      {
        id: 'honda.civic',
        make_id: 'honda',
        name: 'Civic',
        year: 2026,
        body_style: null,
      },
    ];
    const trims: Trim[] = [
      {
        id: 'honda.civic.2026.sport',
        model_id: 'honda.civic',
        name: 'Sport',
        msrp_range: null,
      },
      {
        id: 'honda.civic.2026.orphan',
        model_id: 'honda.civic',
        name: 'Orphan',
        msrp_range: null,
      },
    ];
    const features: Feature[] = [
      {
        id: 'f.x',
        display_name: 'X',
        category: 'c',
        brand_scope: 'universal',
        cue_phrases: ['x'],
        synonyms: [],
      },
    ];
    const matrix: TrimFeature[] = [
      {
        trim_id: 'honda.civic.2026.sport',
        feature_id: 'f.x',
        availability: 'standard',
      },
    ];
    const result = validateCatalog(makes, models, trims, features, matrix);
    const orphan = result.errors.filter((e) => e.code === 'trim_has_no_features');
    expect(orphan).toHaveLength(1);
    expect(orphan[0]?.entity_id).toBe('honda.civic.2026.orphan');
  });
});
