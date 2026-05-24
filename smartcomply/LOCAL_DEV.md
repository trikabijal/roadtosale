# Local development — backend (smartcomply)

## Prerequisites

- **Java 17** (`java -version`)
- **PostgreSQL 14+** running locally on port 5432
- **Maven** wrapper ships with the repo (`./mvnw`)
- A PostgreSQL database created for this app:

  ```bash
  createdb smartcomply
  ```

## First-time setup

1. Copy `.env.example` to `.env` and fill in DB credentials.
2. Make sure `application-local.properties` points at your local DB
   (the file is gitignored, so each dev configures their own).
3. Run:

   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
   ```

   On first boot, Flyway runs every migration in
   `src/main/resources/db/migration/` to bring the schema up to V1.26.
   No tenant seed data is loaded — the schema is empty by default.

4. App is now serving on `http://localhost:8089`.

## Optional: load kia demo data

If you want a populated DB to test BI dashboards or the mobile app
end-to-end, activate the `tenant-data` profile (which adds the
`db/tenants/kia/` Flyway location):

```bash
SPRING_PROFILES_ACTIVE=local,tenant-data TENANT_ID=kia ./mvnw spring-boot:run
```

This seeds the kia users + dealerships. To go further (200 audits with
photos and AI assessments), run the seed scripts:

```bash
psql -d smartcomply < tenants/kia/bin/seed-test-data.sql
python3 tenants/kia/bin/seed-kia-demo.py        # needs GEMINI_KEY in env
python3 tenants/kia/bin/seed-multi-location.py
python3 tenants/kia/bin/backfill-remarks.py
```

## Profiles cheat-sheet

| Profile           | What it does                                                |
|-------------------|-------------------------------------------------------------|
| `local`           | Local DB connection + dev-friendly logging                  |
| `tenant-data`     | Adds `db/tenants/${tenant.id}/` to Flyway locations         |
| `uat`             | UAT env config; auto-includes `tenant-data` via profile group |

So:

- `SPRING_PROFILES_ACTIVE=local` → empty schema, default for daily dev
- `SPRING_PROFILES_ACTIVE=local,tenant-data` → schema + kia seed users
- `SPRING_PROFILES_ACTIVE=uat` → UAT config + kia seed (deploy default)

## Common gotchas

- **Flyway migration V1.24 fails** with "column already exists" or FK
  errors on a DB that has prior data. V1.24 was structural — it
  re-shaped `user_checksheets`. The safest reset:

  ```bash
  dropdb smartcomply && createdb smartcomply
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
  ```

  Or use `flyway repair` if you've manually patched the schema.
- **Boot fails with `tenant.id is unset`** — should not happen, but if
  it does, set `TENANT_ID=kia` in your env.
- **`db/tenants/kia/` migrations don't run** — you didn't activate
  `tenant-data`. That's the new default; see above.

## Build for deploy

```bash
./mvnw clean package -Dtest='!com.checkSheet.demo.ApplicationTests'
# → target/smartcomply-0.0.1-SNAPSHOT.war
```

CI does this automatically — see `.gitlab-ci.yml`.
