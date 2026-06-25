/**
 * Protected session + event routes:
 *   GET    /sessions
 *   POST   /sessions
 *   GET    /sessions/:id
 *   POST   /sessions/:id/submit
 *   POST   /sessions/:id/events
 */

import type { FastifyInstance } from "fastify";
import { coreClient } from "../coreClient.js";
import {
  createSessionSchema,
  getSessionSchema,
  listSessionsSchema,
  postEventsSchema,
  submitSessionSchema,
} from "../schemas.js";
import { relay, requireAuth, withCore } from "../util.js";
import type {
  CreateSessionRequest,
  PostEventsRequest,
  SubmitSessionRequest,
} from "../types.js";

export async function sessionRoutes(app: FastifyInstance): Promise<void> {
  app.get(
    "/sessions",
    { schema: listSessionsSchema, preHandler: requireAuth },
    withCore(async (req, reply) => {
      const result = await coreClient.listSessions(req.headers.authorization);
      return relay(reply, result);
    }),
  );

  app.post(
    "/sessions",
    { schema: createSessionSchema, preHandler: requireAuth },
    withCore(async (req, reply) => {
      const result = await coreClient.createSession(
        req.body as CreateSessionRequest,
        req.headers.authorization,
      );
      return relay(reply, result);
    }),
  );

  app.get<{ Params: { id: string } }>(
    "/sessions/:id",
    { schema: getSessionSchema, preHandler: requireAuth },
    withCore(async (req, reply) => {
      const result = await coreClient.getSession(
        req.params.id,
        req.headers.authorization,
      );
      return relay(reply, result);
    }),
  );

  app.post<{ Params: { id: string } }>(
    "/sessions/:id/submit",
    { schema: submitSessionSchema, preHandler: requireAuth },
    withCore(async (req, reply) => {
      const result = await coreClient.submitSession(
        req.params.id,
        (req.body as SubmitSessionRequest) ?? {},
        req.headers.authorization,
      );
      return relay(reply, result);
    }),
  );

  app.post<{ Params: { id: string } }>(
    "/sessions/:id/events",
    { schema: postEventsSchema, preHandler: requireAuth },
    withCore(async (req, reply) => {
      const result = await coreClient.postEvents(
        req.params.id,
        req.body as PostEventsRequest,
        req.headers.authorization,
      );
      return relay(reply, result);
    }),
  );
}
