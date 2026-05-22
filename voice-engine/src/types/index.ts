/**
 * Shared types for the voice engine TS library.
 *
 * Field-identical with the Python dataclasses in
 * `voice-engine/lab/src/voice_lab/types.py`. A drift test in the lab
 * (`tests/test_drift.py`) enforces this.
 */

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

export interface CueAtom {
  id: string;
  display_name: string;
  source: CueSource;
  cue_phrases: string[];
  synonyms: string[];
  metadata: Record<string, unknown>;
}

export interface CueDetection {
  cue_id: string;
  matched_phrase: string;
  timestamp_ms: number;
  confidence: number | null;
  triggering_event: TranscriptEvent;
}

export interface SessionContext {
  session_id: string;
  language: string;
  custom_vocabulary: string[];
}

export type Unsubscribe = () => void;

export class UnknownStrategyError extends Error {
  constructor(name: string, registered: string[]) {
    super(`Unknown strategy '${name}'. Registered: ${JSON.stringify(registered)}`);
    this.name = 'UnknownStrategyError';
  }
}

export class TranscriptionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'TranscriptionError';
  }
}

export class AudioFileError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AudioFileError';
  }
}

export class MicPermissionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'MicPermissionError';
  }
}

export class NotImplementedError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'NotImplementedError';
  }
}
