import { describe, expect, it } from 'vitest';
import {
  RuleBasedCleanupStrategy,
  bootstrapDefaultCleanupStrategies,
  getCleanupStrategy,
  listCleanupStrategies,
  type CleanupRequest,
} from '../src/index.js';

const baseReq = (over: Partial<CleanupRequest>): CleanupRequest => ({
  raw_text: '',
  level: 'full',
  vocab: {},
  command_grammar: {},
  ...over,
});

describe('TextCleanup contract — rule-based reference', () => {
  it('registers as a default cleanup strategy after bootstrap', async () => {
    await bootstrapDefaultCleanupStrategies();
    expect(listCleanupStrategies()).toContain('rule-based');
    expect(getCleanupStrategy('rule-based')).toBeInstanceOf(RuleBasedCleanupStrategy);
  });

  it('level "off" returns the raw text untouched', async () => {
    const s = new RuleBasedCleanupStrategy();
    const r = await s.clean(baseReq({ raw_text: 'um so like hello', level: 'off' }));
    expect(r.cleaned_text).toBe('um so like hello');
    expect(r.ops_applied).toEqual([]);
  });

  it('removes fillers and capitalizes at full', async () => {
    const s = new RuleBasedCleanupStrategy();
    const r = await s.clean(baseReq({ raw_text: 'um i think uh we should ship' }));
    expect(r.cleaned_text).toBe('I think we should ship');
    expect(r.ops_applied).toContain('fillers');
  });

  it('obeys command grammar (new paragraph)', async () => {
    const s = new RuleBasedCleanupStrategy();
    const r = await s.clean(
      baseReq({
        raw_text: 'ship it monday new paragraph lets sync tomorrow',
        command_grammar: { 'new paragraph': '\n\n' },
      }),
    );
    expect(r.cleaned_text).toContain('\n\n');
    expect(r.ops_applied).toContain('commands');
  });

  it('applies the vocab map after cleanup (forced spelling)', async () => {
    const s = new RuleBasedCleanupStrategy();
    const r = await s.clean(
      baseReq({ raw_text: 'call sarah mitchell', vocab: { 'sarah mitchell': 'Sarah Mitchell' } }),
    );
    expect(r.cleaned_text).toContain('Sarah Mitchell');
    expect(r.ops_applied).toContain('vocab');
  });

  it('collapses repeated words at full only', async () => {
    const s = new RuleBasedCleanupStrategy();
    const full = await s.clean(baseReq({ raw_text: 'the the car is is fast' }));
    expect(full.cleaned_text).toBe('The car is fast');
  });

  it('level "light" strips fillers but preserves wording (no repeat-collapse)', async () => {
    // T-CLN-7: the level *boundary* — light is between off and full. It still
    // removes fillers (so it differs from off) but must NOT collapse repeated
    // words (so it differs from full), preserving the speaker's exact wording.
    const s = new RuleBasedCleanupStrategy();
    const light = await s.clean(
      baseReq({ raw_text: 'um the the car is uh fast fast', level: 'light' }),
    );
    // Fillers gone → 'light' is not a no-op like 'off'.
    expect(light.ops_applied).toContain('fillers');
    expect(light.cleaned_text).not.toContain('um');
    expect(light.cleaned_text).not.toContain('uh');
    // Repeats preserved → not collapsed the way 'full' would. (Casing is
    // normalized at the sentence start, so compare case-insensitively.)
    expect(light.ops_applied).not.toContain('repeats');
    expect(light.cleaned_text.toLowerCase()).toContain('the the');
    expect(light.cleaned_text).toContain('fast fast');

    // Contrast: full collapses the same repeats.
    const full = await s.clean(
      baseReq({ raw_text: 'um the the car is uh fast fast', level: 'full' }),
    );
    expect(full.cleaned_text).not.toContain('the the');
    expect(full.cleaned_text).not.toContain('fast fast');
  });

  it('returns the full CleanupResult contract, not just cleaned_text', async () => {
    // T-CLN-8: used_fallback / latency_ms / engine_metadata must be present.
    const s = new RuleBasedCleanupStrategy();
    const r = await s.clean(baseReq({ raw_text: 'um hello there' }));
    expect(typeof r.cleaned_text).toBe('string');
    expect(Array.isArray(r.ops_applied)).toBe(true);
    // rule-based IS the engine here — it is not a fallback from itself.
    expect(r.used_fallback).toBe(false);
    expect(typeof r.latency_ms).toBe('number');
    expect(r.latency_ms).toBeGreaterThanOrEqual(0);
    expect(r.engine_metadata).toMatchObject({ strategy: 'rule-based' });
  });
});
