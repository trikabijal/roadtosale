import type { TranscriptEvent } from './types';

export type VoiceEngineState = 'idle' | 'starting' | 'listening' | 'muted' | 'stopping' | 'stopped' | 'error';
export type PermissionStatus = 'granted' | 'denied' | 'restricted' | 'undetermined';

export interface VoiceEngineOptions {
  language: string;
  customVocabulary?: string[];
}

export interface IVoiceEngine {
  readonly state: VoiceEngineState;

  requestPermissions(): Promise<PermissionStatus>;
  start(options: VoiceEngineOptions): Promise<void>;
  stop(): Promise<void>;
  mute(): void;
  unmute(): void;

  onTranscript(listener: (event: TranscriptEvent) => void): () => void;
  onStateChange(listener: (state: VoiceEngineState) => void): () => void;
  onError(listener: (error: Error) => void): () => void;
}
