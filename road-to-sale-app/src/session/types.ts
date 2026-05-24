import type { ChecksheetDTO, ChecksheetHeaderDTO, ChecksheetQuestionDTO } from '../api/types';
import type { CueDetection } from '../voice/types';

// ─── Checklist runtime state ─────────────────────────────────────────────────

export type QuestionStatus = 'pending' | 'partial' | 'complete' | 'overridden';

export interface DetectedCue {
  cueId: string;
  cueSource: 'feature' | 'workflow';
  transcriptSnippet: string;
  confidence: number | null;
  detectedAtMs: number;
}

export interface QuestionState {
  question: ChecksheetQuestionDTO;
  status: QuestionStatus;
  detectedCues: DetectedCue[];          // voice-detected cues for this question
  overrideNote?: string;                // present when status === 'overridden'
  voiceAutoCompleted: boolean;          // true = completed by voice, false = manual
}

export interface StepState {
  header: ChecksheetHeaderDTO;
  questions: QuestionState[];
  isComplete: boolean;                   // all mandatory questions complete/overridden
}

export interface ChecklistState {
  steps: StepState[];
  completedCount: number;
  totalCount: number;
  lastUpdatedMs: number;
}

// ─── Cue pack binding ─────────────────────────────────────────────────────────

export interface CuePackEntry {
  cueId: string;
  templateQuestionId: number;            // chksQuestions.id from SmartComply
  required: boolean;                     // if true, this cue must fire to complete the question
  okOptionId?: number;                   // chksQuestionResultOptions.id for the "OK" option
}

// ─── Session lifecycle ────────────────────────────────────────────────────────

export type SessionStatus = 'setup' | 'active' | 'ending' | 'ended' | 'crashed';

export interface SessionCustomer {
  firstName: string;
  lastName?: string;
  phone?: string;
}

export interface SessionVehicle {
  makeId: string;
  makeName: string;
  modelId: string;
  modelName: string;
  trimId: string;
  trimName: string;
  year: number;
}

export interface Session {
  id: string;                            // local UUID
  smartComplyAssignmentId: number;       // SmartComply inspection id
  smartComplyUserChecksheetId?: number;  // set after createOrUpdate succeeds
  customer: SessionCustomer;
  vehicle: SessionVehicle;
  checksheetId: number;
  status: SessionStatus;
  startedAt: Date;
  endedAt?: Date;
  checklist: ChecklistState;
  tradeIn?: TradeInState;
  sessionNote?: string;
}

export interface TradeInState {
  photos: Record<string, string | null>;  // slot → localUri | null
  spokenNotes: DetectedCue[];
  typedNote?: string;
}

// ─── IChecklistEngine ─────────────────────────────────────────────────────────

export interface IChecklistEngine {
  // Initialise with the SmartComply checksheet + Road to Sale cue pack bindings
  init(checksheet: ChecksheetDTO, cuePackEntries: CuePackEntry[]): void;

  // Process an incoming cue detection and return updated checklist state
  processCueDetection(detection: CueDetection): ChecklistState;

  // Manual override
  overrideQuestion(questionId: number, note?: string): ChecklistState;

  // Read current state
  getState(): ChecklistState;

  // Subscribe to updates — returns unsubscribe function
  onUpdate(listener: (state: ChecklistState) => void): () => void;
}

// ─── ISessionEngine ──────────────────────────────────────────────────────────

export interface ISessionEngine {
  startSession(
    assignmentId: number,
    customer: SessionCustomer,
    vehicle: SessionVehicle,
    checksheet: ChecksheetDTO,
    cuePackEntries: CuePackEntry[],
  ): Promise<Session>;

  endSession(sessionId: string): Promise<Session>;

  muteAudio(): void;
  unmuteAudio(): void;

  getSession(sessionId: string): Session | null;

  onSessionUpdate(listener: (session: Session) => void): () => void;
}
