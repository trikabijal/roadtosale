/** Protected checksheet route: GET /checksheet/:code. */

import type { FastifyInstance } from "fastify";
import { coreClient } from "../coreClient.js";
import { getChecksheetSchema } from "../schemas.js";
import { relay, requireAuth, withCore } from "../util.js";

export async function checksheetRoutes(app: FastifyInstance): Promise<void> {
  app.get<{ Params: { code: string } }>(
    "/checksheet/:code",
    { schema: getChecksheetSchema, preHandler: requireAuth },
    withCore(async (req, reply) => {
      const result = await coreClient.getChecksheet(
        req.params.code,
        req.headers.authorization,
      );
      return relay(reply, result);
    }),
  );
}
