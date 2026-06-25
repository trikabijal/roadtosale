/**
 * Core HTTP client.
 *
 * The BFF is a thin backend-for-frontend: it forwards app requests to the Java
 * Core API (the source of truth and auth authority) and passes Core's
 * ApiResponse envelope straight back. This client owns all Core I/O via undici.
 *
 * Conventions:
 *  - Every method returns { status, body } where status is Core's HTTP status
 *    and body is Core's parsed JSON (already in the ApiResponse envelope).
 *  - The caller's Authorization header is forwarded verbatim for protected
 *    endpoints. Core is the auth authority; the BFF never inspects the token.
 *  - If Core is unreachable (connection refused, DNS, timeout), every method
 *    throws CoreUnreachableError, which the server maps to a 502 ApiResponse.
 */

import { request, FormData, type Dispatcher } from "undici";
import type { Readable } from "node:stream";
import type {
  ApiResponse,
  CreateSessionRequest,
  LoginRequest,
  PostEventsRequest,
  RefreshRequest,
  SubmitSessionRequest,
} from "./types.js";

export const CORE_BASE_URL =
  process.env.CORE_BASE_URL ?? "http://localhost:8090";

/**
 * Core serves its resource endpoints under /api/v1 (auth, checksheets,
 * sessions, events, photos). The Core /health probe is the one exception:
 * it stays at the ROOT (/health). This prefix is internal to the BFF↔Core
 * boundary; the BFF's own app-facing surface is unversioned and uses the
 * singular /checksheet — see the route files.
 */
const CORE_API_PREFIX = "/api/v1";

/** Thrown when the Core API cannot be reached at all. Mapped to HTTP 502. */
export class CoreUnreachableError extends Error {
  constructor(public readonly cause: unknown) {
    super("Core API is unreachable");
    this.name = "CoreUnreachableError";
  }
}

/** A relayed Core response: Core's HTTP status + parsed JSON body. */
export type CoreResult<T = unknown> = {
  status: number;
  body: ApiResponse<T> | unknown;
};

type HeadersInit = Record<string, string>;

function authHeader(authorization?: string): HeadersInit {
  return authorization ? { authorization } : {};
}

async function parseJson(res: Dispatcher.ResponseData): Promise<unknown> {
  const text = await res.body.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    // Core should always return JSON; if not, surface the raw text safely.
    return { status: res.statusCode, message: text, data: null };
  }
}

async function call(
  method: "GET" | "POST",
  path: string,
  opts: {
    authorization?: string;
    json?: unknown;
    headers?: HeadersInit;
    body?: Readable | Buffer | FormData;
  } = {},
): Promise<CoreResult> {
  const url = `${CORE_BASE_URL}${path}`;
  const headers: HeadersInit = {
    ...authHeader(opts.authorization),
    ...(opts.headers ?? {}),
  };

  let body: string | Readable | Buffer | FormData | undefined;
  if (opts.json !== undefined) {
    headers["content-type"] = "application/json";
    body = JSON.stringify(opts.json);
  } else if (opts.body !== undefined) {
    body = opts.body;
  }

  try {
    const res = await request(url, {
      method,
      headers,
      body: body as any,
    });
    return { status: res.statusCode, body: await parseJson(res) };
  } catch (err) {
    throw new CoreUnreachableError(err);
  }
}

/** A raw (non-JSON) Core response: status, Content-Type, and the streamable body. */
export type CoreRawResult = {
  status: number;
  contentType?: string;
  /** undici response body; stream it through to the caller without buffering. */
  body: Dispatcher.ResponseData["body"];
};

/**
 * Like `call`, but does NOT parse/buffer the body — used for binary payloads
 * (e.g. photo content). The caller is responsible for consuming/streaming the
 * returned body so the socket is released.
 */
async function callRaw(
  method: "GET",
  path: string,
  opts: { authorization?: string } = {},
): Promise<CoreRawResult> {
  const url = `${CORE_BASE_URL}${path}`;
  try {
    const res = await request(url, {
      method,
      headers: authHeader(opts.authorization),
    });
    return {
      status: res.statusCode,
      contentType: res.headers["content-type"] as string | undefined,
      body: res.body,
    };
  } catch (err) {
    throw new CoreUnreachableError(err);
  }
}

export const coreClient = {
  /** GET /health on Core — used to report Core reachability. */
  async health(): Promise<boolean> {
    try {
      const res = await request(`${CORE_BASE_URL}/health`, { method: "GET" });
      // Drain the body so the socket is released.
      await res.body.text();
      return res.statusCode >= 200 && res.statusCode < 300;
    } catch {
      return false;
    }
  },

  // ---- auth (public) -------------------------------------------------------
  // NOTE: Core serves resource endpoints under /api/v1 and uses the PLURAL
  // /checksheets/{code}. These paths must match the Core's runtime routes.
  login(payload: LoginRequest): Promise<CoreResult> {
    return call("POST", `${CORE_API_PREFIX}/auth/login`, { json: payload });
  },
  refresh(payload: RefreshRequest): Promise<CoreResult> {
    return call("POST", `${CORE_API_PREFIX}/auth/refresh`, { json: payload });
  },

  // ---- checksheet ----------------------------------------------------------
  getChecksheet(code: string, authorization?: string): Promise<CoreResult> {
    return call(
      "GET",
      `${CORE_API_PREFIX}/checksheets/${encodeURIComponent(code)}`,
      { authorization },
    );
  },

  // ---- sessions ------------------------------------------------------------
  listSessions(authorization?: string): Promise<CoreResult> {
    return call("GET", `${CORE_API_PREFIX}/sessions`, { authorization });
  },
  createSession(
    payload: CreateSessionRequest,
    authorization?: string,
  ): Promise<CoreResult> {
    return call("POST", `${CORE_API_PREFIX}/sessions`, {
      authorization,
      json: payload,
    });
  },
  getSession(id: string, authorization?: string): Promise<CoreResult> {
    return call(
      "GET",
      `${CORE_API_PREFIX}/sessions/${encodeURIComponent(id)}`,
      { authorization },
    );
  },
  submitSession(
    id: string,
    payload: SubmitSessionRequest,
    authorization?: string,
  ): Promise<CoreResult> {
    return call(
      "POST",
      `${CORE_API_PREFIX}/sessions/${encodeURIComponent(id)}/submit`,
      { authorization, json: payload },
    );
  },

  // ---- events --------------------------------------------------------------
  postEvents(
    id: string,
    payload: PostEventsRequest,
    authorization?: string,
  ): Promise<CoreResult> {
    return call(
      "POST",
      `${CORE_API_PREFIX}/sessions/${encodeURIComponent(id)}/events`,
      { authorization, json: payload },
    );
  },

  // ---- photos --------------------------------------------------------------
  listPhotos(id: string, authorization?: string): Promise<CoreResult> {
    return call(
      "GET",
      `${CORE_API_PREFIX}/sessions/${encodeURIComponent(id)}/photos`,
      { authorization },
    );
  },
  /**
   * Relay a multipart photo upload to Core. The handler streams the uploaded
   * file part directly into this form (see routes/photos.ts); undici then
   * streams the multipart body to Core. The BFF does not buffer the whole file
   * in memory (only @fastify/multipart's bounded internal chunking applies).
   */
  uploadPhoto(
    id: string,
    form: FormData,
    authorization?: string,
  ): Promise<CoreResult> {
    return call(
      "POST",
      `${CORE_API_PREFIX}/sessions/${encodeURIComponent(id)}/photos`,
      { authorization, body: form },
    );
  },
  /**
   * Fetch a single photo's raw bytes from Core. Returns the streamable body +
   * Content-Type + status so the route can relay them through without
   * buffering. Authed; the caller's Authorization header is forwarded.
   */
  getPhotoContent(
    id: string,
    photoId: string,
    authorization?: string,
  ): Promise<CoreRawResult> {
    return callRaw(
      "GET",
      `${CORE_API_PREFIX}/sessions/${encodeURIComponent(
        id,
      )}/photos/${encodeURIComponent(photoId)}/content`,
      { authorization },
    );
  },
};

export type CoreClient = typeof coreClient;
