/**
 * Road to Sale — full-stack headless E2E (Phase 3a).
 *
 * Black-box discipline: this harness talks ONLY to the running BFF over HTTP
 * (and reads Postgres directly to assert persistence). It never imports BFF or
 * Core source. The whole stack — a real embedded Postgres (no Docker), the Java
 * Core, and the Node BFF — is booted in beforeAll and torn down in afterAll.
 *
 * Scenario (all driven through the BFF facade unless noted):
 *   a. login rep1
 *   b. GET checksheet
 *   c. create session
 *   d. list sessions includes it
 *   e. POST a batch of real events (mix of >=0.6 and one <0.6), unique cueIds
 *   f. idempotent re-POST -> accepted == 0
 *   g. upload a real PNG (multipart) -> fileUrl is the BFF-relative content path;
 *      fetch it THROUGH THE BFF with Bearer -> 200, image content-type, bytes match
 *   h. list photos includes it
 *   i. submit -> COMPLETED, derived outcomes, progress.answered
 *   j. re-submit -> 409; post events to completed session -> 409
 *   k. tenant isolation: rep2 GET rep1's session -> 404
 *   l. photo-content authz: no token -> 401; rep2 (other tenant) -> 404 (B4 IDOR fix)
 *   + direct DB assertions: sessions / session_events / photos rows.
 */

import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { request, FormData, Agent } from "undici";
import { Client } from "pg";
import { startStack, type Stack } from "./harness.js";

// A dedicated dispatcher with NO keep-alive: every request gets a fresh socket.
// Reusing keep-alive sockets across the ordered lifecycle (especially before a
// multipart upload) can wedge; this keeps each call independent and bounded.
const dispatcher = new Agent({
  pipelining: 0,
  keepAliveTimeout: 1,
  keepAliveMaxTimeout: 1,
  headersTimeout: 20_000,
  bodyTimeout: 20_000,
});

// A tiny but valid 1x1 PNG (transparent), generated inline.
const PNG_1X1 = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
  "base64",
);

// Real RTS_HONDA_V1 question ids -> step numbers (see backend checksheet json).
const Q = {
  q1: { questionId: "1", stepNo: 1 },
  q2: { questionId: "2", stepNo: 1 },
  q5: { questionId: "5", stepNo: 2 },
  q7: { questionId: "7", stepNo: 3 },
  q12: { questionId: "12", stepNo: 6 },
};

type Json = any;

let stack: Stack;

// Shared state across the ordered lifecycle tests.
let rep1Token = "";
let rep2Token = "";
let sessionId = "";
let fileUrl = "";

// Batch with FOUR events >=0.6 (q1,q2,q5,q7) and ONE <0.6 (q12).
const detectedAt = "2026-06-25T10:00:00Z";
const eventBatch = {
  events: [
    { cueId: "cue-1", ...Q.q1, detectedAt, confidence: 0.92, transcriptSpan: "Welcome in!", source: "feature" },
    { cueId: "cue-2", ...Q.q2, detectedAt, confidence: 0.81, transcriptSpan: "I'm Sam", source: "feature" },
    { cueId: "cue-3", ...Q.q5, detectedAt, confidence: 0.7, transcriptSpan: "budget around 30k", source: "workflow" },
    { cueId: "cue-4", ...Q.q7, detectedAt, confidence: 0.6, transcriptSpan: "I recommend the Civic", source: "feature" },
    { cueId: "cue-5", ...Q.q12, detectedAt, confidence: 0.42, transcriptSpan: "maybe a trade-in?", source: "feature" },
  ],
};
const SATISFIED_QIDS = ["1", "2", "5", "7"]; // confidence >= 0.6
const UNSATISFIED_QID = "12"; // confidence < 0.6
const UNIQUE_CUE_COUNT = eventBatch.events.length;

// ---- small HTTP helpers (BFF facade only) ---------------------------------

async function http(
  method: "GET" | "POST",
  base: string,
  pathName: string,
  opts: { token?: string; json?: unknown; body?: any; headers?: Record<string, string> } = {},
): Promise<{ status: number; body: Json }> {
  const headers: Record<string, string> = { ...(opts.headers ?? {}) };
  if (opts.token) headers["authorization"] = `Bearer ${opts.token}`;
  let body: any;
  if (opts.json !== undefined) {
    headers["content-type"] = "application/json";
    body = JSON.stringify(opts.json);
  } else if (opts.body !== undefined) {
    body = opts.body;
  }
  const res = await request(`${base}${pathName}`, { method, headers, body, dispatcher });
  const text = await res.body.text();
  let parsed: Json = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    parsed = text;
  }
  return { status: res.statusCode, body: parsed };
}

async function dbQuery<T = any>(sql: string, params: any[] = []): Promise<T[]> {
  const client = new Client({
    host: stack.pg.host,
    port: stack.pg.port,
    user: stack.pg.user,
    password: stack.pg.password,
    database: stack.pg.database,
  });
  await client.connect();
  try {
    const r = await client.query(sql, params);
    return r.rows as T[];
  } finally {
    await client.end();
  }
}

beforeAll(async () => {
  stack = await startStack();
}, 180_000);

afterAll(async () => {
  await dispatcher.close().catch(() => {});
  if (stack) await stack.teardown();
}, 60_000);

describe("Road to Sale E2E (lifecycle through the BFF)", () => {
  it("a. login rep1 -> 200 + accessToken", async () => {
    const { status, body } = await http("POST", stack.bffBase, "/auth/login", {
      json: { username: "rep1", password: "password123", deviceType: "APP" },
    });
    expect(status).toBe(200);
    expect(body.data?.accessToken).toBeTruthy();
    rep1Token = body.data.accessToken;
  });

  it("b. GET checksheet RTS_HONDA_V1 -> 200 with steps/questions", async () => {
    const { status, body } = await http("GET", stack.bffBase, "/checksheet/RTS_HONDA_V1", {
      token: rep1Token,
    });
    expect(status).toBe(200);
    const cs = body.data;
    expect(cs.code).toBe("RTS_HONDA_V1");
    expect(Array.isArray(cs.steps)).toBe(true);
    expect(cs.steps.length).toBeGreaterThan(0);
    const totalQuestions = cs.steps.reduce((n: number, s: any) => n + s.questions.length, 0);
    expect(totalQuestions).toBe(16);
  });

  it("c. create session -> 200, ACTIVE, captures id", async () => {
    const { status, body } = await http("POST", stack.bffBase, "/sessions", {
      token: rep1Token,
      json: {
        type: "LIVE",
        checksheetCode: "RTS_HONDA_V1",
        context: { customerName: "Pat Buyer", vehicleOfInterest: "Civic" },
      },
    });
    expect(status).toBe(200);
    expect(body.data?.id).toBeTruthy();
    expect(body.data.status).toBe("ACTIVE");
    expect(body.data.checksheetCode).toBe("RTS_HONDA_V1");
    sessionId = body.data.id;
  });

  it("d. GET sessions includes the new session", async () => {
    const { status, body } = await http("GET", stack.bffBase, "/sessions", { token: rep1Token });
    expect(status).toBe(200);
    const ids = (body.data as any[]).map((s) => s.id);
    expect(ids).toContain(sessionId);
  });

  it("e. POST events batch -> accepted == events sent", async () => {
    const { status, body } = await http("POST", stack.bffBase, `/sessions/${sessionId}/events`, {
      token: rep1Token,
      json: eventBatch,
    });
    expect(status).toBe(200);
    expect(body.data?.accepted).toBe(UNIQUE_CUE_COUNT);
  });

  it("f. idempotency: re-POST same batch -> accepted == 0", async () => {
    const { status, body } = await http("POST", stack.bffBase, `/sessions/${sessionId}/events`, {
      token: rep1Token,
      json: eventBatch,
    });
    expect(status).toBe(200);
    expect(body.data?.accepted).toBe(0);
  });

  it("g. upload real PNG (front_left) -> fileUrl is BFF content path; fetch through BFF with Bearer", async () => {
    const form = new FormData();
    form.append("slot", "front_left");
    // Must be a REAL valid PNG — Core sniffs magic bytes against an
    // image/{jpeg,png,webp} allow-list (B3) and rejects anything else with 400.
    form.append("file", new Blob([PNG_1X1], { type: "image/png" }), "front_left.png");

    const { status, body } = await http("POST", stack.bffBase, `/sessions/${sessionId}/photos`, {
      token: rep1Token,
      body: form,
    });
    expect(status).toBe(200);
    // fileUrl is now the BFF-relative authed content path (no more public /files/**).
    expect(body.data?.fileUrl).toMatch(
      /^\/sessions\/[^/]+\/photos\/[^/]+\/content$/,
    );
    fileUrl = body.data.fileUrl;

    // The bytes are served by the BFF (NOT the Core) behind auth. Fetch through
    // the BFF with the Bearer token; assert 200, image content-type, byte parity.
    const fileRes = await request(`${stack.bffBase}${fileUrl}`, {
      method: "GET",
      headers: { authorization: `Bearer ${rep1Token}` },
      dispatcher,
    });
    const bytes = Buffer.from(await fileRes.body.arrayBuffer());
    expect(fileRes.statusCode).toBe(200);
    expect(String(fileRes.headers["content-type"] ?? "")).toMatch(/^image\//);
    expect(bytes.length).toBe(PNG_1X1.length);
    expect(bytes.equals(PNG_1X1)).toBe(true);
  });

  it("h. GET photos includes the uploaded photo", async () => {
    const { status, body } = await http("GET", stack.bffBase, `/sessions/${sessionId}/photos`, {
      token: rep1Token,
    });
    expect(status).toBe(200);
    const slots = (body.data as any[]).map((p) => p.slot);
    expect(slots).toContain("front_left");
    expect((body.data as any[]).some((p) => p.fileUrl === fileUrl)).toBe(true);
  });

  it("i. submit -> COMPLETED with derived outcomes + progress", async () => {
    const { status, body } = await http("POST", stack.bffBase, `/sessions/${sessionId}/submit`, {
      token: rep1Token,
      json: { transcript: "Full session transcript for Pat Buyer / Civic." },
    });
    expect(status).toBe(200);
    expect(body.data.status).toBe("COMPLETED");

    const outcomes: any[] = body.data.outcomes ?? [];
    const byQ = new Map(outcomes.map((o) => [o.questionId, o]));
    for (const qid of SATISFIED_QIDS) {
      expect(byQ.get(qid)?.satisfied, `q${qid} should be satisfied`).toBe(true);
    }
    // TW7: the low-confidence question MUST have an outcome, and it MUST be
    // unsatisfied. Asserting unconditionally (no `if`) so an absent outcome fails.
    const lowOutcome = byQ.get(UNSATISFIED_QID);
    expect(lowOutcome, `q${UNSATISFIED_QID} (confidence < 0.6) must have an outcome`).toBeTruthy();
    expect(lowOutcome.satisfied, `q${UNSATISFIED_QID} should be unsatisfied`).toBe(false);

    // progress.answered counts ONLY the >=0.6 questions (4); total 16.
    expect(body.data.progress.total).toBe(16);
    expect(body.data.progress.answered).toBe(SATISFIED_QIDS.length);
  });

  it("j. re-submit -> 409, and posting events to completed session -> 409", async () => {
    const resubmit = await http("POST", stack.bffBase, `/sessions/${sessionId}/submit`, {
      token: rep1Token,
      json: { transcript: "again" },
    });
    expect(resubmit.status).toBe(409);

    const events = await http("POST", stack.bffBase, `/sessions/${sessionId}/events`, {
      token: rep1Token,
      json: {
        events: [
          { cueId: "cue-late", ...Q.q1, detectedAt, confidence: 0.99, transcriptSpan: "late", source: "feature" },
        ],
      },
    });
    expect(events.status).toBe(409);
  });

  it("k. tenant isolation: rep2 GET rep1's session -> 404", async () => {
    const login = await http("POST", stack.bffBase, "/auth/login", {
      json: { username: "rep2", password: "password123", deviceType: "APP" },
    });
    expect(login.status).toBe(200);
    rep2Token = login.body.data.accessToken;

    const { status } = await http("GET", stack.bffBase, `/sessions/${sessionId}`, {
      token: rep2Token,
    });
    expect(status).toBe(404);
  });

  it("l. photo-content authz: no token -> 401; rep2 (other tenant) -> 404 (B4 IDOR)", async () => {
    expect(fileUrl, "fileUrl must have been captured in step g").toBeTruthy();

    // (a) No Authorization header -> BFF requireAuth rejects with 401.
    const noAuth = await request(`${stack.bffBase}${fileUrl}`, {
      method: "GET",
      dispatcher,
    });
    await noAuth.body.text();
    expect(noAuth.statusCode).toBe(401);

    // (b) rep2 (a different tenant) presents a valid token but the session/photo
    // belongs to rep1 -> tenant-scoped lookup misses -> 404 (not 403, not bytes).
    const otherTenant = await request(`${stack.bffBase}${fileUrl}`, {
      method: "GET",
      headers: { authorization: `Bearer ${rep2Token}` },
      dispatcher,
    });
    await otherTenant.body.text();
    expect(otherTenant.statusCode).toBe(404);
  });

  // ---- direct DB assertions: data really persisted -------------------------

  it("DB: sessions row (1 for rep1, COMPLETED, transcript set)", async () => {
    const rows = await dbQuery(
      `SELECT s.id, s.status, s.transcript
         FROM sessions s
         JOIN users u ON u.id = s.user_id
        WHERE u.username = 'rep1'`,
    );
    expect(rows.length).toBe(1);
    expect(rows[0].id).toBe(sessionId);
    expect(rows[0].status).toBe("COMPLETED");
    expect(rows[0].transcript).toBeTruthy();
  });

  it("DB: session_events — unique events, no duplicates (count == distinct cueIds)", async () => {
    const total = await dbQuery<{ count: string }>(
      `SELECT COUNT(*)::int AS count FROM session_events WHERE session_id = $1`,
      [sessionId],
    );
    const distinct = await dbQuery<{ count: string }>(
      `SELECT COUNT(DISTINCT cue_id)::int AS count FROM session_events WHERE session_id = $1`,
      [sessionId],
    );
    expect(Number(total[0].count)).toBe(UNIQUE_CUE_COUNT);
    expect(Number(distinct[0].count)).toBe(UNIQUE_CUE_COUNT);
  });

  it("DB: photos — exactly 1 row for the session", async () => {
    const rows = await dbQuery(
      `SELECT slot, storage_path FROM photos WHERE session_id = $1`,
      [sessionId],
    );
    expect(rows.length).toBe(1);
    expect(rows[0].slot).toBe("front_left");
    expect(rows[0].storage_path).toBeTruthy();
  });
});
