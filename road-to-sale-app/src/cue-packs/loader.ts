import type { CuePackEntry } from '../session/types';

// Metro bundler cannot import YAML natively — we compile road-to-sale-v1.yaml to JSON
// at build time using build-cue-pack.ts.  This loader imports the compiled JSON.
// Run `npx ts-node src/cue-packs/build-cue-pack.ts` before starting the app or as
// part of the build step in build.sh.
import rawEntries from './road-to-sale-v1.json';

export function loadRtsV1CuePack(): CuePackEntry[] {
  return rawEntries as CuePackEntry[];
}
