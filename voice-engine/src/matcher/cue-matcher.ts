import type { CueAtom, CueDetection, TranscriptEvent } from '../types/index.js';

/**
 * Cue matcher — phrase + synonym substring matching.
 *
 * Same algorithm as the Python implementation in
 * `voice-engine/lab/src/voice_lab/matcher/cue_matcher.py`.
 * Case-insensitive. Lightly normalized whitespace and punctuation.
 * Multiple cues in one event each emit one detection; a single cue
 * matched twice in one event produces one detection (longest match wins).
 */

const PUNCT_RE = /[^\p{L}\p{N}\s]+/gu;
const WS_RE = /\s+/g;

function normalize(text: string): string {
  return text.toLowerCase().replace(PUNCT_RE, ' ').replace(WS_RE, ' ').trim();
}

interface IndexedPhrase {
  atomId: string;
  originalPhrase: string;
  normalized: string;
}

export class CueMatcher {
  private readonly index: IndexedPhrase[];

  constructor(atoms: CueAtom[]) {
    const entries: IndexedPhrase[] = [];
    for (const atom of atoms) {
      for (const phrase of atom.cue_phrases) {
        const norm = normalize(phrase);
        if (norm) entries.push({ atomId: atom.id, originalPhrase: phrase, normalized: norm });
      }
      for (const syn of atom.synonyms) {
        const norm = normalize(syn);
        if (norm) entries.push({ atomId: atom.id, originalPhrase: syn, normalized: norm });
      }
    }
    entries.sort((a, b) => b.normalized.length - a.normalized.length);
    this.index = entries;
  }

  *match(events: Iterable<TranscriptEvent>): IterableIterator<CueDetection> {
    for (const event of events) {
      yield* this.matchEvent(event);
    }
  }

  async *matchAsync(events: AsyncIterable<TranscriptEvent>): AsyncIterableIterator<CueDetection> {
    for await (const event of events) {
      for (const det of this.matchEvent(event)) yield det;
    }
  }

  private *matchEvent(event: TranscriptEvent): IterableIterator<CueDetection> {
    const haystack = normalize(event.text);
    if (!haystack) return;
    const seen = new Set<string>();
    for (const entry of this.index) {
      if (seen.has(entry.atomId)) continue;
      if (haystack.includes(entry.normalized)) {
        seen.add(entry.atomId);
        yield {
          cue_id: entry.atomId,
          matched_phrase: entry.originalPhrase,
          timestamp_ms: event.timestamp_ms,
          confidence: event.confidence,
          triggering_event: event,
        };
      }
    }
  }
}

export function matchCues(
  events: Iterable<TranscriptEvent>,
  cueAtoms: CueAtom[],
): IterableIterator<CueDetection> {
  return new CueMatcher(cueAtoms).match(events);
}

export function matchCuesAsync(
  events: AsyncIterable<TranscriptEvent>,
  cueAtoms: CueAtom[],
): AsyncIterableIterator<CueDetection> {
  return new CueMatcher(cueAtoms).matchAsync(events);
}
