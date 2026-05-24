import { NativeModules, NativeEventEmitter, Platform, NativeModule } from 'react-native';
import type { IVoiceEngine, VoiceEngineState, PermissionStatus, VoiceEngineOptions } from './IVoiceEngine';
import type { TranscriptEvent } from './types';

const { RtsVoiceModule } = NativeModules;

// Graceful fallback when native module isn't linked yet (web/simulator without prebuild)
const isMocked = !RtsVoiceModule;

class NativeVoiceModuleImpl implements IVoiceEngine {
  private emitter: NativeEventEmitter | null;
  private _state: VoiceEngineState = 'idle';
  private stateListeners: Array<(s: VoiceEngineState) => void> = [];
  private transcriptListeners: Array<(e: TranscriptEvent) => void> = [];
  private errorListeners: Array<(e: Error) => void> = [];

  constructor() {
    this.emitter = isMocked ? null : new NativeEventEmitter(RtsVoiceModule as NativeModule);
    if (this.emitter) {
      this.emitter.addListener('onTranscriptEvent', (raw: string | TranscriptEvent) => {
        const event: TranscriptEvent = typeof raw === 'string' ? JSON.parse(raw) : raw;
        this.transcriptListeners.forEach(l => l(event));
      });
      this.emitter.addListener('onVoiceStateChange', (state: VoiceEngineState) => {
        this._state = state;
        this.stateListeners.forEach(l => l(state));
      });
      this.emitter.addListener('onVoiceError', (message: string) => {
        const err = new Error(message);
        this.errorListeners.forEach(l => l(err));
      });
    }
  }

  get state(): VoiceEngineState { return this._state; }

  async requestPermissions(): Promise<PermissionStatus> {
    if (isMocked) return 'granted';
    return RtsVoiceModule.requestPermissions();
  }

  async start(options: VoiceEngineOptions): Promise<void> {
    if (isMocked) {
      console.warn('[NativeVoiceModule] Running in mock mode — no audio captured');
      this._setState('listening');
      return;
    }
    this._setState('starting');
    await RtsVoiceModule.startListening(options.language, options.customVocabulary ?? []);
    // State transition to 'listening' comes from the native 'onVoiceStateChange' event
  }

  async stop(): Promise<void> {
    if (isMocked) { this._setState('stopped'); return; }
    this._setState('stopping');
    await RtsVoiceModule.stopListening();
  }

  mute(): void {
    if (isMocked) return;
    RtsVoiceModule.mute();
    this._setState('muted');
  }

  unmute(): void {
    if (isMocked) return;
    RtsVoiceModule.unmute();
    this._setState('listening');
  }

  onTranscript(listener: (event: TranscriptEvent) => void): () => void {
    this.transcriptListeners.push(listener);
    return () => { this.transcriptListeners = this.transcriptListeners.filter(l => l !== listener); };
  }

  onStateChange(listener: (state: VoiceEngineState) => void): () => void {
    this.stateListeners.push(listener);
    return () => { this.stateListeners = this.stateListeners.filter(l => l !== listener); };
  }

  onError(listener: (error: Error) => void): () => void {
    this.errorListeners.push(listener);
    return () => { this.errorListeners = this.errorListeners.filter(l => l !== listener); };
  }

  private _setState(s: VoiceEngineState): void {
    this._state = s;
    this.stateListeners.forEach(l => l(s));
  }
}

// Singleton
let _instance: IVoiceEngine | null = null;
export function getVoiceEngine(): IVoiceEngine {
  if (!_instance) _instance = new NativeVoiceModuleImpl();
  return _instance;
}
export function setVoiceEngine(engine: IVoiceEngine): void { _instance = engine; }
