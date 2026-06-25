# Road to Sale BFF — Architecture

## Role

The BFF is a **thin backend-for-frontend** (Node 20, TypeScript, Fastify 5). It
is the single facade the mobile app talks to. It re-implements **no** business
logic and owns **no** database — the Java Core API is the source of truth and the
auth authority.

```
mobile app ── HTTP ──▶ BFF (8089) ── HTTP ──▶ Core API (8090, CORE_BASE_URL) ──▶ Postgres
```

## Responsibilities (and only these)

1. **Validate** every request body/params with Fastify JSON Schema at the edge.
   Bad input → `400` without ever calling Core.
2. **Require auth presence** on protected routes — an `Authorization: Bearer`
   header must be present, else `401` without calling Core. The BFF does **not**
   verify the JWT signature; Core does.
3. **Relay** to Core, forwarding the caller's `Authorization` header verbatim,
   and pass Core's `ApiResponse` envelope + HTTP status straight back (no
   double-wrapping).
4. **Rebuild multipart** photo uploads and forward them to Core.
5. **Map Core-unreachable** to a `502` ApiResponse.
6. **Serve OpenAPI docs** at `/docs` and a `/health` probe that also reports Core
   reachability.

## Components

| File | Responsibility |
|---|---|
| `src/server.ts` | Builds the Fastify app: logging (Authorization redacted), error/404 handlers (envelope), multipart + swagger plugins, health, route registration. |
| `src/coreClient.ts` | The only place that talks to Core (undici). One typed method per Core endpoint; throws `CoreUnreachableError` on connection failure. |
| `src/schemas.ts` | JSON Schemas derived from the contract; one `FastifySchema` per route. |
| `src/util.ts` | `errorEnvelope`, `requireAuth` preHandler, `relay`, `withCore` (502 wrapper). |
| `src/routes/*.ts` | One plugin per tag group (auth, checksheet, sessions+events, photos). Handlers are 3 lines: call Core client, relay. |
| `src/types.ts` | Shared app-facing DTO types mirroring the contract. |

## Isolation guarantees

- The BFF imports **nothing** from `backend/`, `ui/`, or `qa/`. The only contact
  with Core is HTTP via `coreClient.ts` using `CORE_BASE_URL`.
- All Core I/O is funneled through `coreClient.ts` — routes never call undici
  directly.
- No secrets are shared with Core (no JWT signing key needed; presence check
  only).

## Design decisions

- **Pass-through envelope.** Core already returns `ApiResponse`; the BFF relays
  it rather than reshaping, keeping v1 a validating relay (per PRD §6.2).
- **Presence-only auth.** Verifying signatures in two places duplicates the auth
  authority. The BFF checks the header exists; Core enforces validity and
  tenancy, so cross-tenant access surfaces as Core's `404`, relayed as-is.
- **Multipart rebuild over passthrough.** `@fastify/multipart` parses the upload;
  we rebuild a clean undici `FormData` so field names and metadata are explicit
  and validated.
