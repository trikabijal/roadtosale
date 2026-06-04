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
});
