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

// --- TextCleanup contract (second configurable model layer: the cleanup LLM) ---
export type { CleanupStrategy } from './cleanup/base.js';
export { RuleBasedCleanupStrategy } from './cleanup/rule-based.js';
export {
  registerCleanupStrategy,
  unregisterCleanupStrategy,
  getCleanupStrategy,
  listCleanupStrategies,
  bootstrapDefaultCleanupStrategies,
} from './cleanup/registry.js';
export type {
  CleanupLevel,
  CleanupRequest,
  CleanupResult,
  CommandGrammar,
  VocabMap,
} from './cleanup/types.js';
export { CleanupError } from './cleanup/types.js';
