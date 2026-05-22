import type { SessionContext, TranscriptEvent, Unsubscribe } from '../types/index.js';
import type { Session, TranscriptionStrategy } from './base.js';

/**
 * Mock transcription strategy — replays an in-memory event array.
 *
 * The Python counterpart replays from a JSONL file; the TS variant
 * accepts an in-memory array to keep the library boundary clean
 * (no filesystem access in the live mobile path).
 */
export class MockTranscriptionStrategy implements TranscriptionStrategy {
  readonly name = 'mock';
  private readonly events: TranscriptEvent[];

  constructor(events: TranscriptEvent[]) {
    this.events = events;
  }

  start(_context: SessionContext): Session {
    const handlers: Array<(e: TranscriptEvent) => void> = [];
    let stopped = false;

    // Schedule emission on next microtask so subscribers can attach.
    queueMicrotask(() => {
      if (stopped) return;
      for (const ev of this.events) {
        if (stopped) break;
        for (const h of handlers) h(ev);
      }
    });

    return {
      onEvent(handler: (e: TranscriptEvent) => void): Unsubscribe {
        handlers.push(handler);
        return () => {
          const idx = handlers.indexOf(handler);
          if (idx >= 0) handlers.splice(idx, 1);
        };
      },
      async stop(): Promise<void> {
        stopped = true;
      },
    };
  }
}
