/**
 * Shared app-facing DTO types.
 *
 * These mirror the schemas in ../contract/openapi.yaml (the single source of
 * truth). They are the types the BFF route handlers, the Core HTTP client, and
 * (eventually) the front end all share. Keep them in lock-step with the
 * contract: if the contract changes, change these.
 */

/** Standard response envelope for all JSON responses. data is nullable on error. */
export type ApiResponse<T> = {
  status: number;
  message: string;
  data: T | null;
};

export type SessionType = "LIVE" | "MOCK";
export type SessionStatus = "ACTIVE" | "COMPLETED";
export type EventSource = "feature" | "workflow";
export type PhotoSlot =
  | "front_left"
  | "front_right"
  | "rear_left"
  | "rear_right"
  | "interior"
  | "odometer"
  | "vin";
export type ResultType = "CUE" | "FREE_TEXT" | "NUMERIC" | "PHOTO";

/** Authenticated user. (Lowercase `user` per the contract schema name.) */
export type user = {
  id: string;
  username: string;
  name: string;
  dealershipId: string;
  roles: string[];
};

export type LoginRequest = {
  username: string;
  password: string;
  deviceType: string;
};

export type LoginResponse = {
  accessToken: string;
  refreshToken: string;
  user: user;
};

export type RefreshRequest = {
  refreshToken: string;
};

export type RefreshResponse = {
  accessToken: string;
  refreshToken: string;
};

export type SessionContext = {
  customerName?: string;
  vehicleOfInterest?: string;
};

export type CreateSessionRequest = {
  type: SessionType;
  checksheetCode: string;
  context?: SessionContext;
};

export type SubmitSessionRequest = {
  transcript?: string;
};

export type SessionProgress = {
  answered: number;
  total: number;
};

export type SessionSummaryDTO = {
  id: string;
  type: SessionType;
  status: SessionStatus;
  checksheetCode: string;
  startedAt: string;
  endedAt?: string | null;
  context?: SessionContext;
  progress: SessionProgress;
};

export type SessionOutcome = {
  questionId: string;
  stepNo: number;
  satisfied: boolean;
  confidence?: number | null;
  transcriptSpan?: string | null;
};

export type SessionDTO = SessionSummaryDTO & {
  transcript?: string | null;
  outcomes: SessionOutcome[];
};

export type SessionEventDTO = {
  cueId: string;
  questionId: string;
  stepNo: number;
  detectedAt: string;
  confidence: number;
  transcriptSpan: string;
  source: EventSource;
};

export type PostEventsRequest = {
  events: SessionEventDTO[];
};

export type PostEventsResponse = {
  /** Number of new (non-duplicate) events appended. */
  accepted: number;
};

export type PhotoDTO = {
  id: string;
  sessionId: string;
  slot: PhotoSlot;
  fileUrl: string;
  uploadedAt: string;
};

export type ChecksheetQuestion = {
  id: string;
  text: string;
  orderNo: number;
  isMandatory: boolean;
  resultType: ResultType;
};

export type ChecksheetStep = {
  id: string;
  name: string;
  orderNo: number;
  questions: ChecksheetQuestion[];
};

export type ChecksheetDTO = {
  code: string;
  name: string;
  version: string;
  steps: ChecksheetStep[];
};
