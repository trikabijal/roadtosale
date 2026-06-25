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
  // NOTE: Core serves at the root (no /api/v1 prefix) and uses the SINGULAR
  // /checksheet/{code}. These paths must match the Core's runtime routes.
  login(payload: LoginRequest): Promise<CoreResult> {
    return call("POST", "/auth/login", { json: payload });
  },
  refresh(payload: RefreshRequest): Promise<CoreResult> {
    return call("POST", "/auth/refresh", { json: payload });
  },

  // ---- checksheet ----------------------------------------------------------
  getChecksheet(code: string, authorization?: string): Promise<CoreResult> {
    return call(
      "GET",
      `/checksheet/${encodeURIComponent(code)}`,
      { authorization },
    );
  },

  // ---- sessions ------------------------------------------------------------
  listSessions(authorization?: string): Promise<CoreResult> {
    return call("GET", "/sessions", { authorization });
  },
  createSession(
    payload: CreateSessionRequest,
    authorization?: string,
  ): Promise<CoreResult> {
    return call("POST", "/sessions", { authorization, json: payload });
  },
  getSession(id: string, authorization?: string): Promise<CoreResult> {
    return call(
      "GET",
      `/sessions/${encodeURIComponent(id)}`,
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
      `/sessions/${encodeURIComponent(id)}/submit`,
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
      `/sessions/${encodeURIComponent(id)}/events`,
      { authorization, json: payload },
    );
  },

  // ---- photos --------------------------------------------------------------
  listPhotos(id: string, authorization?: string): Promise<CoreResult> {
    return call(
      "GET",
      `/sessions/${encodeURIComponent(id)}/photos`,
      { authorization },
    );
  },
  /**
   * Relay a multipart photo upload to Core. We build a fresh multipart body
   * (field name `file` + `slot`) from the streamed upload so the file is never
   * buffered fully in BFF memory beyond what undici needs.
   */
  uploadPhoto(
    id: string,
    form: FormData,
    authorization?: string,
  ): Promise<CoreResult> {
    return call(
      "POST",
      `/sessions/${encodeURIComponent(id)}/photos`,
      { authorization, body: form },
    );
  },
};

export type CoreClient = typeof coreClient;
