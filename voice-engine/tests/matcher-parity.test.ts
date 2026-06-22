/**
 * X-PAR-1 — TS ↔ Python cue-matcher BEHAVIOUR parity.
 *
 * The drift test (`lab/tests/test_drift.py`) proves the two languages share
 * field NAMES. It does NOT prove the two `CueMatcher` IMPLEMENTATIONS agree on
 * the same input. This test closes that gap: it feeds the *same* cue atoms and
 * the *same* transcript fixtures to both matchers and asserts they produce the
 * IDENTICAL set of `{cue_id, matched_phrase}` detections.
 *
 *   - TS matcher: imported and run in-process (vitest compiles the source).
 *   - Python matcher: run via a `python3` subprocess against the lab venv.
 *
 * If `python3` / the lab venv is unavailable, the cross-language half is
 * skipped (the TS-vs-expected half still runs), mirroring the drift test's
 * skip-on-missing-toolchain discipline so CI stays green.
 */
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { CueMatcher } from '../src/matcher/cue-matcher.js';
import type { CueAtom, TranscriptEvent } from '../src/types/index.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const FIXTURE_PATH = resolve(HERE, 'fixtures', 'matcher-parity.json');
const VOICE_ENGINE_DIR = resolve(HERE, '..');
const VENV_PYTHON = resolve(VOICE_ENGINE_DIR, 'lab', '.venv', 'bin', 'python');

interface Detection {
  cue_id: string;
  matched_phrase: string;
}
interface Case {
  name: string;
  text: string;
  expected: Detection[];
}
interface Fixture {
  atoms: CueAtom[];
  cases: Case[];
}

const fixture: Fixture = JSON.parse(readFileSync(FIXTURE_PATH, 'utf8'));

function event(text: string): TranscriptEvent {
  return {
    text,
    stability: 'final',
    timestamp_ms: 0,
    latency_ms_from_audio_start: 50,
    confidence: 0.9,
    engine_metadata: {},
  };
}

function sortDetections(d: Detection[]): Detection[] {
  return [...d].sort((a, b) =>
    a.cue_id === b.cue_id
      ? a.matched_phrase.localeCompare(b.matched_phrase)
      : a.cue_id.localeCompare(b.cue_id),
  );
}

function tsDetections(text: string): Detection[] {
  const matcher = new CueMatcher(fixture.atoms);
  return Array.from(matcher.match([event(text)])).map((d) => ({
    cue_id: d.cue_id,
    matched_phrase: d.matched_phrase,
  }));
}

/** Run every case through the Python CueMatcher; returns case-name → detections. */
function pythonDetections(): Record<string, Detection[]> | null {
  if (!existsSync(VENV_PYTHON)) return null;
  const script = `
import json, sys
sys.path.insert(0, "lab/src")
from voice_lab.matcher.cue_matcher import CueMatcher
from voice_lab.types import CueAtom, TranscriptEvent

fx = json.load(open(sys.argv[1], encoding="utf-8"))
atoms = [CueAtom(**a) for a in fx["atoms"]]
out = {}
for c in fx["cases"]:
    ev = TranscriptEvent(text=c["text"], stability="final", timestamp_ms=0,
                         latency_ms_from_audio_start=50, confidence=0.9, engine_metadata={})
    dets = list(CueMatcher(atoms).match([ev]))
    out[c["name"]] = [{"cue_id": d.cue_id, "matched_phrase": d.matched_phrase} for d in dets]
print(json.dumps(out))
`;
  const raw = execFileSync(VENV_PYTHON, ['-c', script, FIXTURE_PATH], {
    cwd: VOICE_ENGINE_DIR,
    encoding: 'utf8',
  });
  return JSON.parse(raw);
}

describe('cue-matcher parity (TS ↔ Python)', () => {
  it('TS matcher matches the expected detection set on shared fixtures', () => {
    for (const c of fixture.cases) {
      expect(sortDetections(tsDetections(c.text)), `TS case "${c.name}"`).toEqual(
        sortDetections(c.expected),
      );
    }
  });

  it('Python matcher produces IDENTICAL detections to the TS matcher', () => {
    const py = pythonDetections();
    if (py === null) {
      // No lab venv (CI without ./build.sh). The TS-vs-expected half above
      // still guards the contract; skip the cross-language comparison.
      return;
    }
    for (const c of fixture.cases) {
      const tsSet = sortDetections(tsDetections(c.text));
      const pySet = sortDetections(py[c.name] ?? []);
      expect(pySet, `Python vs TS case "${c.name}"`).toEqual(tsSet);
    }
  });
});
