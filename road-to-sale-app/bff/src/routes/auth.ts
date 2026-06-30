/** Public auth routes: POST /auth/login, POST /auth/refresh. */

import type { FastifyInstance } from "fastify";
import { coreClient } from "../coreClient.js";
import { loginSchema, refreshSchema } from "../schemas.js";
import { relay, withCore } from "../util.js";
import type { LoginRequest, RefreshRequest } from "../types.js";

export async function authRoutes(app: FastifyInstance): Promise<void> {
  app.post(
    "/auth/login",
    { schema: loginSchema },
    withCore(async (req, reply) => {
      const result = await coreClient.login(req.body as LoginRequest);
      return relay(reply, result);
    }),
  );

  app.post(
    "/auth/refresh",
    { schema: refreshSchema },
    withCore(async (req, reply) => {
      const result = await coreClient.refresh(req.body as RefreshRequest);
      return relay(reply, result);
    }),
  );
}
