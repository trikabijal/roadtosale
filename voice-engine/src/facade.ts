import { matchCuesAsync } from './matcher/cue-matcher.js';
import type { TranscriptionStrategy, Session } from './strategies/base.js';
import {
  bootstrapDefaultStrategies,
  getStrategy,
  listStrategies,
  registerStrategy,
} from './strategies/registry.js';
import type {
  CueAtom,
  CueDetection,
  SessionContext,
  TranscriptEvent,
} from './types/index.js';

/**
 * VoiceEngine — public TS facade.
 *
 * Binding contract: see voice-engine/docs/api.md.
 */
export class VoiceEngine {
  private constructor() {}

  static load(): VoiceEngine {
    // Fire and forget — registry bootstrap is idempotent.
    void bootstrapDefaultStrategies();
    return new VoiceEngine();
  }

  listStrategies(): string[] {
    return listStrategies();
  }

  getStrategy(name: string): TranscriptionStrategy {
    return getStrategy(name);
  }

  /** Register an additional strategy at runtime (used by tests + library consumers). */
  registerStrategy(strategy: TranscriptionStrategy): void {
    registerStrategy(strategy);
  }

  startSession(strategyName: string, context: SessionContext): Session {
    const strategy = getStrategy(strategyName);
    return strategy.start(context);
  }

  matchCues(
    events: AsyncIterable<TranscriptEvent>,
    cueAtoms: CueAtom[],
  ): AsyncIterable<CueDetection> {
    return matchCuesAsync(events, cueAtoms);
  }
}
