import type { CleanupRequest, CleanupResult } from './types.js';

/**
 * A configurable cleanup model behind a stable contract.
 *
 * Mirrors `TranscriptionStrategy` (STT): each implementation is selected by
 * `name` (the provider id) and chosen at runtime via `{ provider, model }` config.
 */
export interface CleanupStrategy {
  /** Provider id, e.g. "foundation-models" | "gemini-nano" | "rule-based". */
  readonly name: string;
  clean(request: CleanupRequest): Promise<CleanupResult>;
}
