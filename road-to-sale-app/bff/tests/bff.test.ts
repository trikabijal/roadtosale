/**
 * Tier-1 BFF unit tests. The Core HTTP client is mocked so these are fast and
 * need no running Core. They assert the BFF's edge behavior:
 *   (a) input validation rejects bad bodies with 400 BEFORE hitting Core
 *   (b) missing Authorization → 401 on a protected route (Core never called)
 *   (c) a happy-path route forwards correctly and returns Core's shaped response
 *   (d) the multipart photo route forwards the file
 *   (e) Core unreachable → 502
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyInstance } from "fastify";

// Mock the Core client module before importing the server.
vi.mock("../src/coreClient.js", async () => {
  const actual = await vi.importActual<typeof import("../src/coreClient.js")>(
    "../src/coreClient.js",
  );
  return {
    ...actual,
    coreClient: {
      health: vi.fn(async () => true),
      login: vi.fn(),
      refresh: vi.fn(),
      getChecksheet: vi.fn(),
      listSessions: vi.fn(),
      createSession: vi.fn(),
      getSession: vi.fn(),
      submitSession: vi.fn(),
      postEvents: vi.fn(),
      listPhotos: vi.fn(),
      uploadPhoto: vi.fn(),
    },
  };
});

import { buildServer } from "../src/server.js";
import { coreClient, CoreUnreachableError } from "../src/coreClient.js";

const AUTH = "Bearer faketoken.value.here";

let app: FastifyInstance;

beforeEach(async () => {
  vi.clearAllMocks();
  app = await buildServer();
  await app.ready();
});

afterEach(async () => {
  await app.close();
});

describe("health", () => {
  it("returns ok and reports Core reachability", async () => {
    const res = await app.inject({ method: "GET", url: "/health" });
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.status).toBe("ok");
    expect(body.coreReachable).toBe(true);
  });
});

describe("(a) input validation rejects bad bodies with 400 before hitting Core", () => {
  it("login with missing password → 400, Core not called", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/auth/login",
      payload: { username: "alice", deviceType: "ios" },
    });
    expect(res.statusCode).toBe(400);
    expect(res.json().status).toBe(400);
    expect(res.json().data).toBeNull();
    expect(coreClient.login).not.toHaveBeenCalled();
  });

  it("create session with bad enum → 400, Core not called", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/sessions",
      headers: { authorization: AUTH },
      payload: { type: "BOGUS", checksheetCode: "RTS_HONDA_V1" },
    });
    expect(res.statusCode).toBe(400);
    expect(coreClient.createSession).not.toHaveBeenCalled();
  });

  it("post events with confidence out of range → 400", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/sessions/11111111-1111-1111-1111-111111111111/events",
      headers: { authorization: AUTH },
      payload: {
        events: [
          {
            cueId: "c1",
            questionId: "q1",
            stepNo: 1,
            detectedAt: "2026-06-25T10:00:00Z",
            confidence: 2,
            transcriptSpan: "hi",
            source: "feature",
          },
        ],
      },
    });
    expect(res.statusCode).toBe(400);
    expect(coreClient.postEvents).not.toHaveBeenCalled();
  });

  it("post events with empty batch → 400", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/sessions/11111111-1111-1111-1111-111111111111/events",
      headers: { authorization: AUTH },
      payload: { events: [] },
    });
    expect(res.statusCode).toBe(400);
    expect(coreClient.postEvents).not.toHaveBeenCalled();
  });
});

describe("(b) missing Authorization → 401 on a protected route", () => {
  it("GET /sessions without auth → 401, Core not called", async () => {
    const res = await app.inject({ method: "GET", url: "/sessions" });
    expect(res.statusCode).toBe(401);
    expect(res.json().status).toBe(401);
    expect(coreClient.listSessions).not.toHaveBeenCalled();
  });

  it("GET /checksheet/:code without auth → 401", async () => {
    const res = await app.inject({ method: "GET", url: "/checksheet/RTS_HONDA_V1" });
    expect(res.statusCode).toBe(401);
    expect(coreClient.getChecksheet).not.toHaveBeenCalled();
  });

  it("non-Bearer Authorization → 401", async () => {
    const res = await app.inject({
      method: "GET",
      url: "/sessions",
      headers: { authorization: "Basic abc" },
    });
    expect(res.statusCode).toBe(401);
  });
});

describe("(c) happy-path routes forward and return Core's shaped response", () => {
  it("login forwards and relays Core's envelope + status", async () => {
    const coreBody = {
      status: 200,
      message: "OK",
      data: {
        accessToken: "a",
        refreshToken: "r",
        user: {
          id: "u1",
          username: "alice",
          name: "Alice",
          dealershipId: "d1",
          roles: ["SALESPERSON"],
        },
      },
    };
    vi.mocked(coreClient.login).mockResolvedValue({ status: 200, body: coreBody });

    const res = await app.inject({
      method: "POST",
      url: "/auth/login",
      payload: { username: "alice", password: "pw", deviceType: "ios" },
    });

    expect(coreClient.login).toHaveBeenCalledWith({
      username: "alice",
      password: "pw",
      deviceType: "ios",
    });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual(coreBody);
  });

  it("GET /sessions forwards Authorization and relays Core's array", async () => {
    const coreBody = { status: 200, message: "OK", data: [] };
    vi.mocked(coreClient.listSessions).mockResolvedValue({ status: 200, body: coreBody });

    const res = await app.inject({
      method: "GET",
      url: "/sessions",
      headers: { authorization: AUTH },
    });

    expect(coreClient.listSessions).toHaveBeenCalledWith(AUTH);
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual(coreBody);
  });

  it("relays Core's non-200 status (e.g. 409 conflict on submit)", async () => {
    const coreBody = { status: 409, message: "Already completed", data: null };
    vi.mocked(coreClient.submitSession).mockResolvedValue({ status: 409, body: coreBody });

    const res = await app.inject({
      method: "POST",
      url: "/sessions/11111111-1111-1111-1111-111111111111/submit",
      headers: { authorization: AUTH },
      payload: {},
    });

    expect(res.statusCode).toBe(409);
    expect(res.json()).toEqual(coreBody);
  });
});

describe("(d) multipart photo route forwards the file", () => {
  it("uploads file + slot and relays Core's PhotoDTO", async () => {
    const coreBody = {
      status: 200,
      message: "OK",
      data: {
        id: "p1",
        sessionId: "11111111-1111-1111-1111-111111111111",
        slot: "front_left",
        fileUrl: "http://core/photos/p1",
        uploadedAt: "2026-06-25T10:00:00Z",
      },
    };
    vi.mocked(coreClient.uploadPhoto).mockResolvedValue({ status: 200, body: coreBody });

    const boundary = "----testboundary";
    const payload =
      `--${boundary}\r\n` +
      `Content-Disposition: form-data; name="slot"\r\n\r\n` +
      `front_left\r\n` +
      `--${boundary}\r\n` +
      `Content-Disposition: form-data; name="file"; filename="car.jpg"\r\n` +
      `Content-Type: image/jpeg\r\n\r\n` +
      `JPEGDATA\r\n` +
      `--${boundary}--\r\n`;

    const res = await app.inject({
      method: "POST",
      url: "/sessions/11111111-1111-1111-1111-111111111111/photos",
      headers: {
        authorization: AUTH,
        "content-type": `multipart/form-data; boundary=${boundary}`,
      },
      payload,
    });

    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual(coreBody);
    expect(coreClient.uploadPhoto).toHaveBeenCalledTimes(1);
    const [id, form, auth] = vi.mocked(coreClient.uploadPhoto).mock.calls[0];
    expect(id).toBe("11111111-1111-1111-1111-111111111111");
    expect(auth).toBe(AUTH);
    expect(form.get("slot")).toBe("front_left");
    expect(form.get("file")).toBeInstanceOf(Blob);
  });

  it("rejects an invalid slot with 400 before calling Core", async () => {
    const boundary = "----testboundary2";
    const payload =
      `--${boundary}\r\n` +
      `Content-Disposition: form-data; name="slot"\r\n\r\n` +
      `not_a_slot\r\n` +
      `--${boundary}\r\n` +
      `Content-Disposition: form-data; name="file"; filename="car.jpg"\r\n` +
      `Content-Type: image/jpeg\r\n\r\n` +
      `JPEGDATA\r\n` +
      `--${boundary}--\r\n`;

    const res = await app.inject({
      method: "POST",
      url: "/sessions/11111111-1111-1111-1111-111111111111/photos",
      headers: {
        authorization: AUTH,
        "content-type": `multipart/form-data; boundary=${boundary}`,
      },
      payload,
    });

    expect(res.statusCode).toBe(400);
    expect(coreClient.uploadPhoto).not.toHaveBeenCalled();
  });
});

describe("(e) Core unreachable → 502", () => {
  it("maps CoreUnreachableError to a 502 ApiResponse", async () => {
    vi.mocked(coreClient.listSessions).mockRejectedValue(
      new CoreUnreachableError(new Error("ECONNREFUSED")),
    );

    const res = await app.inject({
      method: "GET",
      url: "/sessions",
      headers: { authorization: AUTH },
    });

    expect(res.statusCode).toBe(502);
    expect(res.json().status).toBe(502);
    expect(res.json().data).toBeNull();
  });
});
