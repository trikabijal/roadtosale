import { NotImplementedError } from '../types/index.js';
import type { SessionContext } from '../types/index.js';
import type { Session, TranscriptionStrategy } from './base.js';

/**
 * Apple SpeechTranscriber strategy (iOS) — STUB.
 *
 * Wiring to the Swift native module is unresolved. See PRD OQ1
 * (Apple SpeechTranscriber macOS / iOS variant). Until OQ1 is resolved,
 * `start` throws NotImplementedError.
 */
export class AppleSpeechTranscriberStrategy implements TranscriptionStrategy {
  readonly name = 'apple_speech_transcriber';

  start(_context: SessionContext): Session {
    throw new NotImplementedError(
      'AppleSpeechTranscriberStrategy is a stub. Resolve PRD OQ1 ' +
        '(Apple SpeechTranscriber macOS/iOS variant) before wiring this strategy.',
    );
  }
}
