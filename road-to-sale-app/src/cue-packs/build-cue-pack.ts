/**
 * build-cue-pack.ts
 *
 * Build-time compiler: reads road-to-sale-v1.yaml and writes road-to-sale-v1.json
 * so Metro bundler can import it at runtime.
 *
 * Usage (from road-to-sale-app root):
 *   npx ts-node src/cue-packs/build-cue-pack.ts
 *
 * Run this whenever road-to-sale-v1.yaml changes, or wire it into build.sh.
 */

import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import * as yaml from 'js-yaml';
import type { CuePackEntry } from '../session/types';

interface RawYamlEntry {
  cue_id: string;
  template_question_id: number;
  required: boolean;
  ok_option_id?: number;
}

interface RawYaml {
  entries: RawYamlEntry[];
}

// Support both CommonJS (__dirname) and ESM (import.meta.url)
const SRC_DIR = (typeof __dirname !== 'undefined')
  ? __dirname
  : path.dirname(fileURLToPath(import.meta.url));
const yamlPath = path.join(SRC_DIR, 'road-to-sale-v1.yaml');
const jsonPath = path.join(SRC_DIR, 'road-to-sale-v1.json');

function main(): void {
  const raw = fs.readFileSync(yamlPath, 'utf-8');
  const parsed = yaml.load(raw) as RawYaml;

  if (!parsed || !Array.isArray(parsed.entries)) {
    throw new Error('road-to-sale-v1.yaml must have a top-level "entries" array');
  }

  const entries: CuePackEntry[] = parsed.entries.map((e, idx) => {
    if (!e.cue_id) throw new Error(`Entry at index ${idx} is missing cue_id`);
    if (typeof e.template_question_id !== 'number') {
      throw new Error(`Entry "${e.cue_id}" is missing template_question_id`);
    }
    return {
      cueId: e.cue_id,
      templateQuestionId: e.template_question_id,
      required: Boolean(e.required),
      ...(e.ok_option_id !== undefined ? { okOptionId: e.ok_option_id } : {}),
    };
  });

  fs.writeFileSync(jsonPath, JSON.stringify(entries, null, 2) + '\n', 'utf-8');
  console.log(`[build-cue-pack] wrote ${entries.length} entries → ${jsonPath}`);
}

main();
