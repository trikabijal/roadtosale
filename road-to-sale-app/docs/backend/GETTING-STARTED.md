# Getting Started (read this first)

This is the task-oriented onboarding guide for the Road to Sale backend. It gets
you from a fresh checkout to "the stack runs and I know what to build" in a few
minutes. Read it top to bottom the first time.

All commands below assume you start at the **repo root** (the folder that
contains `road-to-sale-app/` and `deployment/`).

---

## 1. Prerequisites

Install these, then run the verify block to confirm versions.

| Tool | Version | Needed for |
|---|---|---|
| Java (JDK) | **17** | Building/running the Core API; the one-command proof |
| Maven | **3.9+** | Building the Core jar |
| Node.js | **20+** | The BFF and the qa E2E tests |
| Docker Desktop | any recent | **Only** the manual local run (Postgres). NOT needed for the one-command proof. |

Verify:

```bash
java -version     # want: 17.x  (e.g. "openjdk version \"17...\"")
mvn -v            # want: Apache Maven 3.9 or newer
node -v           # want: v20 or newer
docker --version  # only needed for the manual local run
```

If `java -version` shows something other than 17, set `JAVA_HOME` to a JDK 17
before continuing.

---

## 2. The one-command proof

This is the fastest way to prove the whole backend works. **No Docker, no manual
setup.**

```bash
cd road-to-sale-app/qa && ./test.sh
```

What to expect:

- The **first (cold) run is slower — about 20–30 seconds** — because it builds
  the Core jar with Maven the first time (and installs npm deps). Later runs
  reuse the jar and are faster.
- You are **done when you see**:

  ```
  Tests  15 passed (15)
  ```

  and the command exits with code `0`.

What it actually proves: it boots a real Postgres in-process (via the npm
`embedded-postgres` package — **no Docker daemon**), the Core jar, and the BFF,
then drives the **full session lifecycle through the BFF over HTTP** (login,
create session, post events, upload photo, submit, read back) and reads Postgres
directly to confirm the data **persisted**. It also checks **tenant isolation**
(one dealership cannot see another's session) and event **idempotency**.

> You may notice the qa logs report a higher Postgres version than the
> docker-compose dev database. That is expected — see the version note in
> [README.md](./README.md).

---

## 3. Manual local run (develop against a live stack)

Use this when you want a running stack to develop against (for example, to point
an app at a live BFF). It uses Docker for Postgres.

**Start Docker Desktop first.** Then:

Each command block below starts from the **repo root**
(`.../Trika/roadtosale`). Use a separate terminal for steps 2 and 3 — the Core
and BFF each run in the foreground.

```bash
# 1) Postgres — from the repo root (docker-compose lives at the repo root)
cd deployment && docker compose up -d
# starts Postgres 16 on port 5432: database "roadtosale", user/password "roadtosale"
```

```bash
# 2) Core API (Java/Spring) on port 8090 — new terminal, from the repo root
cd road-to-sale-app/backend
./build.sh        # mvn clean package -DskipTests → builds the jar
./run.sh          # mvn spring-boot:run, dev profile, port 8090
```

```bash
# 3) BFF (Node/Fastify) on port 8089 — new terminal, from the repo root
cd road-to-sale-app/bff
./build.sh        # npm install + tsc --noEmit (type-check)
./run.sh          # tsx src/server.ts, port 8089, pointed at the Core
```

On the `dev` profile the Core runs database migrations and seeds two dealerships
and two users on start.

### Health checks

```bash
curl -s localhost:8090/health    # Core   → {"status":200,"message":"OK","data":{"status":"ok"}}
curl -s localhost:8089/health    # BFF    → reports its own health + Core reachability
```

### Login with a seed user

```bash
curl -s localhost:8089/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"rep1","password":"password123","deviceType":"ios"}'
# → { "status":200, "message":"OK",
#     "data": { "accessToken":"<JWT>", "refreshToken":"<JWT>", "user":{...} } }
```

Seed users (both password `password123`): `rep1` (Honda of Fremont) and `rep2`
(Honda of Oakland). They are in different dealerships, so you can use them to see
the cross-tenant `404` behavior.

### Troubleshooting

- **`role "roadtosale" does not exist`** or **connection refused** when the Core
  starts → Postgres is not up. Start Docker Desktop, then run
  `cd deployment && docker compose up -d`, then start the Core again.

---

## 4. Orientation — what is this, and what do YOU build

The system is three tiers, each its own deployable, talking only over HTTP:

```
ui  ->  BFF (:8089)  ->  Core API (:8090)  ->  Postgres
```

- **ui** — the Expo / React Native app. Talks **only** to the BFF.
- **bff** — Node/Fastify. The only thing the app talks to. Validates input,
  checks the `Authorization` header, and relays to the Core. No database.
- **backend** — Java/Spring Core API. The source of truth: data, auth,
  multi-tenancy, the static checksheet.
- **qa** — headless full-stack E2E tests (the one-command proof above).

**The backend is done.** `backend/`, `bff/`, and `qa/` are built and tested.

**Your job (Trisha): build the `ui/` — the Expo/React Native app.** The app
talks **only** to the BFF at `http://localhost:8089`. Never call the Core
(`:8090`) directly.

The contract to code against is
[`road-to-sale-app/contract/openapi.yaml`](../../contract/openapi.yaml)
(mirrored in human-readable form in [api.md](./api.md)).

---

## 5. Important: the SmartComply → BFF migration

The existing React Native app currently targets the **old SmartComply backend**,
not the new BFF. Do not expect the existing client to work against the new BFF —
the paths are different.

Specifically:

- [`road-to-sale-app/src/api/SmartComplyClient.ts`](../../src/api/SmartComplyClient.ts)
  and
  [`road-to-sale-app/src/api/clientSingleton.ts`](../../src/api/clientSingleton.ts)
  call old SmartComply paths — for example `/api/user/login` and
  `/api/rts/tradePhoto/upload`.
- [`road-to-sale-app/.env.example`](../../.env.example) uses
  `SMARTCOMPLY_API_URL`.

Part of your job is to **rewrite that client to the new BFF facade**. The new
endpoints are different — for example `POST /auth/login`, `POST /sessions`,
`GET /sessions`, and so on. See
[`contract/openapi.yaml`](../../contract/openapi.yaml) and [api.md](./api.md) for
the full app-facing API.

---

## Where to go next

- [api.md](./api.md) — the app-facing API the BFF exposes (the contract you code
  the app against).
- [architecture.md](./architecture.md) — the three tiers and the isolation rule.
- [flows.md](./flows.md) — end-to-end walkthroughs naming the real files.
- [data-model.md](./data-model.md) — the Postgres schema and how outcomes are
  derived.
- [testing.md](./testing.md) — the test plan and what each tier covers.
