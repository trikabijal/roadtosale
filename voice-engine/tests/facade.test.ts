import { describe, expect, it } from 'vitest';
import {
  MockTranscriptionStrategy,
  UnknownStrategyError,
  VoiceEngine,
  bootstrapDefaultStrategies,
} from '../src/index.js';

describe('VoiceEngine facade', () => {
  it('load() returns a facade instance', () => {
    const engine = VoiceEngine.load();
    expect(engine).toBeInstanceOf(VoiceEngine);
  });

  it('listStrategies includes the mock strategy after bootstrap', async () => {
    await bootstrapDefaultStrategies();
    const engine = VoiceEngine.load();
    expect(engine.listStrategies()).toContain('mock');
  });

  it('getStrategy throws UnknownStrategyError for unknown name', () => {
    const engine = VoiceEngine.load();
    expect(() => engine.getStrategy('nope')).toThrow(UnknownStrategyError);
  });

  it('startSession with mock strategy returns a Session', () => {
    const engine = VoiceEngine.load();
    engine.registerStrategy(new MockTranscriptionStrategy([]));
    const session = engine.startSession('mock', {
      session_id: 's1',
      language: 'en-US',
      custom_vocabulary: [],
    });
    expect(session).toBeDefined();
    expect(typeof session.onEvent).toBe('function');
    expect(typeof session.stop).toBe('function');
  });
});
