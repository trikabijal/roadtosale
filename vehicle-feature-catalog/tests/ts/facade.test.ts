import { beforeAll, describe, expect, it } from 'vitest';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';

import { VehicleFeatureCatalog } from '../../src/ts/index.js';
import { NotFoundError } from '../../src/ts/errors.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const FIXTURES = path.resolve(__dirname, '..', 'fixtures');

describe('VehicleFeatureCatalog facade', () => {
  let catalog: VehicleFeatureCatalog;

  beforeAll(async () => {
    catalog = await VehicleFeatureCatalog.load(FIXTURES);
  });

  it('loads', () => {
    expect(catalog).toBeInstanceOf(VehicleFeatureCatalog);
  });

  it('get_make returns the make', () => {
    const honda = catalog.get_make('honda');
    expect(honda.name).toBe('Honda');
    expect(honda.country).toBe('JP');
  });

  it('get_make throws NotFoundError for unknown id', () => {
    expect(() => catalog.get_make('ford')).toThrow(NotFoundError);
  });

  it('get_model / get_trim / get_feature work', () => {
    expect(catalog.get_model('honda.civic').name).toBe('Civic');
    expect(catalog.get_trim('honda.civic.2026.sport').name).toBe('Sport');
    expect(
      catalog.get_feature('universal.feature.wireless_apple_carplay').display_name,
    ).toBe('Wireless Apple CarPlay');
  });

  it('list_makes returns both makes', () => {
    const ids = new Set(catalog.list_makes().map((m) => m.id));
    expect(ids).toEqual(new Set(['honda', 'toyota']));
  });

  it('list_models filters by make', () => {
    const honda = catalog.list_models('honda');
    expect(honda.map((m) => m.id)).toEqual(['honda.civic']);
    const all = catalog.list_models();
    expect(new Set(all.map((m) => m.id))).toEqual(
      new Set(['honda.civic', 'toyota.corolla']),
    );
  });

  it('list_trims filters by model', () => {
    const trims = catalog.list_trims('honda.civic');
    expect(new Set(trims.map((t) => t.id))).toEqual(
      new Set(['honda.civic.2026.sport', 'honda.civic.2026.touring']),
    );
  });

  it('list_features filters by brand_scope', () => {
    const honda = catalog.list_features({ brand_scope: 'honda' });
    expect(honda.map((f) => f.id)).toEqual(['honda.feature.honda_sensing_360plus']);
  });

  it('list_features filters by category', () => {
    const comfort = catalog.list_features({ category: 'comfort' });
    expect(new Set(comfort.map((f) => f.id))).toEqual(
      new Set(['universal.feature.heated_front_seats']),
    );
  });

  it('list_features_for_trim returns standard features by default', () => {
    const feats = catalog.list_features_for_trim('honda.civic.2026.sport');
    expect(new Set(feats.map((f) => f.id))).toEqual(
      new Set([
        'universal.feature.wireless_apple_carplay',
        'universal.feature.lane_keep_assist',
      ]),
    );
  });

  it('list_features_for_trim can include optional features', () => {
    const feats = catalog.list_features_for_trim('honda.civic.2026.sport', [
      'standard',
      'optional',
    ]);
    expect(new Set(feats.map((f) => f.id))).toEqual(
      new Set([
        'universal.feature.wireless_apple_carplay',
        'universal.feature.lane_keep_assist',
        'universal.feature.heated_front_seats',
      ]),
    );
  });

  it('list_features_for_trim throws on unknown trim', () => {
    expect(() => catalog.list_features_for_trim('honda.nope')).toThrow(NotFoundError);
  });

  it('list_trims_with_feature returns every trim with the feature', () => {
    const trims = catalog.list_trims_with_feature(
      'universal.feature.wireless_apple_carplay',
    );
    expect(new Set(trims.map((t) => t.id))).toEqual(
      new Set([
        'honda.civic.2026.sport',
        'honda.civic.2026.touring',
        'toyota.corolla.2026.le',
      ]),
    );
  });

  it('list_trims_with_feature throws on unknown feature', () => {
    expect(() => catalog.list_trims_with_feature('universal.feature.unknown')).toThrow(
      NotFoundError,
    );
  });

  it('validate is happy with the fixture catalog', () => {
    const result = catalog.validate();
    expect(result.is_valid).toBe(true);
    expect(result.errors).toEqual([]);
  });
});
