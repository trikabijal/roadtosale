# Tenant overlay (backend)

The backend ships as a generic **AuditPro** product. Customer-specific
business-logic config — currently the BI dashboard's AI-insight correlation
patterns and message templates — is layered in at startup from a per-tenant
overlay folder.

Unlike the frontend repos, the backend does **not** copy files into a build
output. It reads tenant config at runtime, every time the JVM starts, with
the chosen tenant resolved from a Spring property (`tenant.id`).

---

## 1. The mental model

Two layers, same as the frontends:

| Layer | What it is | Lives in |
|---|---|---|
| **Base** | Complete generic AuditPro identity. For the backend that means real defaults — the National BI dashboard's "AI insights" panel renders empty for unbranded builds, that's the base default. | `tenants/_base/` |
| **Tenant overlay** | Just the config a tenant adds on top. Today: their list of paired-element correlation patterns and message templates. | `tenants/<tenantId>/` |

The contract that names what's overridable lives in **`tenants/_schema.json`**.

---

## 2. `tenants/_schema.json` — the contract

Each entry declares a file a tenant may override:

| Field | Meaning |
|---|---|
| `path` | File path *relative to the tenant folder*. |
| `requiredInBase` | If `true`, `_base/<path>` must exist. App startup fails if missing. The base must be complete. |
| `requiredInTenant` | If `true`, every tenant must ship this file. Today all are `false` — every override is opt-in. |
| `description` | One-line human explanation. |
| `validate.type` | Per-file content validator. Today: `yaml-correlations` for `insights.yaml`. Add a new validator type by extending `parseAndValidate(...)` in `TenantInsightsConfig.java`. |

Today the only overridable file is `config/insights.yaml`. Adding a new
overridable backend config file (say `config/welcome-email.html`) means
adding a schema entry, dropping the file in `_base/`, and teaching the
relevant Spring component to read it.

---

## 3. The folder layout

```
smartcomply/
├── src/main/java/...                    # Code only
│   └── com/checkSheet/config/
│       └── TenantInsightsConfig.java    # Loads + validates + layers
│
├── src/main/resources/
│   ├── application.properties           # Defines tenant.id default
│   └── application-local.properties     # Local dev override
│
├── tenants/
│   ├── _schema.json                     # The contract (committed)
│   │
│   ├── _base/                           # Default tenant — empty correlations
│   │   └── config/
│   │       └── insights.yaml            # correlations: []
│   │
│   ├── kia/                             # Kia overlay
│   │   └── config/
│   │       └── insights.yaml            # paver+signage correlation
│   │
│   └── ford/                            # (example) future tenant
│       └── config/
│           └── insights.yaml            # ford-specific patterns
│
└── bin/
    └── restart.sh                       # Local restart helper
```

Unlike the frontend, no files in `src/` are touched by the tenant overlay.
The `tenants/` folder lives alongside the source and ships **inside the
docker image** (see `Dockerfile`), then is read by the JVM at runtime via
the path in `tenant.config.path` (default `./tenants`).

---

## Tenant data via Flyway (zero-touch deploy)

Tenant-specific *DB seed data* (Kia users, Kia dealerships) lives in
`src/main/resources/db/tenants/<tenantId>/` as Flyway V100.x migrations.
`spring.flyway.locations` resolves the tenant folder at startup:

```properties
spring.flyway.locations=classpath:db/migration,classpath:db/tenants/${tenant.id}
```

Layout:

```
src/main/resources/db/
├── migration/                          # Universal schema — runs for every tenant
│   ├── V1.0__... .sql
│   └── V1.26__... .sql
└── tenants/
    ├── _base/                          # No data migrations (empty default)
    │   └── README.md                   # Placeholder so the dir ships in WAR
    └── kia/
        ├── V100.001__seed_kia_users.sql
        └── V100.002__seed_kia_dealerships.sql
```

Versioning convention:
- `V1.x` (1.0–1.99) → universal schema migrations
- `V100.xxx` (100.001–100.999) → tenant data migrations, one namespace
  per tenant (the same `V100.001` lives in `kia/` and `ford/` — they
  never collide because Flyway only loads one tenant's location at a time)

**What this means operationally**: setting up Kia on a fresh server is
**zero manual effort**. Deploy the WAR with `TENANT_ID=kia` (default)
and on first boot:

1. Flyway scans `db/migration/` → applies V1.0 → V1.26 (schema)
2. Flyway scans `db/tenants/kia/` → applies V100.001, V100.002
   (Kia users + dealerships)
3. JVM finishes startup with the DB fully populated. No CI clicks.

For an existing UAT/prod that already has Kia data, the V100 migrations
hit `ON CONFLICT DO NOTHING` and become no-ops — Flyway still records
them in `flyway_schema_history` so they run exactly once per DB.

For a different tenant (`TENANT_ID=ford`), drop a parallel
`db/tenants/ford/V100.001__...` file and Flyway only applies Ford's
migrations. Kia's are invisible to Ford's DB.

The previous manual CI jobs `seed-kia-uat-users` and `seed-kia-dealerships`
were removed when this change landed — they're now obsolete.

What is **not** auto-seeded:
- The 200-audit demo dataset from `tenants/kia/bin/seed-kia-demo.py` — still manual
  because it makes real Gemini Flash API calls (~$0.50) and is showcase
  material, not production data.

---

## 4. How the build passes tenant info

### The Spring property contract

`application.properties` sets:

```
tenant.id=${TENANT_ID:kia}
tenant.config.path=${TENANT_CONFIG_PATH:./tenants}
```

`tenant.id` picks which `tenants/<id>/` folder to layer on top of `_base`.
`tenant.config.path` is where the `tenants/` folder lives at runtime —
default `./tenants` works for both local dev (run from repo root) and
docker (Dockerfile copies `tenants/` to `/app/tenants/`, JVM runs with
`WORKDIR /app`).

Both default values can be overridden via env vars:

```
TENANT_ID=ford java -jar smartcomply.war
```

### Local dev

```
./bin/restart.sh                 # uses tenant.id default (kia)
TENANT_ID=_base ./bin/restart.sh # unbranded build
```

The script is dumb — it just kills the running JVM, repackages if needed,
and starts a fresh one. The tenant resolution happens inside the Spring
boot — look for the log line:

```
TenantInsightsConfig : Loaded N BI insight correlation(s) for tenant 'kia'
```

If you don't see that line, or you see `Loaded 0` when you expect more,
the config didn't load — check the path (`tenant.config.path` resolves
relative to the JVM's working directory).

### Docker / CI

The current `Dockerfile`:

```dockerfile
FROM amazoncorretto:17.0.7-alpine
...
COPY ./target/smartcomply-0.0.1-SNAPSHOT.war /app/smartcomply-0.0.1-SNAPSHOT.war
COPY ./tenants                              /app/tenants/
WORKDIR /app
ENTRYPOINT [ "java", "-jar", "/app/smartcomply-0.0.1-SNAPSHOT.war" ]
```

The `COPY ./tenants /app/tenants/` line is critical — without it the JVM
boots, can't find `_base/config/insights.yaml`, and **fails to start**
(`IllegalStateException: Tenant _base is incomplete`). Don't drop that
line.

The `docker run` invocation passes `TENANT_ID`:

```bash
docker run -d \
  -e SPRING_PROFILES_ACTIVE=uat \
  -e TENANT_ID=kia \
  -e ANTHROPIC_KEY -e GEMINI_KEY \
  --name java-smartcomply \
  gitlab.tiez.net:5050/tiez/smartcomply:latest
```

For a Ford container, build the same image (the war + the whole `tenants/`
folder ships in it) and run with `-e TENANT_ID=ford`. The image is
tenant-agnostic; the runtime env picks which tenant config layers on top
of base.

GitLab CI (`.gitlab-ci.yml`) — no change required for the existing build
job. The Maven build doesn't touch `tenants/`, and the docker build copies
it as part of the build context. A future "build-per-tenant" matrix would
just add a `TENANT_ID` env var to the deploy job and an image tag suffix
to keep them apart in the registry.

---

## 5. Startup behaviour

`TenantInsightsConfig.@PostConstruct` runs once at app startup and
performs:

1. Load `tenants/_schema.json`. If missing, log a warning and skip
   strict-mode checks (still works, but tenant typos won't fail loud).
2. **Validate `tenants/_base/config/insights.yaml`** — must exist, parse,
   and every correlation entry must have `id`, `message`,
   `left.elementPattern`, `right.elementPattern`. If anything is wrong,
   throws `IllegalStateException` — **the JVM does not start**. Base is
   the contract.
3. If `tenant.id` is set and isn't `_base` / `default`:
   - Walk `tenants/<id>/` and check every file is declared in
     `_schema.json`. **Strict mode**: undeclared file → JVM does not
     start, with the offending path printed.
   - Validate `tenants/<id>/config/insights.yaml` if present (same per-
     correlation validation). Append valid entries to the base list.
4. Final list of correlations is exposed via
   `TenantInsightsConfig.getCorrelations()`. `AuditServiceImpl` iterates
   over it when building the National BI dashboard's AI insight callout.

Failure modes are deliberately fail-fast: a bad tenant config never quietly
produces wrong dashboard data — it stops the deploy.

---

## 6. Adding a new tenant

```bash
mkdir -p tenants/ford/config
# Write the tenant's own insights.yaml (or skip the file entirely if no
# tenant-specific insights — then base's empty list is the result)
```

Example `tenants/ford/config/insights.yaml`:

```yaml
correlations:
  - id: bay_signage
    left:
      elementPattern: '%service bay%'
      questionPattern: '%cleanli%'
    right:
      elementPattern: '%showroom signage%'
    message: 'Service bay cleanliness and showroom signage failures co-occur in {pct}% of cases.'
```

Then deploy with `TENANT_ID=ford` (env var on `docker run` or
`spring.profiles.active`). The startup log will say `Loaded N BI insight
correlation(s) for tenant 'ford'`.

If you add tenant-specific files beyond `insights.yaml`, the schema also
needs an entry — strict mode rejects undeclared files.

---

## 7. Adding a new overridable config file

Let's say you want every tenant to be able to override a "compliance
threshold" YAML.

1. Add to `tenants/_schema.json`:
   ```json
   {
     "path": "config/compliance-thresholds.yaml",
     "requiredInBase": true,
     "requiredInTenant": false,
     "description": "RAG threshold percentages for the dashboard band counts.",
     "validate": { "type": "yaml-thresholds" }
   }
   ```
2. Create `tenants/_base/config/compliance-thresholds.yaml` with sensible
   defaults.
3. Add a Spring component (e.g. `TenantThresholdsConfig`) that loads it
   at startup, validates it, and is injected wherever the thresholds are
   read today (currently the `BAND_GREEN_PCT` / `BAND_AMBER_PCT` constants
   in `AuditServiceImpl`).
4. Extend `TenantInsightsConfig.parseAndValidate` (or factor out a
   reusable validator) for the new validator type.

Anything that's a "tenant business decision" belongs in `tenants/`, not in
hardcoded Java constants.

---

## 8. Common questions

**Q: I see `Loaded 0 BI insight correlation(s)` but I expected the Kia paver/signage one.**
A: Either `tenant.id` isn't `kia` (check `application.properties` and the
`TENANT_ID` env var), or `tenant.config.path` doesn't point at the right
folder. Inside the docker container the path is `./tenants` and the
working directory is `/app` — if you removed the `COPY ./tenants` line
from the Dockerfile, this is the symptom.

**Q: My tenant's `insights.yaml` is malformed and the JVM won't start.**
A: That's the design. Read the exception message — it names the file and
the field. Fix the YAML, redeploy.

**Q: Can a tenant override a single correlation in the base list?**
A: Today, no. Tenant correlations are *appended* to base. Since base ships
empty (`correlations: []`), this is moot. If a future tenant wants to
*remove* a base-shipped correlation, add a `disable:` field to the
correlation entry and have the merger filter it out.

**Q: Why is base empty and not the Kia stuff?**
A: Because base is the unbranded AuditPro product, not Kia's
configuration. Kia's correlations are Kia-vocabulary ("paver", "signage")
and would mean nothing to a different tenant's checksheet. The base ships
the *capability* (the SQL co-occurrence engine) without the Kia content.
