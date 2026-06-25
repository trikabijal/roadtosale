/**
 * Cross-cutting helpers: ApiResponse envelope construction, the
 * Authorization-presence guard, and Core-result relaying.
 */

import type { FastifyReply, FastifyRequest } from "fastify";
import {
  CoreUnreachableError,
  type CoreResult,
  type CoreRawResult,
} from "./coreClient.js";
import type { ApiResponse } from "./types.js";

/** Build an error ApiResponse envelope (data = null). */
export function errorEnvelope(status: number, message: string): ApiResponse<null> {
  return { status, message, data: null };
}

/**
 * preHandler that requires an `Authorization: Bearer <token>` header to be
 * present. The BFF does NOT verify the JWT signature — Core is the auth
 * authority. A presence check is enough for the thin BFF; absent → 401 without
 * calling Core.
 */
export async function requireAuth(
  req: FastifyRequest,
  reply: FastifyReply,
): Promise<FastifyReply | void> {
  const header = req.headers.authorization;
  if (!header || !/^Bearer\s+.+/i.test(header)) {
    // Explicitly send AND return: returning the reply tells Fastify the
    // preHandler short-circuited the request, so the route handler never runs
    // and Core is never called. (Do not rely on an implicit halt.)
    return reply
      .code(401)
      .send(errorEnvelope(401, "Missing or invalid Authorization header"));
  }
}

/**
 * Relay a Core result back to the app: set Core's HTTP status and send Core's
 * body verbatim (it is already in the ApiResponse envelope — do NOT double-wrap).
 */
export function relay(reply: FastifyReply, result: CoreResult): FastifyReply {
  reply.code(result.status);
  return reply.send(result.body);
}

/**
 * Relay a RAW (binary) Core result back to the app: pass through Core's HTTP
 * status and Content-Type, and stream Core's body directly to the client
 * without buffering it in BFF memory. Used for photo-content bytes.
 *
 * Works for non-2xx too (e.g. Core 404): the body is streamed through as-is.
 */
export function relayRaw(
  reply: FastifyReply,
  result: CoreRawResult,
): FastifyReply {
  reply.code(result.status);
  if (result.contentType) {
    reply.header("content-type", result.contentType);
  }
  // result.body is a Readable; Fastify streams it through and releases the
  // underlying Core socket when it finishes.
  return reply.send(result.body);
}

/**
 * Wrap a handler that calls Core so a CoreUnreachableError becomes a 502
 * ApiResponse instead of an unhandled 500. Generic so it preserves the route's
 * typed request (params/body) instead of widening to a bare FastifyRequest.
 */
export function withCore<Req extends FastifyRequest, Rep extends FastifyReply>(
  handler: (req: Req, reply: Rep) => Promise<unknown>,
): (req: Req, reply: Rep) => Promise<unknown> {
  return async (req: Req, reply: Rep) => {
    try {
      return await handler(req, reply);
    } catch (err) {
      if (err instanceof CoreUnreachableError) {
        req.log.error({ err: String(err.cause) }, "Core API unreachable");
        return reply
          .code(502)
          .send(errorEnvelope(502, "Core API is unreachable"));
      }
      throw err;
    }
  };
}
