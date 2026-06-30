import { beforeAll, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';

import { VehicleFeatureCatalog } from '../../src/ts/index.js';

// TS half of the cross-language parity harness (C-PAR-1, C-PAR-2).
// Reads the SAME tests/parity/expectations.json as tests/python/test_parity.py
// and asserts the SAME results on the SAME fixtures. Drift in the TS facade
// fails here; drift in Python fails the Python parity test.
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const FIXTURES = path.resolve(__dirname, '..', 'fixtures');
const FIXTURES_INVALID = path.resolve(__dirname, '..', 'fixtures-invalid');
const EXPECTATIONS = path.resolve(__dirname, '..', 'parity', 'expectations.json');

const expected = JSON.parse(readFileSync(EXPECTATIONS, 'utf-8'));

const ids = (entities: { id: string }[]): string[] =>
  entities.map((e) => e.id).sort();

describe('Python<->TS facade parity', () => {
  let catalog: VehicleFeatureCatalog;

  beforeAll(async () => {
    catalog = await VehicleFeatureCatalog.load(FIXTURES);
  });

  it('exposes the shared public method set', () => {
    for (const name of [
      'get_make',
      'get_model',
      'get_trim',
      'get_feature',
      'list_makes',
      'list_models',
      'list_trims',
      'list_features',
      'list_features_for_trim',
      'list_trims_with_feature',
      'validate',
    ]) {
      expect(typeof (catalog as unknown as Record<string, unknown>)[name]).toBe(
        'function',
      );
    }
  });

  it('returns the shared expected results for every query', () => {
    const f = expected.fixtures;
    expect(ids(catalog.list_makes())).toEqual(f.list_makes);
    expect(ids(catalog.list_models())).toEqual(f.list_models_all);
    expect(ids(catalog.list_models('honda'))).toEqual(f.list_models_honda);
    expect(ids(catalog.list_trims('honda.civic'))).toEqual(f.list_trims_honda_civic);
    expect(ids(catalog.list_features({ brand_scope: 'honda' }))).toEqual(
      f.list_features_brand_honda,
    );
    expect(ids(catalog.list_features({ category: 'comfort' }))).toEqual(
      f.list_features_category_comfort,
    );
    expect(ids(catalog.list_features_for_trim('honda.civic.2026.sport'))).toEqual(
      f.list_features_for_trim_sport_standard,
    );
    expect(
      ids(
        catalog.list_features_for_trim('honda.civic.2026.sport', [
          'standard',
          'optional',
        ]),
      ),
    ).toEqual(f.list_features_for_trim_sport_standard_optional);
    expect(
      ids(catalog.list_trims_with_feature('universal.feature.wireless_apple_carplay')),
    ).toEqual(f.list_trims_with_carplay);
  });

  it('produces the shared expected validator error codes', async () => {
    const catalogInvalid = await VehicleFeatureCatalog.load(FIXTURES_INVALID);
    const codes = Array.from(
      new Set(catalogInvalid.validate().errors.map((e) => e.code)),
    ).sort();
    expect(codes).toEqual(expected.fixtures_invalid.error_codes);
  });
});
