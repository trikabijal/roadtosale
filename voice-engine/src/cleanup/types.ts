/**
 * TextCleanup contract types — the SECOND configurable model layer in the voice
 * engine. The first is STT (`TranscriptionStrategy`); this is the cleanup LLM.
 *
 * "Code does not port; contracts, data, and learnings do." These types are the
 * language-neutral source of truth. Native platforms implement the same contract:
 *   - Apple (macOS/iOS): Foundation Models  → `FoundationModelsCleanup` (Swift)
 *   - Android:           Gemini Nano / MediaPipe LLM
 *   - Fallback (any):    rule-based (see `rule-based.ts` reference implementation)
 *
 * Field names are snake_case to stay wire-compatible with the rest of the engine
 * (`TranscriptEvent`) and the platform telemetry schema.
 */

/** How aggressively to rewrite spoken text into clean written text. */
export type CleanupLevel = 'off' | 'light' | 'full';

/** Spoken command → literal output. e.g. `{ "new paragraph": "\n\n" }`. */
export type CommandGrammar = Record<string, string>;

/** Forced spelling/replacement, applied AFTER cleanup so the LLM can't undo it. */
export type VocabMap = Record<string, string>;

export interface CleanupRequest {
  raw_text: string;
  level: CleanupLevel;
  vocab: VocabMap;
  command_grammar: CommandGrammar;
  /** Selects a prompt/data pack, e.g. "dictation" | "road-to-sale". */
  profile?: string;
}

export interface CleanupResult {
  cleaned_text: string;
  /** Transformations applied, for telemetry e.g. ["commands","fillers","punctuation"]. */
  ops_applied: string[];
  /** True when the engine fell back to rule-based (LLM unavailable / degenerate output). */
  used_fallback: boolean;
  latency_ms: number;
  engine_metadata: Record<string, unknown>;
}

export class CleanupError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'CleanupError';
  }
}
