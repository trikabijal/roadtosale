import type { IVoiceEngine, VoiceEngineState, PermissionStatus, VoiceEngineOptions } from './IVoiceEngine';
import type { TranscriptEvent } from './types';

export class MockVoiceEngine implements IVoiceEngine {
  private _state: VoiceEngineState = 'idle';
  private transcriptListeners: Array<(e: TranscriptEvent) => void> = [];
  private stateListeners: Array<(s: VoiceEngineState) => void> = [];
  private errorListeners: Array<(e: Error) => void> = [];

  get state() { return this._state; }

  async requestPermissions(): Promise<PermissionStatus> { return 'granted'; }

  async start(_options: VoiceEngineOptions): Promise<void> {
    this._setState('listening');
  }

  async stop(): Promise<void> { this._setState('stopped'); }
  mute(): void { this._setState('muted'); }
  unmute(): void { this._setState('listening'); }

  onTranscript(l: (e: TranscriptEvent) => void): () => void {
    this.transcriptListeners.push(l);
    return () => { this.transcriptListeners = this.transcriptListeners.filter(x => x !== l); };
  }
  onStateChange(l: (s: VoiceEngineState) => void): () => void {
    this.stateListeners.push(l);
    return () => { this.stateListeners = this.stateListeners.filter(x => x !== l); };
  }
  onError(l: (e: Error) => void): () => void {
    this.errorListeners.push(l);
    return () => { this.errorListeners = this.errorListeners.filter(x => x !== l); };
  }

  simulateTranscript(text: string, stability: 'partial' | 'final' = 'final'): void {
    const event: TranscriptEvent = {
      text,
      stability,
      timestamp_ms: Date.now(),
      latency_ms_from_audio_start: 100,
      confidence: 0.95,
      engine_metadata: { engine: 'mock' },
    };
    this.transcriptListeners.forEach(l => l(event));
  }

  private _setState(s: VoiceEngineState): void {
    this._state = s;
    this.stateListeners.forEach(l => l(s));
  }
}
