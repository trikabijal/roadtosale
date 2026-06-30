/**
 * Unit tests for RetryQueueConsumer — the offline drain / exponential backoff /
 * 5-retry circuit breaker the app's offline-first reliability rests on.
 *
 * The two singletons it pulls from (`getSessionRepository`, `getSmartComplyClient`)
 * are replaced with fakes via their `set…()` test seams. Backoff is asserted with
 * fake timers — no real waiting.
 *
 * Covers C-RQ-1, C-RQ-2, C-RQ-3, C-RQ-4 (circuit breaker), C-RQ-5 (backoff), C-RQ-6.
 */

// repositorySingleton → SqliteSessionRepository → schema imports expo-sqlite at
// module load. We never exercise the real repo here (a fake is injected via
// setSessionRepository), but the import must resolve, so stub the native module.
jest.mock('expo-sqlite', () => ({ openDatabaseAsync: jest.fn() }));

import { drainPendingWrites } from './RetryQueueConsumer';
import { setSessionRepository } from './repositorySingleton';
import { setSmartComplyClient } from '../api/clientSingleton';
import type { ISessionRepository, PendingWrite } from './ISessionRepository';
import type { ISmartComplyClient } from '../api/ISmartComplyClient';

// ─── Fakes ──────────────────────────────────────────────────────────────────

function makeRepo(overrides: Partial<ISessionRepository> = {}): ISessionRepository {
  return {
    createSession: jest.fn(),
    updateSession: jest.fn(),
    getSession: jest.fn(),
    getAllSessions: jest.fn(),
    getActiveSessions: jest.fn(),
    queuePendingWrite: jest.fn(),
    getPendingWrites: jest.fn().mockResolvedValue([]),
    markWriteSucceeded: jest.fn().mockResolvedValue(undefined),
    incrementWriteRetry: jest.fn().mockResolvedValue(undefined),
    markWriteFailed: jest.fn().mockResolvedValue(undefined),
    cacheTemplate: jest.fn(),
    getCachedTemplate: jest.fn(),
    cacheAppointments: jest.fn(),
    getCachedAppointments: jest.fn(),
    ...overrides,
  };
}

function makeClient(overrides: Partial<ISmartComplyClient> = {}): ISmartComplyClient {
  return {
    login: jest.fn(),
    refreshToken: jest.fn(),
    logout: jest.fn(),
    getMyAssignments: jest.fn(),
    createWalkInAssignment: jest.fn(),
    getChecksheetDetail: jest.fn(),
    startOrResumeSession: jest.fn(),
    submitSession: jest.fn().mockResolvedValue(undefined),
    submitAnswer: jest.fn(),
    submitAnswers: jest.fn().mockResolvedValue(undefined),
    uploadTradePhoto: jest.fn(),
    getTradePhotos: jest.fn(),
    ...overrides,
  };
}

function makeWrite(overrides: Partial<PendingWrite> = {}): PendingWrite {
  return {
    id: 'pw-1',
    sessionId: 'session-1',
    retryCount: 0,
    lastAttemptedAt: null,
    status: 'queued',
    payload: JSON.stringify({ type: 'submitAnswers', data: [] }),
    ...overrides,
  };
}

/**
 * drainPendingWrites awaits a setTimeout (backoff) before each dispatch. With fake
 * timers we must repeatedly flush microtasks + advance pending timers until the
 * whole loop settles, then await the returned promise.
 */
async function runDrainWithFakeTimers(drainPromise: Promise<void>): Promise<void> {
  // Loop: let pending microtasks run, advance any scheduled timer, repeat.
  for (let i = 0; i < 50; i++) {
    await Promise.resolve();
    await Promise.resolve();
    if (jest.getTimerCount() > 0) {
      jest.advanceTimersByTime(20000);
    }
  }
  await drainPromise;
}

// ─── Tests ────────────────────────────────────────────────────────────────────

describe('RetryQueueConsumer.drainPendingWrites', () => {
  afterEach(() => {
    jest.useRealTimers();
    jest.clearAllMocks();
  });

  // ── C-RQ-1: drain → dispatch → delete ─────────────────────────────────────

  describe('successful drain (C-RQ-1)', () => {
    it('dispatches a queued submitAnswers payload then markWriteSucceeded (row deleted)', async () => {
      jest.useFakeTimers();
      const answers = [{ userChecksheetId: 1, chksQuestionId: 101 }];
      const repo = makeRepo({
        getPendingWrites: jest.fn().mockResolvedValue([
          makeWrite({ payload: JSON.stringify({ type: 'submitAnswers', data: answers }) }),
        ]),
      });
      const client = makeClient();
      setSessionRepository(repo);
      setSmartComplyClient(client);

      await runDrainWithFakeTimers(drainPendingWrites());

      expect(client.submitAnswers).toHaveBeenCalledWith(answers);
      expect(repo.markWriteSucceeded).toHaveBeenCalledWith('pw-1');
      expect(repo.incrementWriteRetry).not.toHaveBeenCalled();
      expect(repo.markWriteFailed).not.toHaveBeenCalled();
    });
  });

  // ── C-RQ-2: dispatch-by-type ──────────────────────────────────────────────

  describe('dispatch by payload.type (C-RQ-2)', () => {
    it('routes a submitSession payload to client.submitSession', async () => {
      jest.useFakeTimers();
      const repo = makeRepo({
        getPendingWrites: jest.fn().mockResolvedValue([
          makeWrite({ payload: JSON.stringify({ type: 'submitSession', data: 77 }) }),
        ]),
      });
      const client = makeClient();
      setSessionRepository(repo);
      setSmartComplyClient(client);

      await runDrainWithFakeTimers(drainPendingWrites());

      expect(client.submitSession).toHaveBeenCalledWith(77);
      expect(client.submitAnswers).not.toHaveBeenCalled();
      expect(repo.markWriteSucceeded).toHaveBeenCalledWith('pw-1');
    });

    it('drops an unknown payload type without calling a client method, then clears the row', async () => {
      jest.useFakeTimers();
      const repo = makeRepo({
        getPendingWrites: jest.fn().mockResolvedValue([
          makeWrite({ payload: JSON.stringify({ type: 'mysteryType', data: {} }) }),
        ]),
      });
      const client = makeClient();
      setSessionRepository(repo);
      setSmartComplyClient(client);

      await runDrainWithFakeTimers(drainPendingWrites());

      expect(client.submitAnswers).not.toHaveBeenCalled();
      expect(client.submitSession).not.toHaveBeenCalled();
      // Unknown type is a no-op dispatch → treated as success so it doesn't loop forever.
      expect(repo.markWriteSucceeded).toHaveBeenCalledWith('pw-1');
    });
  });

  // ── C-RQ-3: failure → increment ───────────────────────────────────────────

  describe('failed dispatch (C-RQ-3)', () => {
    it('increments the retry count instead of deleting when the client throws', async () => {
      jest.useFakeTimers();
      const repo = makeRepo({
        getPendingWrites: jest.fn().mockResolvedValue([
          makeWrite({ payload: JSON.stringify({ type: 'submitSession', data: 5 }) }),
        ]),
      });
      const client = makeClient({
        submitSession: jest.fn().mockRejectedValue(new Error('network down')),
      });
      setSessionRepository(repo);
      setSmartComplyClient(client);

      await runDrainWithFakeTimers(drainPendingWrites());

      expect(repo.incrementWriteRetry).toHaveBeenCalledWith('pw-1');
      expect(repo.markWriteSucceeded).not.toHaveBeenCalled();
      expect(repo.markWriteFailed).not.toHaveBeenCalled();
    });
  });

  // ── C-RQ-4: circuit breaker ───────────────────────────────────────────────

  describe('circuit breaker at retryCount >= 5 (C-RQ-4)', () => {
    it('marks a write at retryCount 5 as failed and does NOT dispatch it', async () => {
      jest.useFakeTimers();
      const repo = makeRepo({
        getPendingWrites: jest.fn().mockResolvedValue([
          makeWrite({ retryCount: 5, payload: JSON.stringify({ type: 'submitSession', data: 9 }) }),
        ]),
      });
      const client = makeClient();
      setSessionRepository(repo);
      setSmartComplyClient(client);

      await runDrainWithFakeTimers(drainPendingWrites());

      expect(repo.markWriteFailed).toHaveBeenCalledWith('pw-1');
      expect(client.submitSession).not.toHaveBeenCalled();
      expect(client.submitAnswers).not.toHaveBeenCalled();
      expect(repo.markWriteSucceeded).not.toHaveBeenCalled();
    });

    it('does NOT trip the breaker at retryCount 4 — still dispatches', async () => {
      jest.useFakeTimers();
      const repo = makeRepo({
        getPendingWrites: jest.fn().mockResolvedValue([
          makeWrite({ retryCount: 4, payload: JSON.stringify({ type: 'submitSession', data: 9 }) }),
        ]),
      });
      const client = makeClient();
      setSessionRepository(repo);
      setSmartComplyClient(client);

      await runDrainWithFakeTimers(drainPendingWrites());

      expect(client.submitSession).toHaveBeenCalledWith(9);
      expect(repo.markWriteFailed).not.toHaveBeenCalled();
    });
  });

  // ── C-RQ-5: backoff schedule ──────────────────────────────────────────────

  describe('exponential backoff schedule (C-RQ-5)', () => {
    it.each([
      [0, 1000],
      [1, 2000],
      [2, 4000],
      [3, 8000],
      [4, 16000],
    ])('waits the [1s,2s,4s,8s,16s] backoff for retryCount=%i before dispatching', async (retryCount, expectedMs) => {
      jest.useFakeTimers();
      const repo = makeRepo({
        getPendingWrites: jest.fn().mockResolvedValue([
          makeWrite({ retryCount, payload: JSON.stringify({ type: 'submitSession', data: 1 }) }),
        ]),
      });
      const client = makeClient();
      setSessionRepository(repo);
      setSmartComplyClient(client);

      const drainPromise = drainPendingWrites();
      // Let getPendingWrites resolve and the setTimeout be scheduled.
      await Promise.resolve();
      await Promise.resolve();

      // Just before the backoff elapses, nothing should have dispatched.
      jest.advanceTimersByTime(expectedMs - 1);
      await Promise.resolve();
      expect(client.submitSession).not.toHaveBeenCalled();

      // Cross the threshold → dispatch fires.
      jest.advanceTimersByTime(1);
      await runDrainWithFakeTimers(drainPromise);
      expect(client.submitSession).toHaveBeenCalledWith(1);
    });
  });

  // ── C-RQ-6: safe no-op when repo not ready ────────────────────────────────

  describe('repo not ready (C-RQ-6)', () => {
    it('is a safe no-op when getPendingWrites throws (no unhandled rejection, no dispatch)', async () => {
      jest.useFakeTimers();
      const repo = makeRepo({
        getPendingWrites: jest.fn().mockRejectedValue(new Error('db not open')),
      });
      const client = makeClient();
      setSessionRepository(repo);
      setSmartComplyClient(client);

      await expect(drainPendingWrites()).resolves.toBeUndefined();
      expect(client.submitAnswers).not.toHaveBeenCalled();
      expect(client.submitSession).not.toHaveBeenCalled();
    });

    it('drains nothing (no client calls) when the queue is empty', async () => {
      jest.useFakeTimers();
      const repo = makeRepo({ getPendingWrites: jest.fn().mockResolvedValue([]) });
      const client = makeClient();
      setSessionRepository(repo);
      setSmartComplyClient(client);

      await runDrainWithFakeTimers(drainPendingWrites());

      expect(client.submitAnswers).not.toHaveBeenCalled();
      expect(repo.markWriteSucceeded).not.toHaveBeenCalled();
    });
  });

  // ── Multi-write ordering ──────────────────────────────────────────────────

  describe('drain order', () => {
    it('processes multiple writes in the order returned by getPendingWrites', async () => {
      jest.useFakeTimers();
      const order: number[] = [];
      const repo = makeRepo({
        getPendingWrites: jest.fn().mockResolvedValue([
          makeWrite({ id: 'a', payload: JSON.stringify({ type: 'submitSession', data: 1 }) }),
          makeWrite({ id: 'b', payload: JSON.stringify({ type: 'submitSession', data: 2 }) }),
          makeWrite({ id: 'c', payload: JSON.stringify({ type: 'submitSession', data: 3 }) }),
        ]),
      });
      const client = makeClient({
        submitSession: jest.fn(async (id: number) => { order.push(id); return undefined as never; }),
      });
      setSessionRepository(repo);
      setSmartComplyClient(client);

      await runDrainWithFakeTimers(drainPendingWrites());

      expect(order).toEqual([1, 2, 3]);
      expect(repo.markWriteSucceeded).toHaveBeenCalledTimes(3);
    });
  });
});
