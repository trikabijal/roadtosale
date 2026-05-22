import { promises as fs } from 'node:fs';
import * as path from 'node:path';
import * as yaml from 'js-yaml';

import {
  Availability,
  Feature,
  Make,
  Model,
  Trim,
  TrimFeature,
} from './entities.js';
import { LoadError } from './errors.js';

export interface RawCatalog {
  makes: Make[];
  models: Model[];
  trims: Trim[];
  features: Feature[];
  trim_features: TrimFeature[];
}

const VALID_AVAILABILITY: ReadonlySet<Availability> = new Set([
  'standard',
  'optional',
  'unavailable',
]);

export class YAMLLoader {
  static async read_all(dataDir: string): Promise<RawCatalog> {
    const stat = await safeStat(dataDir);
    if (!stat) {
      throw new LoadError(`data_dir does not exist: ${dataDir}`);
    }
    if (!stat.isDirectory()) {
      throw new LoadError(`data_dir is not a directory: ${dataDir}`);
    }

    const makes = await this.loadMakes(path.join(dataDir, 'makes'));
    const models = await this.loadModels(path.join(dataDir, 'models'));
    const trims = await this.loadTrims(path.join(dataDir, 'trims'));
    const features = await this.loadFeatures(path.join(dataDir, 'features'));
    const trim_features = await this.loadMatrix(path.join(dataDir, 'matrix'));

    return { makes, models, trims, features, trim_features };
  }

  private static async loadMakes(dir: string): Promise<Make[]> {
    const out: Make[] = [];
    for (const filePath of await listYamlFiles(dir)) {
      const data = await readYaml(filePath);
      requireKeys(filePath, data, ['id', 'name', 'country']);
      out.push({
        id: String(data['id']),
        name: String(data['name']),
        country: String(data['country']),
      });
    }
    return out;
  }

  private static async loadModels(dir: string): Promise<Model[]> {
    const out: Model[] = [];
    for (const filePath of await listYamlFiles(dir)) {
      const data = await readYaml(filePath);
      requireKeys(filePath, data, ['id', 'make_id', 'name', 'year']);
      const body = data['body_style'];
      out.push({
        id: String(data['id']),
        make_id: String(data['make_id']),
        name: String(data['name']),
        year: Number(data['year']),
        body_style: body == null ? null : String(body),
      });
    }
    return out;
  }

  private static async loadTrims(dir: string): Promise<Trim[]> {
    const out: Trim[] = [];
    for (const filePath of await listYamlFiles(dir)) {
      const data = await readYaml(filePath);
      requireKeys(filePath, data, ['id', 'model_id', 'name']);
      const raw = data['msrp_range'];
      let msrp_range: [number, number] | null;
      if (raw == null) {
        msrp_range = null;
      } else if (Array.isArray(raw) && raw.length === 2) {
        msrp_range = [Number(raw[0]), Number(raw[1])];
      } else {
        throw new LoadError(
          `${filePath}: msrp_range must be a 2-element list, got ${JSON.stringify(raw)}`,
        );
      }
      out.push({
        id: String(data['id']),
        model_id: String(data['model_id']),
        name: String(data['name']),
        msrp_range,
      });
    }
    return out;
  }

  private static async loadFeatures(dir: string): Promise<Feature[]> {
    const out: Feature[] = [];
    for (const filePath of await listYamlFiles(dir)) {
      const data = await readYaml(filePath);
      requireKeys(filePath, data, ['id', 'display_name', 'category', 'brand_scope']);
      const cueRaw = data['cue_phrases'] ?? [];
      const synRaw = data['synonyms'] ?? [];
      if (!Array.isArray(cueRaw)) {
        throw new LoadError(`${filePath}: cue_phrases must be a list`);
      }
      if (!Array.isArray(synRaw)) {
        throw new LoadError(`${filePath}: synonyms must be a list`);
      }
      out.push({
        id: String(data['id']),
        display_name: String(data['display_name']),
        category: String(data['category']),
        brand_scope: String(data['brand_scope']),
        cue_phrases: cueRaw.map((p) => String(p)),
        synonyms: synRaw.map((s) => String(s)),
      });
    }
    return out;
  }

  private static async loadMatrix(dir: string): Promise<TrimFeature[]> {
    const out: TrimFeature[] = [];
    const stat = await safeStat(dir);
    if (!stat) return out;
    for (const filePath of await listYamlFiles(dir)) {
      const data = await readYaml(filePath);
      const entries = data['entries'];
      if (entries == null) {
        throw new LoadError(`${filePath}: matrix file missing top-level 'entries' key`);
      }
      if (!Array.isArray(entries)) {
        throw new LoadError(`${filePath}: 'entries' must be a list`);
      }
      for (const entry of entries) {
        if (typeof entry !== 'object' || entry === null) {
          throw new LoadError(`${filePath}: matrix entry must be a mapping`);
        }
        const entryRec = entry as Record<string, unknown>;
        const trimId = entryRec['trim_id'];
        const features = entryRec['features'] ?? [];
        if (!trimId) {
          throw new LoadError(`${filePath}: matrix entry missing trim_id`);
        }
        if (!Array.isArray(features)) {
          throw new LoadError(`${filePath}: features in entry must be a list`);
        }
        for (const cell of features) {
          if (typeof cell !== 'object' || cell === null) {
            throw new LoadError(
              `${filePath}: feature cell must be a mapping, got ${JSON.stringify(cell)}`,
            );
          }
          const cellRec = cell as Record<string, unknown>;
          const featureId = cellRec['feature_id'];
          const availability = cellRec['availability'];
          if (!featureId) {
            throw new LoadError(
              `${filePath}: feature cell missing feature_id under trim ${String(trimId)}`,
            );
          }
          if (
            typeof availability !== 'string' ||
            !VALID_AVAILABILITY.has(availability as Availability)
          ) {
            throw new LoadError(
              `${filePath}: invalid availability ${JSON.stringify(availability)} for ` +
                `${String(trimId)} / ${String(featureId)}`,
            );
          }
          out.push({
            trim_id: String(trimId),
            feature_id: String(featureId),
            availability: availability as Availability,
          });
        }
      }
    }
    return out;
  }
}

async function safeStat(target: string): Promise<import('fs').Stats | null> {
  try {
    return await fs.stat(target);
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code === 'ENOENT') return null;
    throw err;
  }
}

async function listYamlFiles(root: string): Promise<string[]> {
  const stat = await safeStat(root);
  if (!stat) return [];
  const out: string[] = [];
  await walk(root, out);
  out.sort();
  return out;
}

async function walk(dir: string, out: string[]): Promise<void> {
  const entries = await fs.readdir(dir, { withFileTypes: true });
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      await walk(full, out);
    } else if (entry.isFile() && entry.name.endsWith('.yaml')) {
      out.push(full);
    }
  }
}

async function readYaml(filePath: string): Promise<Record<string, unknown>> {
  let text: string;
  try {
    text = await fs.readFile(filePath, 'utf-8');
  } catch (err) {
    throw new LoadError(`Failed to read ${filePath}: ${(err as Error).message}`);
  }
  let data: unknown;
  try {
    data = yaml.load(text);
  } catch (err) {
    throw new LoadError(`YAML parse error in ${filePath}: ${(err as Error).message}`);
  }
  if (data == null) {
    throw new LoadError(`${filePath}: file is empty`);
  }
  if (typeof data !== 'object' || Array.isArray(data)) {
    throw new LoadError(`${filePath}: top-level YAML must be a mapping`);
  }
  return data as Record<string, unknown>;
}

function requireKeys(
  filePath: string,
  data: Record<string, unknown>,
  keys: string[],
): void {
  const missing = keys.filter((k) => !(k in data));
  if (missing.length > 0) {
    throw new LoadError(`${filePath}: missing required key(s): ${missing.join(', ')}`);
  }
}
