import { describe, expect, it } from 'vitest';
import { promises as fs } from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';

import { VehicleFeatureCatalog } from '../../src/ts/index.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const FIXTURES = path.resolve(__dirname, '..', 'fixtures');
const REAL_DATA = path.resolve(__dirname, '..', '..', 'data');

// Mirrors the Python test_facade_extra.py items so Python/TS stay in lockstep:
//   C-LOAD-2 real data loads + validates
//   C-LIST-4 combined brand_scope + category filter
//   C-VAL-6  parent-reference problems are warnings, not errors
describe('VehicleFeatureCatalog facade (extra coverage)', () => {
  it('loads and validates the shipped real data/ tree', async () => {
    const catalog = await VehicleFeatureCatalog.load(REAL_DATA);
    const result = catalog.validate();
    expect(result.is_valid).toBe(true);
    expect(catalog.get_trim('honda.cr-v-hybrid-awd.2026.sport').name).toBe('Sport');
  });

  it('list_features filters by brand_scope AND category together', async () => {
    const catalog = await VehicleFeatureCatalog.load(FIXTURES);
    const combined = catalog.list_features({
      brand_scope: 'honda',
      category: 'driver_assistance',
    });
    expect(combined.map((f) => f.id)).toEqual(['honda.feature.honda_sensing_360plus']);
    const none = catalog.list_features({ brand_scope: 'honda', category: 'comfort' });
    expect(none).toEqual([]);
  });

  it('emits parent-reference warnings without flipping is_valid', async () => {
    const tmp = await fs.mkdtemp(path.join(os.tmpdir(), 'vfc-warn-'));
    const write = async (rel: string, text: string) => {
      const p = path.join(tmp, rel);
      await fs.mkdir(path.dirname(p), { recursive: true });
      await fs.writeFile(p, text);
    };
    await write('makes/honda.yaml', 'id: honda\nname: Honda\ncountry: JP\n');
    await write(
      'models/honda/civic.yaml',
      'id: honda.civic\nmake_id: ghost-make\nname: Civic\nyear: 2026\n',
    );
    await write(
      'trims/honda/civic/2026/sport.yaml',
      'id: honda.civic.2026.sport\nmodel_id: ghost-model\nname: Sport\n',
    );
    await write(
      'features/universal/carplay.yaml',
      'id: universal.feature.carplay\n' +
        'display_name: Wireless Apple CarPlay\n' +
        'category: connectivity\n' +
        'brand_scope: universal\n' +
        'cue_phrases:\n  - carplay\n' +
        'synonyms: []\n',
    );
    await write(
      'matrix/honda.yaml',
      'make_id: honda\n' +
        'entries:\n' +
        '  - trim_id: honda.civic.2026.sport\n' +
        '    features:\n' +
        '      - { feature_id: universal.feature.carplay, availability: standard }\n',
    );

    const catalog = await VehicleFeatureCatalog.load(tmp);
    const result = catalog.validate();
    const warningCodes = new Set(result.warnings.map((w) => w.code));
    expect(warningCodes.has('model_unknown_make')).toBe(true);
    expect(warningCodes.has('trim_unknown_model')).toBe(true);
    expect(result.is_valid).toBe(true);
    expect(result.errors).toEqual([]);
  });
});
