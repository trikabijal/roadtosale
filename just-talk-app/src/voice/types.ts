// Mirror of voice-engine/src/types/index.ts — must stay in sync.
// If the canonical types change, update both files and cross-check.

export type Stability = 'partial' | 'final';
export type CueSource = 'feature' | 'workflow';

export interface TranscriptEvent {
  text: string;
  stability: Stability;
  timestamp_ms: number;
  latency_ms_from_audio_start: number;
  confidence: number | null;
  engine_metadata: Record<string, unknown>;
}

export interface CueDetection {
  cue_id: string;
  matched_phrase: string;
  timestamp_ms: number;
  confidence: number | null;
  triggering_event: TranscriptEvent;
}
