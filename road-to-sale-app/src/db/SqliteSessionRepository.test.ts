import type { Session } from '../session/types';

// ─── Mock expo-sqlite ─────────────────────────────────────────────────────────

const mockDb = {
  execAsync: jest.fn(),
  runAsync: jest.fn(),
  getFirstAsync: jest.fn(),
  getAllAsync: jest.fn(),
};

jest.mock('expo-sqlite', () => ({
  openDatabaseAsync: jest.fn(),
}));

// Must import after mock is defined
import { SqliteSessionRepository } from './SqliteSessionRepository';
import { _resetDbForTests } from './schema';

// ─── Helpers ──────────────────────────────────────────────────────────────────

function makeSession(overrides: Partial<Session> = {}): Session {
  return {
    id: 'session-1',
    smartComplyAssignmentId: 42,
    customer: { firstName: 'Alice', lastName: 'Smith' },
    vehicle: {
      makeId: 'honda',
      makeName: 'Honda',
      modelId: 'honda.accord',
      modelName: 'Accord',
      trimId: 'honda.accord.2026.lx',
      trimName: 'LX',
      year: 2026,
    },
    checksheetId: 7,
    status: 'active',
    startedAt: new Date('2026-05-24T10:00:00.000Z'),
    checklist: {
      steps: [],
      completedCount: 0,
      totalCount: 0,
      lastUpdatedMs: 0,
    },
    ...overrides,
  };
}

// ─── Setup / teardown ─────────────────────────────────────────────────────────

beforeEach(() => {
  // Reset call history but keep implementations alive
  mockDb.execAsync.mockReset().mockResolvedValue(undefined);
  mockDb.runAsync.mockReset().mockResolvedValue({ changes: 1, lastInsertRowId: 1 });
  mockDb.getFirstAsync.mockReset().mockResolvedValue(null);
  mockDb.getAllAsync.mockReset().mockResolvedValue([]);

  const { openDatabaseAsync } = jest.requireMock<{ openDatabaseAsync: jest.Mock }>('expo-sqlite');
  openDatabaseAsync.mockReset().mockResolvedValue(mockDb);

  // Force schema singleton to re-open on next getDb() call
  _resetDbForTests();
});

// ─── Tests ────────────────────────────────────────────────────────────────────

describe('SqliteSessionRepository', () => {
  describe('createSession()', () => {
    it('calls db.runAsync with an INSERT statement', async () => {
      const repo = new SqliteSessionRepository();
      const session = makeSession();

      await repo.createSession(session);

      expect(mockDb.runAsync).toHaveBeenCalledTimes(1);
      const [sql, params] = mockDb.runAsync.mock.calls[0];
      expect(sql).toMatch(/INSERT INTO sessions/i);
      expect(params[0]).toBe('session-1');
      expect(params[1]).toBe(42);
    });

    it('JSON-stringifies customer and vehicle fields', async () => {
      const repo = new SqliteSessionRepository();
      const session = makeSession();

      await repo.createSession(session);

      const params = mockDb.runAsync.mock.calls[0][1];
      expect(JSON.parse(params[3])).toEqual(session.customer);
      expect(JSON.parse(params[4])).toEqual(session.vehicle);
    });
  });

  describe('getSession()', () => {
    it('returns null when db returns null', async () => {
      mockDb.getFirstAsync.mockResolvedValueOnce(null);
      const repo = new SqliteSessionRepository();

      const result = await repo.getSession('nonexistent');

      expect(result).toBeNull();
    });

    it('parses JSON fields correctly when a row is returned', async () => {
      const session = makeSession();
      const row: Record<string, unknown> = {
        id: session.id,
        smart_comply_assignment_id: session.smartComplyAssignmentId,
        smart_comply_uc_id: null,
        customer_json: JSON.stringify(session.customer),
        vehicle_json: JSON.stringify(session.vehicle),
        checksheet_id: session.checksheetId,
        status: session.status,
        started_at: session.startedAt.toISOString(),
        ended_at: null,
        checklist_json: JSON.stringify(session.checklist),
        trade_in_json: null,
        session_note: null,
      };
      mockDb.getFirstAsync.mockResolvedValueOnce(row);

      const repo = new SqliteSessionRepository();
      const result = await repo.getSession(session.id);

      expect(result).not.toBeNull();
      expect(result!.id).toBe(session.id);
      expect(result!.customer).toEqual(session.customer);
      expect(result!.vehicle).toEqual(session.vehicle);
      expect(result!.startedAt).toEqual(session.startedAt);
      expect(result!.smartComplyUserChecksheetId).toBeUndefined();
      expect(result!.tradeIn).toBeUndefined();
    });

    it('parses smartComplyUserChecksheetId when present', async () => {
      const session = makeSession({ smartComplyUserChecksheetId: 99 });
      const row: Record<string, unknown> = {
        id: session.id,
        smart_comply_assignment_id: session.smartComplyAssignmentId,
        smart_comply_uc_id: 99,
        customer_json: JSON.stringify(session.customer),
        vehicle_json: JSON.stringify(session.vehicle),
        checksheet_id: session.checksheetId,
        status: session.status,
        started_at: session.startedAt.toISOString(),
        ended_at: null,
        checklist_json: JSON.stringify(session.checklist),
        trade_in_json: null,
        session_note: null,
      };
      mockDb.getFirstAsync.mockResolvedValueOnce(row);

      const repo = new SqliteSessionRepository();
      const result = await repo.getSession(session.id);

      expect(result!.smartComplyUserChecksheetId).toBe(99);
    });
  });

  describe('queuePendingWrite()', () => {
    it('returns a non-empty string ID', async () => {
      const repo = new SqliteSessionRepository();
      const id = await repo.queuePendingWrite('session-1', { foo: 'bar' });

      expect(typeof id).toBe('string');
      expect(id.length).toBeGreaterThan(0);
    });

    it('calls db.runAsync with INSERT INTO pending_writes', async () => {
      const repo = new SqliteSessionRepository();
      await repo.queuePendingWrite('session-1', { action: 'submit' });

      expect(mockDb.runAsync).toHaveBeenCalledTimes(1);
      const [sql] = mockDb.runAsync.mock.calls[0];
      expect(sql).toMatch(/INSERT INTO pending_writes/i);
    });
  });

  describe('getPendingWrites()', () => {
    it('maps rows to PendingWrite objects', async () => {
      const rows = [
        {
          id: 'pw-1',
          session_id: 'session-1',
          retry_count: 2,
          last_attempted_at: '2026-05-24T09:00:00.000Z',
          status: 'queued',
          payload: JSON.stringify({ data: 'test' }),
        },
      ];
      mockDb.getAllAsync.mockResolvedValueOnce(rows);

      const repo = new SqliteSessionRepository();
      const writes = await repo.getPendingWrites();

      expect(writes).toHaveLength(1);
      expect(writes[0].id).toBe('pw-1');
      expect(writes[0].sessionId).toBe('session-1');
      expect(writes[0].retryCount).toBe(2);
      expect(writes[0].lastAttemptedAt).toEqual(new Date('2026-05-24T09:00:00.000Z'));
      expect(writes[0].status).toBe('queued');
      expect(writes[0].payload).toBe(JSON.stringify({ data: 'test' }));
    });

    it('sets lastAttemptedAt to null when column is null', async () => {
      const rows = [
        {
          id: 'pw-2',
          session_id: 'session-2',
          retry_count: 0,
          last_attempted_at: null,
          status: 'queued',
          payload: '{}',
        },
      ];
      mockDb.getAllAsync.mockResolvedValueOnce(rows);

      const repo = new SqliteSessionRepository();
      const writes = await repo.getPendingWrites();

      expect(writes[0].lastAttemptedAt).toBeNull();
    });
  });

  describe('markWriteSucceeded()', () => {
    it('calls DELETE on the pending_writes table', async () => {
      const repo = new SqliteSessionRepository();
      await repo.markWriteSucceeded('pw-1');

      expect(mockDb.runAsync).toHaveBeenCalledTimes(1);
      const [sql, params] = mockDb.runAsync.mock.calls[0];
      expect(sql).toMatch(/DELETE FROM pending_writes/i);
      expect(params[0]).toBe('pw-1');
    });
  });

  describe('incrementWriteRetry()', () => {
    it('runs an UPDATE that bumps retry_count and stamps last_attempted_at', async () => {
      const repo = new SqliteSessionRepository();
      await repo.incrementWriteRetry('pw-1');

      expect(mockDb.runAsync).toHaveBeenCalledTimes(1);
      const [sql, params] = mockDb.runAsync.mock.calls[0];
      expect(sql).toMatch(/UPDATE pending_writes/i);
      expect(sql).toMatch(/retry_count\s*=\s*retry_count\s*\+\s*1/i);
      expect(sql).toMatch(/last_attempted_at\s*=/i);
      expect(params[0]).toBe('pw-1');
    });
  });

  describe('markWriteFailed()', () => {
    it("runs an UPDATE that sets status='failed'", async () => {
      const repo = new SqliteSessionRepository();
      await repo.markWriteFailed('pw-9');

      expect(mockDb.runAsync).toHaveBeenCalledTimes(1);
      const [sql, params] = mockDb.runAsync.mock.calls[0];
      expect(sql).toMatch(/UPDATE pending_writes/i);
      expect(sql).toMatch(/status\s*=\s*'failed'/i);
      expect(params[0]).toBe('pw-9');
    });
  });

  describe('updateSession()', () => {
    it('persists a status transition via an UPDATE on the matching id', async () => {
      const repo = new SqliteSessionRepository();
      await repo.updateSession({ id: 'session-1', status: 'ended' });

      expect(mockDb.runAsync).toHaveBeenCalledTimes(1);
      const [sql, params] = mockDb.runAsync.mock.calls[0];
      expect(sql).toMatch(/UPDATE sessions SET/i);
      expect(sql).toMatch(/status = \?/i);
      // 'ended' is bound, and the id is always the last bound param (WHERE id = ?)
      expect(params).toContain('ended');
      expect(params[params.length - 1]).toBe('session-1');
    });

    it('persists the tradeIn blob as JSON', async () => {
      const repo = new SqliteSessionRepository();
      const tradeIn = { photos: { front_left: 'file:///x.jpg' }, spokenNotes: [] };
      await repo.updateSession({ id: 'session-1', tradeIn });

      const [sql, params] = mockDb.runAsync.mock.calls[0];
      expect(sql).toMatch(/trade_in_json = \?/i);
      // The serialized blob is one of the bound params.
      const jsonParam = params.find(
        (p: unknown) => typeof p === 'string' && p.includes('front_left'),
      );
      expect(jsonParam).toBeDefined();
      expect(JSON.parse(jsonParam as string)).toEqual(tradeIn);
    });
  });

  describe('getActiveSessions()', () => {
    it("queries only status='active' rows and maps them", async () => {
      const session = makeSession();
      const row: Record<string, unknown> = {
        id: session.id,
        smart_comply_assignment_id: session.smartComplyAssignmentId,
        smart_comply_uc_id: null,
        customer_json: JSON.stringify(session.customer),
        vehicle_json: JSON.stringify(session.vehicle),
        checksheet_id: session.checksheetId,
        status: 'active',
        started_at: session.startedAt.toISOString(),
        ended_at: null,
        checklist_json: JSON.stringify(session.checklist),
        trade_in_json: null,
        session_note: null,
      };
      mockDb.getAllAsync.mockResolvedValueOnce([row]);

      const repo = new SqliteSessionRepository();
      const result = await repo.getActiveSessions();

      const [sql] = mockDb.getAllAsync.mock.calls[0];
      expect(sql).toMatch(/status\s*=\s*'active'/i);
      expect(result).toHaveLength(1);
      expect(result[0].id).toBe(session.id);
      expect(result[0].status).toBe('active');
    });

    it('returns an empty array when no active sessions exist', async () => {
      mockDb.getAllAsync.mockResolvedValueOnce([]);
      const repo = new SqliteSessionRepository();
      expect(await repo.getActiveSessions()).toEqual([]);
    });
  });

  describe('cacheTemplate() / getCachedTemplate()', () => {
    it('round-trips template data correctly', async () => {
      const repo = new SqliteSessionRepository();
      const data = { id: 1, name: 'Test Template', headers: [] };

      // Cache it
      await repo.cacheTemplate(1, '"abc123"', data);

      expect(mockDb.runAsync).toHaveBeenCalledTimes(1);
      const [insertSql, insertParams] = mockDb.runAsync.mock.calls[0];
      expect(insertSql).toMatch(/INSERT OR REPLACE INTO template_cache/i);
      expect(insertParams[0]).toBe(1);
      expect(insertParams[1]).toBe('"abc123"');
      expect(JSON.parse(insertParams[2])).toEqual(data);

      // Retrieve it
      const storedRow = {
        template_id: 1,
        etag: '"abc123"',
        fetched_at: '2026-05-24T10:00:00.000Z',
        template_json: JSON.stringify(data),
      };
      mockDb.getFirstAsync.mockResolvedValueOnce(storedRow);

      const result = await repo.getCachedTemplate(1);

      expect(result).not.toBeNull();
      expect(result!.etag).toBe('"abc123"');
      expect(result!.data).toEqual(data);
      expect(result!.fetchedAt).toEqual(new Date('2026-05-24T10:00:00.000Z'));
    });

    it('returns null for cache miss', async () => {
      mockDb.getFirstAsync.mockResolvedValueOnce(null);
      const repo = new SqliteSessionRepository();
      const result = await repo.getCachedTemplate(999);
      expect(result).toBeNull();
    });
  });

  describe('cacheAppointments() / getCachedAppointments()', () => {
    it('round-trips appointments data correctly', async () => {
      const repo = new SqliteSessionRepository();
      const appts = [{ id: 1 }, { id: 2 }];

      await repo.cacheAppointments('2026-05-24', 'rep-7', appts);

      const [insertSql, insertParams] = mockDb.runAsync.mock.calls[0];
      expect(insertSql).toMatch(/INSERT OR REPLACE INTO appointments_cache/i);
      expect(insertParams[0]).toBe('2026-05-24::rep-7');

      const storedRow = {
        cache_key: '2026-05-24::rep-7',
        fetched_at: '2026-05-24T10:00:00.000Z',
        appointments_json: JSON.stringify(appts),
      };
      mockDb.getFirstAsync.mockResolvedValueOnce(storedRow);

      const result = await repo.getCachedAppointments('2026-05-24', 'rep-7');

      expect(result).not.toBeNull();
      expect(result!.data).toEqual(appts);
      expect(result!.fetchedAt).toEqual(new Date('2026-05-24T10:00:00.000Z'));
    });

    it('returns null for cache miss', async () => {
      mockDb.getFirstAsync.mockResolvedValueOnce(null);
      const repo = new SqliteSessionRepository();
      const result = await repo.getCachedAppointments('2026-01-01', 'rep-0');
      expect(result).toBeNull();
    });
  });
});
