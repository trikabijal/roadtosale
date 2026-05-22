import { describe, expect, it } from 'vitest';
import { promises as fs } from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';

import { YAMLLoader } from '../../src/ts/loader.js';
import { LoadError } from '../../src/ts/errors.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const FIXTURES = path.resolve(__dirname, '..', 'fixtures');

describe('YAMLLoader.read_all', () => {
  it('returns all entities from the fixture catalog', async () => {
    const raw = await YAMLLoader.read_all(FIXTURES);

    expect(new Set(raw.makes.map((m) => m.id))).toEqual(new Set(['honda', 'toyota']));
    expect(new Set(raw.models.map((m) => m.id))).toEqual(
      new Set(['honda.civic', 'toyota.corolla']),
    );
    expect(new Set(raw.trims.map((t) => t.id))).toEqual(
      new Set([
        'honda.civic.2026.sport',
        'honda.civic.2026.touring',
        'toyota.corolla.2026.le',
      ]),
    );
    expect(new Set(raw.features.map((f) => f.id))).toEqual(
      new Set([
        'universal.feature.wireless_apple_carplay',
        'universal.feature.heated_front_seats',
        'universal.feature.lane_keep_assist',
        'honda.feature.honda_sensing_360plus',
      ]),
    );
    expect(raw.trim_features).toHaveLength(9);
  });

  it('parses msrp_range as a tuple', async () => {
    const raw = await YAMLLoader.read_all(FIXTURES);
    const sport = raw.trims.find((t) => t.id === 'honda.civic.2026.sport');
    expect(sport?.msrp_range).toEqual([25000, 27000]);
  });

  it('throws LoadError when data_dir is missing', async () => {
    await expect(YAMLLoader.read_all('/no/such/path')).rejects.toBeInstanceOf(LoadError);
  });

  it('throws LoadError on malformed YAML', async () => {
    const tmp = await fs.mkdtemp(path.join(os.tmpdir(), 'vfc-'));
    await fs.mkdir(path.join(tmp, 'makes'));
    await fs.writeFile(
      path.join(tmp, 'makes', 'broken.yaml'),
      'id: honda\n  bad indent: yes\n',
    );
    await expect(YAMLLoader.read_all(tmp)).rejects.toBeInstanceOf(LoadError);
  });

  it('throws LoadError on missing required keys', async () => {
    const tmp = await fs.mkdtemp(path.join(os.tmpdir(), 'vfc-'));
    await fs.mkdir(path.join(tmp, 'makes'));
    await fs.writeFile(
      path.join(tmp, 'makes', 'honda.yaml'),
      'id: honda\nname: Honda\n',
    );
    await expect(YAMLLoader.read_all(tmp)).rejects.toBeInstanceOf(LoadError);
  });

  it('throws LoadError on invalid availability', async () => {
    const tmp = await fs.mkdtemp(path.join(os.tmpdir(), 'vfc-'));
    await fs.mkdir(path.join(tmp, 'matrix'));
    await fs.writeFile(
      path.join(tmp, 'matrix', 'x.yaml'),
      'entries:\n  - trim_id: a\n    features:\n      - { feature_id: b, availability: someday }\n',
    );
    await expect(YAMLLoader.read_all(tmp)).rejects.toBeInstanceOf(LoadError);
  });
});
