#!/usr/bin/env ts-node
/**
 * build-catalog.ts — Vehicle catalog compiler
 *
 * Reads vehicle-feature-catalog/data/ YAML files and writes a single
 * src/catalog/bundle.json for the React Native app to load at startup.
 *
 * Run: ts-node src/catalog/build-catalog.ts
 * Called automatically by build.sh
 *
 * Data layout (actual on disk):
 *   data/makes/         — one YAML per make  (honda.yaml, toyota.yaml)
 *   data/models/        — one subdir per make, one YAML per model
 *   data/trims/         — one subdir per make → model → year, one YAML per trim
 *   data/features/      — one subdir per brand scope (honda/, universal/)
 *                         each file is a single feature document
 *   data/matrix/        — one YAML per make; each file lists trim×feature entries
 */

import * as fs from 'fs';
import * as path from 'path';
import * as yaml from 'js-yaml';

const CATALOG_DATA_DIR = path.resolve(__dirname, '../../../vehicle-feature-catalog/data');
const OUTPUT_FILE = path.resolve(__dirname, 'bundle.json');

interface CatalogBundle {
  makes: Record<string, unknown>[];
  models: Record<string, unknown>[];
  trims: Record<string, unknown>[];
  features: Record<string, unknown>[];
  trimFeatures: Record<string, unknown>[];
  generatedAt: string;
}

/** Read every *.yaml / *.yml file directly inside `dir` (non-recursive). */
function readYamlFilesInDir(dir: string): Record<string, unknown>[] {
  if (!fs.existsSync(dir)) return [];
  return fs
    .readdirSync(dir)
    .filter(f => f.endsWith('.yaml') || f.endsWith('.yml'))
    .map(f => {
      const raw = yaml.load(fs.readFileSync(path.join(dir, f), 'utf8'));
      return raw as Record<string, unknown>;
    })
    .filter(Boolean);
}

/** Recursively collect all YAML files under `dir` (any depth). */
function readYamlFilesRecursive(dir: string): Record<string, unknown>[] {
  if (!fs.existsSync(dir)) return [];
  const results: Record<string, unknown>[] = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      results.push(...readYamlFilesRecursive(fullPath));
    } else if (entry.name.endsWith('.yaml') || entry.name.endsWith('.yml')) {
      const raw = yaml.load(fs.readFileSync(fullPath, 'utf8'));
      if (raw) results.push(raw as Record<string, unknown>);
    }
  }
  return results;
}

/**
 * The matrix YAML files have this shape:
 *
 *   make_id: honda
 *   entries:
 *     - trim_id: honda.cr-v-hybrid-awd.2026.sport-touring
 *       features:
 *         - feature_id: universal.feature.wireless_apple_carplay
 *           availability: standard
 *
 * We flatten these into TrimFeature records compatible with the entity interface.
 */
function loadTrimFeatures(): Record<string, unknown>[] {
  const matrixDir = path.join(CATALOG_DATA_DIR, 'matrix');
  if (!fs.existsSync(matrixDir)) return [];

  const result: Record<string, unknown>[] = [];

  for (const file of fs.readdirSync(matrixDir).filter(f => f.endsWith('.yaml') || f.endsWith('.yml'))) {
    const doc = yaml.load(fs.readFileSync(path.join(matrixDir, file), 'utf8')) as {
      make_id?: string;
      entries?: Array<{
        trim_id: string;
        features: Array<{ feature_id: string; availability: string }>;
      }>;
    };

    if (!doc || !Array.isArray(doc.entries)) continue;

    for (const entry of doc.entries) {
      if (!entry.trim_id || !Array.isArray(entry.features)) continue;
      for (const f of entry.features) {
        result.push({
          trim_id: entry.trim_id,
          feature_id: f.feature_id,
          availability: f.availability,
        });
      }
    }
  }

  return result;
}

function build(): void {
  console.log('Building vehicle catalog bundle...');

  // Makes — flat files directly under data/makes/
  const makes = readYamlFilesInDir(path.join(CATALOG_DATA_DIR, 'makes'));

  // Models — one subdir per make, each containing model YAML files
  const modelsDir = path.join(CATALOG_DATA_DIR, 'models');
  const models: Record<string, unknown>[] = [];
  if (fs.existsSync(modelsDir)) {
    for (const makeSubdir of fs.readdirSync(modelsDir, { withFileTypes: true })) {
      if (makeSubdir.isDirectory()) {
        models.push(...readYamlFilesInDir(path.join(modelsDir, makeSubdir.name)));
      }
    }
  }

  // Trims — deeply nested: trims/<make>/<model>/<year>/<trim>.yaml
  const trims = readYamlFilesRecursive(path.join(CATALOG_DATA_DIR, 'trims'));

  // Features — one subdir per brand scope (honda/, universal/)
  const featuresDir = path.join(CATALOG_DATA_DIR, 'features');
  const features: Record<string, unknown>[] = [];
  if (fs.existsSync(featuresDir)) {
    for (const scopeSubdir of fs.readdirSync(featuresDir, { withFileTypes: true })) {
      if (scopeSubdir.isDirectory()) {
        features.push(...readYamlFilesInDir(path.join(featuresDir, scopeSubdir.name)));
      }
    }
  }

  // Trim-feature mappings — from matrix/ directory
  const trimFeatures = loadTrimFeatures();

  const bundle: CatalogBundle = {
    makes,
    models,
    trims,
    features,
    trimFeatures,
    generatedAt: new Date().toISOString(),
  };

  fs.writeFileSync(OUTPUT_FILE, JSON.stringify(bundle, null, 2));
  console.log(
    `Catalog bundle written: ${makes.length} makes, ${models.length} models, ` +
      `${trims.length} trims, ${features.length} features, ${trimFeatures.length} trim-feature mappings`,
  );
}

build();
