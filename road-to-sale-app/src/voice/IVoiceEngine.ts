import type { TranscriptEvent } from './types';

export type VoiceEngineState = 'idle' | 'starting' | 'listening' | 'muted' | 'stopping' | 'stopped' | 'error';
export type PermissionStatus = 'granted' | 'denied' | 'restricted' | 'undetermined';

export interface VoiceEngineOptions {
  language: string;          // e.g. 'en-US'
  customVocabulary?: string[]; // dealer-specific terms to boost recognition
}

export interface IVoiceEngine {
  readonly state: VoiceEngineState;

  // Lifecycle
  requestPermissions(): Promise<PermissionStatus>;
  start(options: VoiceEngineOptions): Promise<void>;
  stop(): Promise<void>;
  mute(): void;
  unmute(): void;

  // Events — returns unsubscribe function
  onTranscript(listener: (event: TranscriptEvent) => void): () => void;
  onStateChange(listener: (state: VoiceEngineState) => void): () => void;
  onError(listener: (error: Error) => void): () => void;
}
