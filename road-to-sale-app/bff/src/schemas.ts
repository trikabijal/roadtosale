/**
 * Fastify JSON Schemas derived from ../contract/openapi.yaml.
 *
 * These validate request bodies and params at the BFF edge. Invalid input is
 * rejected with 400 BEFORE Core is ever called. Response schemas are attached
 * for the Swagger docs but kept loose (Core owns the authoritative shape) so the
 * BFF never strips fields Core adds.
 */

import type { FastifySchema } from "fastify";

// ---- reusable fragments -----------------------------------------------------

const sessionType = { type: "string", enum: ["LIVE", "MOCK"] } as const;
const eventSource = { type: "string", enum: ["feature", "workflow"] } as const;
const photoSlot = {
  type: "string",
  enum: [
    "front_left",
    "front_right",
    "rear_left",
    "rear_right",
    "interior",
    "odometer",
    "vin",
  ],
} as const;

const sessionContext = {
  type: "object",
  additionalProperties: false,
  properties: {
    customerName: { type: "string" },
    vehicleOfInterest: { type: "string" },
  },
} as const;

const sessionIdParams = {
  type: "object",
  required: ["id"],
  properties: {
    id: { type: "string", format: "uuid" },
  },
} as const;

const photoContentParams = {
  type: "object",
  required: ["id", "photoId"],
  properties: {
    id: { type: "string", format: "uuid" },
    photoId: { type: "string", minLength: 1 },
  },
} as const;

const checksheetCodeParams = {
  type: "object",
  required: ["code"],
  properties: {
    code: { type: "string", minLength: 1 },
  },
} as const;

const apiResponse = {
  type: "object",
  properties: {
    status: { type: "integer" },
    message: { type: "string" },
    data: {},
  },
} as const;

// ---- request body schemas ---------------------------------------------------

export const loginBody = {
  type: "object",
  required: ["username", "password", "deviceType"],
  additionalProperties: false,
  properties: {
    username: { type: "string", minLength: 1 },
    password: { type: "string", minLength: 1 },
    deviceType: { type: "string", minLength: 1 },
  },
} as const;

export const refreshBody = {
  type: "object",
  required: ["refreshToken"],
  additionalProperties: false,
  properties: {
    refreshToken: { type: "string", minLength: 1 },
  },
} as const;

export const createSessionBody = {
  type: "object",
  required: ["type", "checksheetCode"],
  additionalProperties: false,
  properties: {
    type: sessionType,
    checksheetCode: { type: "string", minLength: 1 },
    context: sessionContext,
  },
} as const;

export const submitSessionBody = {
  type: "object",
  additionalProperties: false,
  properties: {
    transcript: { type: "string" },
  },
} as const;

export const sessionEvent = {
  type: "object",
  required: [
    "cueId",
    "questionId",
    "stepNo",
    "detectedAt",
    "confidence",
    "transcriptSpan",
    "source",
  ],
  additionalProperties: false,
  properties: {
    cueId: { type: "string", minLength: 1 },
    questionId: { type: "string", minLength: 1 },
    stepNo: { type: "integer" },
    detectedAt: { type: "string", format: "date-time" },
    confidence: { type: "number", minimum: 0, maximum: 1 },
    transcriptSpan: { type: "string" },
    source: eventSource,
  },
} as const;

export const postEventsBody = {
  type: "object",
  required: ["events"],
  additionalProperties: false,
  properties: {
    events: {
      type: "array",
      minItems: 1,
      // Cap batch size so a single request can't carry an unbounded payload.
      // Rejected with 400 at the edge before Core is called.
      maxItems: 500,
      items: sessionEvent,
    },
  },
} as const;

// ---- full route schemas (request validation + Swagger metadata) -------------

const okEnvelope = { 200: apiResponse } as const;

export const loginSchema: FastifySchema = {
  tags: ["auth"],
  summary: "Log in with username + password",
  body: loginBody,
  response: okEnvelope,
};

export const refreshSchema: FastifySchema = {
  tags: ["auth"],
  summary: "Exchange a refresh token for a new token pair",
  body: refreshBody,
  response: okEnvelope,
};

export const getChecksheetSchema: FastifySchema = {
  tags: ["checksheet"],
  summary: "Get a NADA checksheet by code (static reference data)",
  params: checksheetCodeParams,
  response: okEnvelope,
};

export const listSessionsSchema: FastifySchema = {
  tags: ["sessions"],
  summary: "List the authenticated user's sessions (most recent first)",
  response: okEnvelope,
};

export const createSessionSchema: FastifySchema = {
  tags: ["sessions"],
  summary: "Create a new session (status starts ACTIVE)",
  body: createSessionBody,
  response: okEnvelope,
};

export const getSessionSchema: FastifySchema = {
  tags: ["sessions"],
  summary: "Get a single session by id (with derived per-question outcomes)",
  params: sessionIdParams,
  response: okEnvelope,
};

export const submitSessionSchema: FastifySchema = {
  tags: ["sessions"],
  summary: "Submit (complete) a session",
  params: sessionIdParams,
  body: submitSessionBody,
  response: okEnvelope,
};

export const postEventsSchema: FastifySchema = {
  tags: ["events"],
  summary: "Append a batch of detected cue events (idempotent by cueId)",
  params: sessionIdParams,
  body: postEventsBody,
  response: okEnvelope,
};

export const listPhotosSchema: FastifySchema = {
  tags: ["photos"],
  summary: "List all photos for a session",
  params: sessionIdParams,
  response: okEnvelope,
};

// Multipart body is NOT validated by JSON Schema (consumes multipart/form-data);
// the slot is validated in the handler. Params still validated here.
export const uploadPhotoSchema: FastifySchema = {
  tags: ["photos"],
  summary: "Upload one trade-in photo for a slot (multipart)",
  consumes: ["multipart/form-data"],
  params: sessionIdParams,
  response: okEnvelope,
};

// Raw photo-bytes relay. No response envelope (binary pass-through), so no
// `response` schema is attached — Core's Content-Type/body are streamed as-is.
export const photoContentSchema: FastifySchema = {
  tags: ["photos"],
  summary: "Get the raw bytes of a single photo (streamed from Core)",
  params: photoContentParams,
};

export const PHOTO_SLOTS = photoSlot.enum;
