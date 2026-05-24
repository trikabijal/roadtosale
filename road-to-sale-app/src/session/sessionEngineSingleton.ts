/**
 * sessionEngineSingleton.ts
 *
 * Lazy singleton for SessionEngine. All screens import from here — never
 * instantiate SessionEngine directly.
 *
 * The `setSessionEngine()` override is provided for test injection.
 */

import { SessionEngine } from './SessionEngine';

let _engine: SessionEngine | null = null;

export function getSessionEngine(): SessionEngine {
  if (!_engine) {
    _engine = new SessionEngine();
  }
  return _engine;
}

/** Override for tests — pass null to reset to the real implementation. */
export function setSessionEngine(engine: SessionEngine | null): void {
  _engine = engine;
}
