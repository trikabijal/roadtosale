/**
 * Road to Sale BFF — the only thing the mobile app talks to.
 *
 * Thin backend-for-frontend: validates input with JSON Schema at the edge,
 * checks Authorization presence on protected routes, then relays to the Java
 * Core API (CORE_BASE_URL) and passes Core's ApiResponse envelope straight back.
 * The BFF re-implements no business logic and owns no database.
 *
 * See ../contract/openapi.yaml for the authoritative app-facing contract.
 */

import Fastify, { type FastifyError, type FastifyInstance } from "fastify";
import multipart from "@fastify/multipart";
import swagger from "@fastify/swagger";
import swaggerUi from "@fastify/swagger-ui";

import { authRoutes } from "./routes/auth.js";
import { checksheetRoutes } from "./routes/checksheet.js";
import { sessionRoutes } from "./routes/sessions.js";
import { photoRoutes } from "./routes/photos.js";
import { coreClient } from "./coreClient.js";
import { errorEnvelope } from "./util.js";

const PORT = Number(process.env.PORT ?? 8089);
const HOST = process.env.HOST ?? "0.0.0.0";

export async function buildServer(): Promise<FastifyInstance> {
  const app = Fastify({
    logger: {
      // Never log Authorization headers or password fields.
      redact: {
        paths: [
          'req.headers.authorization',
          'req.headers["authorization"]',
          "req.body.password",
        ],
        remove: true,
      },
    },
    // We send our own ApiResponse envelope; don't let Fastify rewrite shapes.
    ajv: { customOptions: { removeAdditional: false, coerceTypes: true } },
  });

  // ---- request logging: method, path, status, latency --------------------
  app.addHook("onResponse", async (req, reply) => {
    req.log.info(
      {
        method: req.method,
        path: req.url,
        status: reply.statusCode,
        latencyMs: Math.round(reply.elapsedTime),
      },
      "request completed",
    );
  });

  // ---- validation errors → 400 ApiResponse (without calling Core) ---------
  app.setErrorHandler((err: FastifyError, req, reply) => {
    if (err.validation) {
      const message = err.message || "Invalid request";
      reply.code(400).send(errorEnvelope(400, message));
      return;
    }
    // 401 thrown from preHandlers already sent; otherwise generic 500.
    const status = err.statusCode && err.statusCode >= 400 ? err.statusCode : 500;
    req.log.error({ err: err.message }, "unhandled error");
    reply.code(status).send(errorEnvelope(status, err.message || "Internal error"));
  });

  // ---- 404 for unknown routes in the envelope -----------------------------
  app.setNotFoundHandler((req, reply) => {
    reply.code(404).send(errorEnvelope(404, `Not found: ${req.method} ${req.url}`));
  });

  // ---- plugins ------------------------------------------------------------
  await app.register(multipart, {
    limits: { fileSize: 25 * 1024 * 1024, files: 1 },
  });

  await app.register(swagger, {
    openapi: {
      info: {
        title: "Road to Sale — App-facing API (BFF)",
        description:
          "The app-facing contract. The mobile app talks ONLY to the BFF, " +
          "which relays to the internal Core API. Responses use the " +
          "ApiResponse envelope { status, message, data }.",
        version: "1.0.0",
      },
      servers: [{ url: `http://localhost:${PORT}`, description: "BFF (local dev)" }],
      tags: [
        { name: "health" },
        { name: "auth" },
        { name: "checksheet" },
        { name: "sessions" },
        { name: "events" },
        { name: "photos" },
      ],
      components: {
        securitySchemes: {
          bearerAuth: { type: "http", scheme: "bearer", bearerFormat: "JWT" },
        },
      },
    },
  });
  await app.register(swaggerUi, { routePrefix: "/docs" });

  // ---- health (also reports Core reachability) ----------------------------
  app.get(
    "/health",
    { schema: { tags: ["health"], summary: "Liveness/readiness probe" } },
    async () => {
      const coreReachable = await coreClient.health();
      return { status: "ok", coreReachable };
    },
  );

  // ---- app-facing routes --------------------------------------------------
  await app.register(authRoutes);
  await app.register(checksheetRoutes);
  await app.register(sessionRoutes);
  await app.register(photoRoutes);

  return app;
}

// Run directly (tsx src/server.ts) but not when imported by tests.
const isMain = import.meta.url === `file://${process.argv[1]}`;
if (isMain) {
  buildServer()
    .then((app) =>
      app.listen({ port: PORT, host: HOST }).then((addr) => {
        app.log.info(`Road to Sale BFF listening on ${addr}`);
      }),
    )
    .catch((err) => {
      // eslint-disable-next-line no-console
      console.error(err);
      process.exit(1);
    });
}
