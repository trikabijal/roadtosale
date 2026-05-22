import { describe, expect, it } from 'vitest';
import { MockTranscriptionStrategy } from '../src/strategies/mock.js';
import type { TranscriptEvent } from '../src/types/index.js';

function event(text: string, ts: number, stability: 'partial' | 'final'): TranscriptEvent {
  return {
    text,
    stability,
    timestamp_ms: ts,
    latency_ms_from_audio_start: ts + 50,
    confidence: 0.8,
    engine_metadata: {},
  };
}

describe('MockTranscriptionStrategy', () => {
  it('replays the provided events to subscribers', async () => {
    const events = [
      event('hello', 100, 'partial'),
      event('hello world', 200, 'final'),
    ];
    const strategy = new MockTranscriptionStrategy(events);
    const session = strategy.start({ session_id: 's1', language: 'en-US', custom_vocabulary: [] });

    const received: TranscriptEvent[] = [];
    session.onEvent((e) => received.push(e));

    // wait for microtask flush
    await new Promise((r) => setTimeout(r, 0));
    await session.stop();

    expect(received).toHaveLength(2);
    expect(received[0].stability).toBe('partial');
    expect(received[1].stability).toBe('final');
  });

  it('stop() prevents further emission', async () => {
    const events = [event('a', 1, 'final'), event('b', 2, 'final')];
    const strategy = new MockTranscriptionStrategy(events);
    const session = strategy.start({ session_id: 's1', language: 'en-US', custom_vocabulary: [] });
    await session.stop();

    const received: TranscriptEvent[] = [];
    session.onEvent((e) => received.push(e));
    await new Promise((r) => setTimeout(r, 0));
    expect(received).toEqual([]);
  });

  it('unsubscribe stops delivering to the handler', async () => {
    const events = [event('a', 1, 'final')];
    const strategy = new MockTranscriptionStrategy(events);
    const session = strategy.start({ session_id: 's1', language: 'en-US', custom_vocabulary: [] });

    const received: TranscriptEvent[] = [];
    const unsubscribe = session.onEvent((e) => received.push(e));
    unsubscribe();

    await new Promise((r) => setTimeout(r, 0));
    expect(received).toEqual([]);
    await session.stop();
  });
});
