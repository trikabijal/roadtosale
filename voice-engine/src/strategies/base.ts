import type { SessionContext, TranscriptEvent, Unsubscribe } from '../types/index.js';

export interface Session {
  onEvent(handler: (event: TranscriptEvent) => void): Unsubscribe;
  stop(): Promise<void>;
}

export interface TranscriptionStrategy {
  readonly name: string;
  start(context: SessionContext): Session;
}
