/**
 * P-PACK-3 / P-PACK-4 — the cleanup-pack DATA actually drives the cleanup
 * contract end-to-end.
 *
 * The Python `test_cleanup_packs.py` guards the pack's JSON schema. This file
 * proves the pack's `command_grammar` and `lexicon.expansions` (fed in as a
 * VocabMap) produce the documented transforms when run through the reference
 * `RuleBasedCleanupStrategy` — i.e. the data is wired to behaviour, not just
 * well-formed.
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  RuleBasedCleanupStrategy,
  type CleanupRequest,
  type CommandGrammar,
  type VocabMap,
} from '../src/index.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const PACKS_DIR = resolve(HERE, '..', 'cleanup-packs');

interface CleanupPack {
  profile: string;
  command_grammar: CommandGrammar;
  lexicon?: { terms: string[]; expansions: Record<string, string> };
}

function loadPack(name: string): CleanupPack {
  return JSON.parse(readFileSync(resolve(PACKS_DIR, name), 'utf8'));
}

const rtsPack = loadPack('road-to-sale.json');
const strategy = new RuleBasedCleanupStrategy();

const req = (over: Partial<CleanupRequest>): CleanupRequest => ({
  raw_text: '',
  level: 'full',
  vocab: {},
  command_grammar: {},
  ...over,
});

describe('cleanup pack drives RuleBasedCleanupStrategy end-to-end', () => {
  it("RTS lexicon.expansions force-spell dealership terms ('f and i' → F&I)", async () => {
    const vocab = rtsPack.lexicon!.expansions as VocabMap;
    const r = await strategy.clean(
      req({
        raw_text: 'we agreed on the f and i and the trade in is solid',
        vocab,
      }),
    );
    expect(r.cleaned_text).toContain('F&I');
    expect(r.cleaned_text).toContain('trade-in');
    expect(r.ops_applied).toContain('vocab');
  });

  it('RTS command_grammar from the pack inserts the documented breaks', async () => {
    const r = await strategy.clean(
      req({
        raw_text: 'customer wants the touring trim new paragraph trade in pending',
        command_grammar: rtsPack.command_grammar,
      }),
    );
    expect(r.cleaned_text).toContain('\n\n');
    expect(r.ops_applied).toContain('commands');
  });

  it('P-PACK-4: every RTS expansion round-trips through the vocab pass', async () => {
    // Use level:'light' so the vocab pass is isolated: 'full' collapses repeated
    // tokens (e.g. "k b b" → "k b") BEFORE vocab runs, which is correct cleanup
    // behaviour but would defeat a whole-word expansion match. The expansion
    // contract is "spoken form → written form", which 'light' preserves.
    const expansions = rtsPack.lexicon!.expansions;
    for (const [spoken, written] of Object.entries(expansions)) {
      const r = await strategy.clean(
        req({ raw_text: `note ${spoken} here`, level: 'light', vocab: expansions }),
      );
      expect(r.cleaned_text, `expansion "${spoken}" → "${written}"`).toContain(written);
    }
  });
});
