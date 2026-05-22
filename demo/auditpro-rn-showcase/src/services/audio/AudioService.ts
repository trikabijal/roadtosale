export type Speaker = 'salesperson' | 'customer' | 'manager' | 'unknown';

export type TranscriptEvent = {
  text: string;
  trigger?: string;
  confidence?: number;
  speaker?: Speaker;
};

export type DetectionEvent = {
  stepId: string;
  type:
    | 'auto-confirm'
    | 'feature-pass'
    | 'feature-fail'
    | 'voice-detected'
    | 'note-extracted'
    | 'usecase-detected';
  payload?: Record<string, unknown>;
};

export type AudioStartContext = {
  screenId: string;
  stepId: string;
};

export type Unsubscribe = () => void;

// A single capture window. Each call to AudioService.start() returns its own
// session — concurrent screens (e.g. a screen and a modal opened on top of
// it) get isolated event delivery and independent stop().
export interface AudioSession {
  onTranscript(handler: (event: TranscriptEvent) => void): Unsubscribe;
  onDetection(handler: (event: DetectionEvent) => void): Unsubscribe;
  stop(): Promise<void>;
}

export interface AudioService {
  start(context: AudioStartContext): Promise<AudioSession>;
}
