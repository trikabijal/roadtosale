import type { ISessionRepository, PendingWrite } from './ISessionRepository';
import type { Session } from '../session/types';
import { getDb } from './schema';

type BindValue = string | number | null | boolean;

function generateId(): string {
  return (
    Math.random().toString(36).slice(2) +
    Math.random().toString(36).slice(2) +
    Date.now().toString(36)
  );
}

export class SqliteSessionRepository implements ISessionRepository {
  // ─── Sessions ────────────────────────────────────────────────────────────────

  async createSession(session: Session): Promise<void> {
    const db = await getDb();
    await db.runAsync(
      `INSERT INTO sessions (
        id, smart_comply_assignment_id, smart_comply_uc_id,
        customer_json, vehicle_json, checksheet_id,
        status, started_at, ended_at,
        checklist_json, trade_in_json, session_note,
        created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now'), datetime('now'))`,
      [
        session.id,
        session.smartComplyAssignmentId,
        session.smartComplyUserChecksheetId ?? null,
        JSON.stringify(session.customer),
        JSON.stringify(session.vehicle),
        session.checksheetId,
        session.status,
        session.startedAt.toISOString(),
        session.endedAt ? session.endedAt.toISOString() : null,
        JSON.stringify(session.checklist),
        session.tradeIn ? JSON.stringify(session.tradeIn) : null,
        session.sessionNote ?? null,
      ],
    );
  }

  async updateSession(session: Partial<Session> & { id: string }): Promise<void> {
    const db = await getDb();

    const setClauses: string[] = ['updated_at = datetime(\'now\')'];
    const params: BindValue[] = [];

    if (session.smartComplyAssignmentId !== undefined) {
      setClauses.push('smart_comply_assignment_id = ?');
      params.push(session.smartComplyAssignmentId);
    }
    if (session.smartComplyUserChecksheetId !== undefined) {
      setClauses.push('smart_comply_uc_id = ?');
      params.push(session.smartComplyUserChecksheetId);
    }
    if (session.customer !== undefined) {
      setClauses.push('customer_json = ?');
      params.push(JSON.stringify(session.customer));
    }
    if (session.vehicle !== undefined) {
      setClauses.push('vehicle_json = ?');
      params.push(JSON.stringify(session.vehicle));
    }
    if (session.checksheetId !== undefined) {
      setClauses.push('checksheet_id = ?');
      params.push(session.checksheetId);
    }
    if (session.status !== undefined) {
      setClauses.push('status = ?');
      params.push(session.status);
    }
    if (session.startedAt !== undefined) {
      setClauses.push('started_at = ?');
      params.push(session.startedAt.toISOString());
    }
    if (session.endedAt !== undefined) {
      setClauses.push('ended_at = ?');
      params.push(session.endedAt ? session.endedAt.toISOString() : null);
    }
    if (session.checklist !== undefined) {
      setClauses.push('checklist_json = ?');
      params.push(JSON.stringify(session.checklist));
    }
    if (session.tradeIn !== undefined) {
      setClauses.push('trade_in_json = ?');
      params.push(session.tradeIn ? JSON.stringify(session.tradeIn) : null);
    }
    if (session.sessionNote !== undefined) {
      setClauses.push('session_note = ?');
      params.push(session.sessionNote ?? null);
    }

    params.push(session.id);

    await db.runAsync(
      `UPDATE sessions SET ${setClauses.join(', ')} WHERE id = ?`,
      params,
    );
  }

  async getSession(id: string): Promise<Session | null> {
    const db = await getDb();
    const row = await db.getFirstAsync<Record<string, unknown>>(
      'SELECT * FROM sessions WHERE id = ?',
      [id],
    );
    if (!row) return null;
    return this.rowToSession(row);
  }

  async getAllSessions(limit = 50): Promise<Session[]> {
    const db = await getDb();
    const rows = await db.getAllAsync<Record<string, unknown>>(
      'SELECT * FROM sessions ORDER BY started_at DESC LIMIT ?',
      [limit],
    );
    return rows.map(r => this.rowToSession(r));
  }

  async getActiveSessions(): Promise<Session[]> {
    const db = await getDb();
    const rows = await db.getAllAsync<Record<string, unknown>>(
      "SELECT * FROM sessions WHERE status = 'active' ORDER BY started_at DESC",
    );
    return rows.map(r => this.rowToSession(r));
  }

  // ─── Pending writes ───────────────────────────────────────────────────────────

  async queuePendingWrite(sessionId: string, payload: object): Promise<string> {
    const db = await getDb();
    const id = generateId();
    await db.runAsync(
      `INSERT INTO pending_writes (id, session_id, retry_count, status, payload, created_at)
       VALUES (?, ?, 0, 'queued', ?, datetime('now'))`,
      [id, sessionId, JSON.stringify(payload)],
    );
    return id;
  }

  async getPendingWrites(): Promise<PendingWrite[]> {
    const db = await getDb();
    const rows = await db.getAllAsync<Record<string, unknown>>(
      "SELECT * FROM pending_writes WHERE status IN ('queued', 'failed') ORDER BY created_at ASC",
    );
    return rows.map(r => ({
      id: r.id as string,
      sessionId: r.session_id as string,
      retryCount: r.retry_count as number,
      lastAttemptedAt: r.last_attempted_at
        ? new Date(r.last_attempted_at as string)
        : null,
      status: r.status as 'queued' | 'failed',
      payload: r.payload as string,
    }));
  }

  async markWriteSucceeded(id: string): Promise<void> {
    const db = await getDb();
    await db.runAsync('DELETE FROM pending_writes WHERE id = ?', [id]);
  }

  async incrementWriteRetry(id: string): Promise<void> {
    const db = await getDb();
    await db.runAsync(
      `UPDATE pending_writes
       SET retry_count = retry_count + 1, last_attempted_at = datetime('now')
       WHERE id = ?`,
      [id],
    );
  }

  async markWriteFailed(id: string): Promise<void> {
    const db = await getDb();
    await db.runAsync(
      `UPDATE pending_writes
       SET status = 'failed', last_attempted_at = datetime('now')
       WHERE id = ?`,
      [id],
    );
  }

  // ─── Template cache ───────────────────────────────────────────────────────────

  async cacheTemplate(templateId: number, etag: string, data: object): Promise<void> {
    const db = await getDb();
    await db.runAsync(
      `INSERT OR REPLACE INTO template_cache (template_id, etag, fetched_at, template_json)
       VALUES (?, ?, datetime('now'), ?)`,
      [templateId, etag, JSON.stringify(data)],
    );
  }

  async getCachedTemplate(
    templateId: number,
  ): Promise<{ etag: string; data: object; fetchedAt: Date } | null> {
    const db = await getDb();
    const row = await db.getFirstAsync<Record<string, unknown>>(
      'SELECT * FROM template_cache WHERE template_id = ?',
      [templateId],
    );
    if (!row) return null;
    return {
      etag: row.etag as string,
      data: JSON.parse(row.template_json as string) as object,
      fetchedAt: new Date(row.fetched_at as string),
    };
  }

  // ─── Appointments cache ───────────────────────────────────────────────────────

  async cacheAppointments(date: string, repId: string, data: object[]): Promise<void> {
    const db = await getDb();
    const key = `${date}::${repId}`;
    await db.runAsync(
      `INSERT OR REPLACE INTO appointments_cache (cache_key, fetched_at, appointments_json)
       VALUES (?, datetime('now'), ?)`,
      [key, JSON.stringify(data)],
    );
  }

  async getCachedAppointments(
    date: string,
    repId: string,
  ): Promise<{ data: object[]; fetchedAt: Date } | null> {
    const db = await getDb();
    const key = `${date}::${repId}`;
    const row = await db.getFirstAsync<Record<string, unknown>>(
      'SELECT * FROM appointments_cache WHERE cache_key = ?',
      [key],
    );
    if (!row) return null;
    return {
      data: JSON.parse(row.appointments_json as string) as object[],
      fetchedAt: new Date(row.fetched_at as string),
    };
  }

  // ─── Private helpers ──────────────────────────────────────────────────────────

  private rowToSession(row: Record<string, unknown>): Session {
    return {
      id: row.id as string,
      smartComplyAssignmentId: row.smart_comply_assignment_id as number,
      smartComplyUserChecksheetId:
        row.smart_comply_uc_id != null
          ? (row.smart_comply_uc_id as number)
          : undefined,
      customer: JSON.parse(row.customer_json as string),
      vehicle: JSON.parse(row.vehicle_json as string),
      checksheetId: row.checksheet_id as number,
      status: row.status as Session['status'],
      startedAt: new Date(row.started_at as string),
      endedAt: row.ended_at ? new Date(row.ended_at as string) : undefined,
      checklist: JSON.parse(row.checklist_json as string),
      tradeIn: row.trade_in_json
        ? JSON.parse(row.trade_in_json as string)
        : undefined,
      sessionNote: row.session_note != null ? (row.session_note as string) : undefined,
    };
  }
}
