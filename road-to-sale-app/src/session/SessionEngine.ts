import { v4 as uuidv4 } from 'uuid';
import { ChecklistEngine } from './ChecklistEngine';
import { getSmartComplyClient } from '../api/clientSingleton';
import type {
  ISessionEngine,
  Session,
  SessionCustomer,
  SessionVehicle,
  ChecklistState,
  CuePackEntry,
} from './types';
import type { ChecksheetDTO } from '../api/types';
import type { CueDetection } from '../voice/types';

export class SessionEngine implements ISessionEngine {
  private sessions: Map<string, Session> = new Map();
  private checklistEngines: Map<string, ChecklistEngine> = new Map();
  private listeners: Array<(session: Session) => void> = [];

  async startSession(
    assignmentId: number,
    customer: SessionCustomer,
    vehicle: SessionVehicle,
    checksheet: ChecksheetDTO,
    cuePackEntries: CuePackEntry[],
  ): Promise<Session> {
    const sessionId = uuidv4();
    const client = getSmartComplyClient();

    // Tell SmartComply this inspection is now IN_PROGRESS
    const userChecksheet = await client.startOrResumeSession({
      auditAssignmentId: assignmentId,
      status: 'IN_PROGRESS',
      shift: 'First',
      startedAt: formatDateTime(new Date()),
      submissionVersion: 0,
      frequencyOfFreqOfChkCnt: 1,
    });

    // Initialise checklist engine
    const engine = new ChecklistEngine();
    engine.init(checksheet, cuePackEntries);
    this.checklistEngines.set(sessionId, engine);

    // Subscribe engine updates to propagate into session state
    engine.onUpdate((checklist: ChecklistState) => {
      const session = this.sessions.get(sessionId);
      if (!session) return;
      const updated: Session = { ...session, checklist };
      this.sessions.set(sessionId, updated);
      this.emit(updated);
    });

    const session: Session = {
      id: sessionId,
      smartComplyAssignmentId: assignmentId,
      smartComplyUserChecksheetId: userChecksheet.id,
      customer,
      vehicle,
      checksheetId: checksheet.id,
      status: 'active',
      startedAt: new Date(),
      checklist: engine.getState(),
    };

    this.sessions.set(sessionId, session);
    this.emit(session);
    return session;
  }

  async endSession(sessionId: string): Promise<Session> {
    const session = this.sessions.get(sessionId);
    if (!session) throw new Error(`Session not found: ${sessionId}`);

    const ending: Session = { ...session, status: 'ending', endedAt: new Date() };
    this.sessions.set(sessionId, ending);
    this.emit(ending);

    // Submit to SmartComply
    if (session.smartComplyUserChecksheetId !== undefined) {
      try {
        await getSmartComplyClient().submitSession(session.smartComplyUserChecksheetId);
      } catch (e) {
        // Queue for retry — handled by caller
        console.warn('[SessionEngine] Submit failed, will retry:', e);
      }
    }

    const ended: Session = { ...ending, status: 'ended' };
    this.sessions.set(sessionId, ended);
    this.emit(ended);
    return ended;
  }

  processCueDetection(sessionId: string, detection: CueDetection): void {
    const engine = this.checklistEngines.get(sessionId);
    if (!engine) return;
    engine.processCueDetection(detection);
    // Session state updated via engine.onUpdate() subscription above
  }

  /** Audio muting is handled by VoiceEngine — these are pass-through stubs. */
  muteAudio(): void {
    // Intentionally empty: delegated to VoiceEngine
  }

  unmuteAudio(): void {
    // Intentionally empty: delegated to VoiceEngine
  }

  getSession(sessionId: string): Session | null {
    return this.sessions.get(sessionId) ?? null;
  }

  /** Manually mark a question as overridden (sales rep confirms step). */
  overrideQuestion(sessionId: string, questionId: number, note?: string): ChecklistState | null {
    const engine = this.checklistEngines.get(sessionId);
    if (!engine) return null;
    return engine.overrideQuestion(questionId, note);
  }

  onSessionUpdate(listener: (session: Session) => void): () => void {
    this.listeners.push(listener);
    return () => {
      this.listeners = this.listeners.filter((l) => l !== listener);
    };
  }

  private emit(session: Session): void {
    for (const listener of this.listeners) {
      listener(session);
    }
  }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatDateTime(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.000`
  );
}
