/**
 * Shared app-facing DTO types — Phase 1 placeholder.
 *
 * These mirror the schemas in ../contract/openapi.yaml (the single source of
 * truth). Phase 2 fills these in / generates them from the contract and the
 * front end can consume them. Defined here so route handlers and the Core HTTP
 * client share one set of types.
 */

export type ApiResponse<T> = {
  status: number;
  message: string;
  data: T;
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

// Phase 2: flesh out user, SessionSummaryDTO, SessionDTO, SessionEventDTO,
// PhotoDTO, ChecksheetDTO to match ../contract/openapi.yaml exactly.
