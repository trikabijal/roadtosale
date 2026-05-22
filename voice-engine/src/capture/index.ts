/**
 * Audio capture subsystem — placeholder.
 *
 * Mic capture, permissions, rolling buffer, chunk distribution. The
 * full capture layer is a future PRD. For v1 the interface shape is
 * sketched here so strategy code can compile against stable type names.
 */

export interface AudioCaptureChunk {
  pcm: Float32Array;
  sample_rate_hz: number;
  timestamp_ms: number;
}

export interface AudioCapture {
  start(): Promise<void>;
  stop(): Promise<void>;
  onChunk(handler: (chunk: AudioCaptureChunk) => void): () => void;
}

// TODO: native module bridge — see ios/Sources/AppleSpeechTranscriberModule.swift
// and android/src/main/kotlin/com/auditpro/voiceengine/Stub.kt for placeholders.
