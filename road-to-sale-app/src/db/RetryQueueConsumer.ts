import { AppState, AppStateStatus } from 'react-native';
import { useEffect } from 'react';
import { getSessionRepository } from './repositorySingleton';
import { getSmartComplyClient } from '../api/clientSingleton';
import type { ISmartComplyClient } from '../api/ISmartComplyClient';
import type { PendingWrite } from './ISessionRepository';

const MAX_RETRIES = 5;
const BACKOFF_MS = [1000, 2000, 4000, 8000, 16000];

/**
 * Drains the pending writes queue with exponential backoff.
 * Called when the app comes to foreground or after a session ends.
 */
export async function drainPendingWrites(): Promise<void> {
  const repo = getSessionRepository();
  const client = getSmartComplyClient();

  let writes: PendingWrite[];
  try {
    writes = await repo.getPendingWrites();
  } catch {
    return; // repo not ready
  }

  for (const write of writes) {
    if (write.retryCount >= MAX_RETRIES) {
      await repo.markWriteFailed(write.id).catch(() => {});
      continue;
    }

    const backoffMs = BACKOFF_MS[Math.min(write.retryCount, BACKOFF_MS.length - 1)];
    await new Promise<void>(resolve => setTimeout(resolve, backoffMs));

    try {
      const payload = JSON.parse(write.payload) as { type: string; data: unknown };
      await dispatchWrite(client, payload);
      await repo.markWriteSucceeded(write.id);
    } catch {
      await repo.incrementWriteRetry(write.id).catch(() => {});
    }
  }
}

async function dispatchWrite(
  client: ISmartComplyClient,
  payload: { type: string; data: unknown }
): Promise<void> {
  switch (payload.type) {
    case 'submitAnswers':
      await client.submitAnswers(
        payload.data as Parameters<ISmartComplyClient['submitAnswers']>[0]
      );
      break;
    case 'submitSession':
      await client.submitSession(payload.data as number);
      break;
    default:
      // Unknown payload type — mark as succeeded to clear it (don't retry forever).
      break;
  }
}

/**
 * React hook that drains the pending writes queue when the app comes to foreground.
 * Mount once at the App root level.
 */
export function useRetryQueueConsumer(): void {
  useEffect(() => {
    // Drain on mount
    drainPendingWrites().catch(() => {});

    // Drain on foreground
    const handleAppState = (nextState: AppStateStatus) => {
      if (nextState === 'active') {
        drainPendingWrites().catch(() => {});
      }
    };

    const subscription = AppState.addEventListener('change', handleAppState);
    return () => {
      subscription.remove();
    };
  }, []);
}
