# Road to Sale — BFF (Node / Fastify)

The app-facing API. The mobile app talks **only** to the BFF; the BFF validates
input, checks the `Authorization` header, and relays to the Core API. It owns no
database and re-implements no business logic.

- **Stack:** Node 20, TypeScript, Fastify.
- **Port:** 8089 (dev).
- **Talks to:** the Core API at `CORE_BASE_URL` (default `http://localhost:8090`).

## Build / run / test

```bash
./build.sh        # npm install + tsc --noEmit (type-check)
./run.sh          # tsx src/server.ts on port 8089, pointed at the Core
./test.sh         # vitest run — BFF unit tests (Core mocked)
```

Health check once running: `curl -s localhost:8089/health` (also reports whether
the Core is reachable). Swagger UI is served at `/docs`.

## Docs

- New here? Read [docs/backend/GETTING-STARTED.md](../docs/backend/GETTING-STARTED.md) first.
- App-facing API reference: [docs/backend/api.md](../docs/backend/api.md).
- The BFF's own docs: [docs/api.md](./docs/api.md),
  [docs/architecture.md](./docs/architecture.md), [docs/flows.md](./docs/flows.md).
- Backend stack overview: [docs/backend/README.md](../docs/backend/README.md).
