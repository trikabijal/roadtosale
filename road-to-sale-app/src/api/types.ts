// ─── Auth ───────────────────────────────────────────────────────────────────

export interface LoginRequest {
  username: string;
  password: string;
  deviceType: 'APP' | 'WEB';
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  id: number;
  username: string;
  name: string;
  permissions: string[];
  allRoles: string[];
}

export interface RefreshRequest {
  refreshToken: string;
}

// ─── Standard envelope ───────────────────────────────────────────────────────

export interface ApiResponse<T> {
  status: number;
  message: string;
  data: T;
}

// ─── Assignments (CRM appointments equivalent) ───────────────────────────────

export interface AssignmentDTO {
  assignmentId: number;
  auditId: number;
  auditName: string;
  checksheetId: number;
  checksheetName: string;
  locationLabel: string;
  userChecksheetId: number | null;
  status: InspectionStatus;
  answeredQuestions: number;
  totalQuestions: number;
}

export type InspectionStatus =
  | 'ASSIGNED'
  | 'IN_PROGRESS'
  | 'SUBMITTED'
  | 'VALIDATED'
  | 'APPROVED'
  | 'DECLINED';

// ─── Checksheet (NADA audit template) ────────────────────────────────────────

export interface ChecksheetDTO {
  id: number;
  name: string;
  code: string;
  status: 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'ARCHIVED';
  headers: ChecksheetHeaderDTO[];
}

export interface ChecksheetHeaderDTO {
  id: number;
  name: string;              // NADA step name, e.g. "Greet / Hospitality"
  orderNo: number;
  questions: ChecksheetQuestionDTO[];
}

export interface ChecksheetQuestionDTO {
  id: number;
  question: string;          // The audit question text displayed in UI
  orderNo: number;
  isMandatory: boolean;
  questionResultType: QuestionResultType;
  resultOptions: QuestionResultOptionDTO[];
}

export type QuestionResultType =
  | 'SUBJECTIVE_CONDITION'   // OK / Not OK — used for cue detection
  | 'SUBJECTIVE'             // Free text
  | 'OBJECTIVE'              // Numeric
  | 'FILE_UPLOAD';           // Photo attachment

export interface QuestionResultOptionDTO {
  id: number;
  option: string;            // e.g. "OK", "Not OK"
  orderNo: number;
}

// ─── UserChecksheet (session / inspection) ────────────────────────────────────

export interface UserChecksheetCreateDTO {
  auditAssignmentId: number;
  status: InspectionStatus;
  shift: string;
  startedAt: string;         // "YYYY-MM-DD HH:mm:ss.SSS"
  submissionVersion: number;
  frequencyOfFreqOfChkCnt: number;
}

export interface UserChecksheetDTO {
  id: number;
  auditId: number;
  auditeeLocationId: number;
  checksheetId: number;
  auditName: string;
  checksheetName: string;
  status: InspectionStatus;
  startedAt: string;
}

// ─── Answers (cue detection events) ─────────────────────────────────────────

export interface UserChecksheetAnswerDTO {
  userChecksheetId: number;
  chksQuestionId: number;
  chksQuestionRsltOptionId?: number;  // For SUBJECTIVE_CONDITION: the option ID for "OK"
  answer?: string;                    // For SUBJECTIVE/OBJECTIVE types
  isNotApplicable?: boolean;
  // Road to Sale extension fields (V1.34 migration)
  rtsCueId?: string;
  rtsCueSource?: 'feature' | 'workflow';
  rtsTranscriptSnippet?: string;
  rtsCueConfidence?: number;
  rtsVoiceAutoCompleted?: boolean;
}

// ─── Add audit assignment (walk-in creation) ─────────────────────────────────

export interface AddAssignmentRequest {
  auditId: number;
  assignments: Array<{
    auditeeLocationId: number;
    operatorUserId: number;
    dealerPrincipalUserId?: number;
  }>;
}

// ─── Trade photos ─────────────────────────────────────────────────────────────

export type TradePhotoSlot =
  | 'front_left'
  | 'front_right'
  | 'rear_left'
  | 'rear_right'
  | 'interior'
  | 'odometer'
  | 'vin';

export interface TradePhotoDTO {
  id: number;
  inspectionId: number;
  slot: TradePhotoSlot;
  fileUrl: string;
  uploadedAt: string;
}
