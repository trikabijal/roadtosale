import Fastify from "fastify";

/**
 * Road to Sale BFF — minimal Phase 1 scaffold.
 *
 * Only GET /health is wired here. The 10 app-facing routes (see
 * ../contract/openapi.yaml) land in Phase 2, validated with JSON Schema and
 * relayed to the Core API at CORE_BASE_URL.
 */

const PORT = Number(process.env.PORT ?? 8089);
const HOST = process.env.HOST ?? "0.0.0.0";

export function buildServer() {
  const app = Fastify({ logger: true });

  app.get("/health", async () => {
    return { status: "ok" };
  });

  return app;
}

// Run directly (tsx src/server.ts) but not when imported by tests.
const isMain = import.meta.url === `file://${process.argv[1]}`;
if (isMain) {
  const app = buildServer();
  app
    .listen({ port: PORT, host: HOST })
    .then((addr) => {
      app.log.info(`Road to Sale BFF listening on ${addr}`);
    })
    .catch((err) => {
      app.log.error(err);
      process.exit(1);
    });
}
