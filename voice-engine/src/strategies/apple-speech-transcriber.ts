import type { SessionContext, TranscriptEvent, Unsubscribe } from '../types/index.js';
import type { Session, TranscriptionStrategy } from './base.js';

/**
 * Minimal interface the strategy needs from the iOS native module.
 * NativeVoiceModuleImpl in road-to-sale-app satisfies this structurally.
 */
export interface NativeVoiceAdapter {
  start(options: { language: string; customVocabulary: string[] }): Promise<void>;
  stop(): Promise<void>;
  onTranscript(listener: (event: TranscriptEvent) => void): Unsubscribe;
}

/**
 * Apple SpeechTranscriber strategy (iOS).
 *
 * Wraps RtsVoiceModule.swift behind the TranscriptionStrategy interface.
 * The native module is injected via the constructor so this strategy stays
 * decoupled from react-native imports.
 *
 * Registration (in road-to-sale-app/src/voice/voiceEngineSetup.ts):
 *   registerStrategy(new AppleSpeechTranscriberStrategy(getVoiceEngine()))
 */
export class AppleSpeechTranscriberStrategy implements TranscriptionStrategy {
  readonly name = 'apple_speech_transcriber';

  constructor(private readonly native: NativeVoiceAdapter) {}

  start(context: SessionContext): Session {
    const handlers: Array<(e: TranscriptEvent) => void> = [];

    // Forward every transcript event from the native module to our handlers
    const unsubscribeNative = this.native.onTranscript((event) => {
      for (const h of handlers) h(event);
    });

    // Tell the native module to open the mic and start recognising
    void this.native.start({
      language: context.language,
      customVocabulary: context.custom_vocabulary,
    });

    const native = this.native;
    return {
      onEvent(handler: (e: TranscriptEvent) => void): Unsubscribe {
        handlers.push(handler);
        return () => {
          const idx = handlers.indexOf(handler);
          if (idx >= 0) handlers.splice(idx, 1);
        };
      },
      async stop(): Promise<void> {
        unsubscribeNative();
        await native.stop();
      },
    };
  }
}
