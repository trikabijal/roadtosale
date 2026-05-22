import { UnknownStrategyError } from '../types/index.js';
import type { TranscriptionStrategy } from './base.js';

const registry = new Map<string, TranscriptionStrategy>();

export function registerStrategy(strategy: TranscriptionStrategy): void {
  if (!strategy.name) {
    throw new Error('Strategy.name must be a non-empty string');
  }
  registry.set(strategy.name, strategy);
}

export function unregisterStrategy(name: string): void {
  registry.delete(name);
}

export function getRegisteredStrategies(): Map<string, TranscriptionStrategy> {
  return new Map(registry);
}

export function getStrategy(name: string): TranscriptionStrategy {
  const strategy = registry.get(name);
  if (!strategy) {
    throw new UnknownStrategyError(name, Array.from(registry.keys()).sort());
  }
  return strategy;
}

export function listStrategies(): string[] {
  return Array.from(registry.keys()).sort();
}

let bootstrapped = false;

export async function bootstrapDefaultStrategies(): Promise<void> {
  if (bootstrapped) return;
  bootstrapped = true;
  const { MockTranscriptionStrategy } = await import('./mock.js');
  if (!registry.has('mock')) {
    registerStrategy(new MockTranscriptionStrategy([]));
  }
}
