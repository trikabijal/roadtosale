# Road to Sale Backend — Docs

The Road to Sale backend is the server side of the Road to Sale dealership sales
app. It replaces the old external SmartComply dependency with a backend the team
owns end to end, built around a **session**: a salesperson's recorded
conversation with a customer, scored against the NADA "Road to the Sale" question
set.

> These docs cover the **backend stack** (BFF + Core + Postgres + QA). The
> front-end app's own docs live one level up in `road-to-sale-app/docs/`
> (`architecture.md`, `api.md`, `flows.md`, `build.md`).

## What this is — three tiers

A request flows through three tiers, each its own deployable:

```
mobile app (ui)  ->  BFF (Node/Fastify)  ->  Core API (Java/Spring)  ->  Postgres
```

- **ui** — the Expo / React Native app. Talks ONLY to the BFF. (Built by the
  front-end task; documented here for context.)
- **bff** — Node 20 / TypeScript / Fastify. The only thing the app talks to. It
  validates input, checks for an `Authorization` header, and relays to the Core.
  It owns no database and re-implements no business logic.
- **backend** — Java 17 / Spring Boot 3 / Postgres 16. The source of truth. Owns
  all data, auth, multi-tenancy, and the static checksheet.
- **qa** — headless full-stack end-to-end (E2E) tests that boot the whole stack
  and drive it through the BFF over HTTP.

The two facades are `ui -> bff` and `bff -> Core`. The app never knows Java
exists; the Core never knows the app's shape. See
[architecture.md](./architecture.md) for the isolation rule.

## Folder map

Everything lives under `road-to-sale-app/`:

```
road-to-sale-app/
├── ui/         Front end (Expo / React Native) — talks only to the BFF
├── bff/        Node / Fastify BFF — the app-facing API
├── backend/    Java / Spring Core API — domain + Postgres, source of truth
├── qa/         Headless full-stack E2E tests
├── contract/   openapi.yaml — the authoritative app-facing contract
└── docs/       Front-end docs, plus docs/backend/ (these files)
```

> Note: in the current checkout the front-end source sits directly under
> `road-to-sale-app/` (e.g. `src/`); relocating it into `ui/` is a separate
> front-end task. The four boundaries share no source code — they communicate
> only over HTTP.

Postgres for local development is defined in `deployment/docker-compose.yml` at
the repo root.

## Build, run, and test the stack locally

Each deployable follows the same script convention: `build.sh`, `run.sh`,
`test.sh`. All paths below are relative to the repo root.

### 1. Start Postgres (normal local dev)

```bash
cd deployment && docker compose up -d
```

This starts Postgres 16 on port 5432 (database `roadtosale`, user/password
`roadtosale`). The headless E2E tests do NOT need this — they launch their own
embedded Postgres (see step 5).

### 2. Build and run the Core API

```bash
cd road-to-sale-app/backend
./build.sh        # mvn clean package -DskipTests → builds the jar
./run.sh          # mvn spring-boot:run, dev profile, port 8090
```

On the `dev` profile the Core runs Flyway migrations on start and seeds two
dealerships and two users (see [data-model.md](./data-model.md) for seed
credentials). It needs a reachable Postgres (`SPRING_DATASOURCE_*`).

### 3. Build and run the BFF

```bash
cd road-to-sale-app/bff
./build.sh        # npm install + tsc --noEmit (type-check)
./run.sh          # tsx src/server.ts, port 8089, pointed at the Core
```

The BFF reads `CORE_BASE_URL` (default `http://localhost:8090`). The app's
existing config expects the BFF on port 8089.

### 4. Run the unit tests

```bash
cd road-to-sale-app/backend && ./test.sh   # 25 Core slice tests (embedded Postgres)
cd road-to-sale-app/bff     && ./test.sh   # 14 BFF unit tests (Core mocked)
```

### 5. Run the headless full-stack E2E

```bash
cd road-to-sale-app/qa && ./test.sh        # 14 checks, full lifecycle + DB assertions
```

This boots a real Postgres in-process (the `embedded-postgres` package — **no
Docker daemon**), the Core jar, and the BFF, then drives the full session
lifecycle through the BFF and reads Postgres directly to confirm the data
persisted. See [testing.md](./testing.md).

## The other docs

- [architecture.md](./architecture.md) — the three tiers, the isolation rule,
  the session-centric domain, multi-tenancy, and key decisions.
- [api.md](./api.md) — the app-facing API: 10 BFF endpoints, the `ApiResponse`
  envelope, every DTO, and error codes.
- [data-model.md](./data-model.md) — the Postgres schema, multi-tenancy, the
  append-only event log, and how outcomes are derived.
- [flows.md](./flows.md) — end-to-end walkthroughs naming the real files: login,
  a full live session, and tenant isolation.
- [testing.md](./testing.md) — the test strategy, tiers, and what the E2E
  asserts. Doubles as the test plan.
