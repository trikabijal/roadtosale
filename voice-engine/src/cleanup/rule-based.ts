import type { CleanupStrategy } from './base.js';
import type { CleanupRequest, CleanupResult, CommandGrammar, VocabMap } from './types.js';

/**
 * Deterministic, dependency-free cleanup. This is the cross-platform FALLBACK
 * reference: when no on-device LLM is available (or it returns degenerate output),
 * every platform falls back to behaviour equivalent to this. It is intentionally
 * conservative — it never paraphrases, only normalizes.
 *
 * Native implementations (Swift `RuleBasedCleanup`, Kotlin equivalent) should match
 * these transformations so fallback behaviour is identical everywhere.
 */
export class RuleBasedCleanupStrategy implements CleanupStrategy {
  readonly name = 'rule-based';

  /** Disfluencies removed at `light` and `full`. */
  private static readonly FILLERS = [
    'um', 'uh', 'erm', 'ah', 'hmm', 'you know', 'i mean', 'sort of', 'kind of',
  ];

  async clean(req: CleanupRequest): Promise<CleanupResult> {
    const start = Date.now();
    const ops: string[] = [];

    if (req.level === 'off') {
      return this.result(req.raw_text, ops, start);
    }

    let text = req.raw_text;
    text = applyCommands(text, req.command_grammar, ops);
    text = removeFillers(text, RuleBasedCleanupStrategy.FILLERS, ops);
    if (req.level === 'full') {
      text = collapseRepeatedWords(text, ops);
    }
    text = normalizeWhitespaceAndCaps(text, ops);
    text = applyVocab(text, req.vocab, ops);

    return this.result(text.trim(), ops, start);
  }

  private result(text: string, ops: string[], start: number): CleanupResult {
    return {
      cleaned_text: text,
      ops_applied: ops,
      used_fallback: false, // rule-based IS the engine here, not a fallback from itself
      latency_ms: Date.now() - start,
      engine_metadata: { strategy: 'rule-based' },
    };
  }
}

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** Replace spoken command phrases with their literal output (longest-first). */
function applyCommands(text: string, grammar: CommandGrammar, ops: string[]): string {
  const phrases = Object.keys(grammar).sort((a, b) => b.length - a.length);
  let out = text;
  let hit = false;
  for (const phrase of phrases) {
    const re = new RegExp(`\\b${escapeRegExp(phrase)}\\b`, 'gi');
    if (re.test(out)) {
      out = out.replace(re, grammar[phrase]);
      hit = true;
    }
  }
  if (hit) ops.push('commands');
  return out;
}

function removeFillers(text: string, fillers: string[], ops: string[]): string {
  let out = text;
  let hit = false;
  for (const filler of fillers) {
    const re = new RegExp(`\\b${escapeRegExp(filler)}\\b[,]?`, 'gi');
    if (re.test(out)) {
      out = out.replace(re, '');
      hit = true;
    }
  }
  if (hit) ops.push('fillers');
  return out;
}

/** "i i think" → "i think"; "the the car" → "the car". */
function collapseRepeatedWords(text: string, ops: string[]): string {
  const out = text.replace(/\b(\w+)(\s+\1\b)+/gi, '$1');
  if (out !== text) ops.push('repeats');
  return out;
}

function normalizeWhitespaceAndCaps(text: string, ops: string[]): string {
  let out = text
    .replace(/[ \t]{2,}/g, ' ')
    .replace(/ +([,.!?;:])/g, '$1')
    .replace(/[ \t]*\n[ \t]*/g, '\n')
    .trim();

  // Capitalize the first alphabetic char of each sentence/line.
  out = out.replace(/(^|[.!?]\s+|\n+)([a-z])/g, (_m, sep: string, ch: string) => sep + ch.toUpperCase());
  // Standalone "i" → "I".
  out = out.replace(/\bi\b/g, 'I');

  if (out !== text) ops.push('punctuation');
  return out;
}

/** Forced spellings/replacements, applied last (case-insensitive, whole-word). */
function applyVocab(text: string, vocab: VocabMap, ops: string[]): string {
  let out = text;
  let hit = false;
  for (const [from, to] of Object.entries(vocab)) {
    const re = new RegExp(`\\b${escapeRegExp(from)}\\b`, 'gi');
    if (re.test(out)) {
      out = out.replace(re, to);
      hit = true;
    }
  }
  if (hit) ops.push('vocab');
  return out;
}
