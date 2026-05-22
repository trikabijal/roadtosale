export { VoiceEngine } from './facade.js';
export { CueMatcher, matchCues, matchCuesAsync } from './matcher/cue-matcher.js';
export { MockTranscriptionStrategy } from './strategies/mock.js';
export { AppleSpeechTranscriberStrategy } from './strategies/apple-speech-transcriber.js';
export type { Session, TranscriptionStrategy } from './strategies/base.js';
export {
  registerStrategy,
  getStrategy,
  listStrategies,
  unregisterStrategy,
  getRegisteredStrategies,
  bootstrapDefaultStrategies,
} from './strategies/registry.js';
export type {
  CueAtom,
  CueDetection,
  CueSource,
  SessionContext,
  Stability,
  TranscriptEvent,
  Unsubscribe,
} from './types/index.js';
export {
  UnknownStrategyError,
  TranscriptionError,
  AudioFileError,
  MicPermissionError,
  NotImplementedError,
} from './types/index.js';
