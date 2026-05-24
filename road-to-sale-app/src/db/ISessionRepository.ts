import type { Session, TradeInState } from '../session/types';

export interface PendingWrite {
  id: string;
  sessionId: string;
  retryCount: number;
  lastAttemptedAt: Date | null;
  status: 'queued' | 'failed';
  payload: string;  // JSON
}

export interface ISessionRepository {
  // Sessions
  createSession(session: Session): Promise<void>;
  updateSession(session: Partial<Session> & { id: string }): Promise<void>;
  getSession(id: string): Promise<Session | null>;
  getAllSessions(limit?: number): Promise<Session[]>;
  getActiveSessions(): Promise<Session[]>;       // status='active'

  // Pending writes (retry queue)
  queuePendingWrite(sessionId: string, payload: object): Promise<string>;  // returns queue entry id
  getPendingWrites(): Promise<PendingWrite[]>;
  markWriteSucceeded(id: string): Promise<void>;
  incrementWriteRetry(id: string): Promise<void>;
  markWriteFailed(id: string): Promise<void>;

  // Template cache
  cacheTemplate(templateId: number, etag: string, data: object): Promise<void>;
  getCachedTemplate(templateId: number): Promise<{ etag: string; data: object; fetchedAt: Date } | null>;

  // Appointments cache
  cacheAppointments(date: string, repId: string, data: object[]): Promise<void>;
  getCachedAppointments(date: string, repId: string): Promise<{ data: object[]; fetchedAt: Date } | null>;
}
