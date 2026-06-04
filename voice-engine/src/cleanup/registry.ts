import { UnknownStrategyError } from '../types/index.js';
import type { CleanupStrategy } from './base.js';

/**
 * Cleanup-strategy registry. Mirrors the STT strategy registry so both
 * configurable model layers behave identically.
 */
const registry = new Map<string, CleanupStrategy>();

export function registerCleanupStrategy(strategy: CleanupStrategy): void {
  if (!strategy.name) {
    throw new Error('CleanupStrategy.name must be a non-empty string');
  }
  registry.set(strategy.name, strategy);
}

export function unregisterCleanupStrategy(name: string): void {
  registry.delete(name);
}

export function getCleanupStrategy(name: string): CleanupStrategy {
  const strategy = registry.get(name);
  if (!strategy) {
    throw new UnknownStrategyError(name, Array.from(registry.keys()).sort());
  }
  return strategy;
}

export function listCleanupStrategies(): string[] {
  return Array.from(registry.keys()).sort();
}

let bootstrapped = false;

/** Registers the always-available rule-based fallback. Idempotent. */
export async function bootstrapDefaultCleanupStrategies(): Promise<void> {
  if (bootstrapped) return;
  bootstrapped = true;
  const { RuleBasedCleanupStrategy } = await import('./rule-based.js');
  if (!registry.has('rule-based')) {
    registerCleanupStrategy(new RuleBasedCleanupStrategy());
  }
}
