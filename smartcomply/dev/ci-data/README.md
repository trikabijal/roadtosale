# CI database setup

The backend `test:e2e` job (and the frontend `qc:e2e` job) creates an
ephemeral Postgres database per pipeline run on the **UAT Postgres
server** (`172.31.0.157:5432`), runs the test suite against it, and
drops it on success. This file documents the one-time DBA step that
makes those jobs work, and the credentials convention they use.

## One-time setup — `CREATE ROLE` on the UAT Postgres

A DBA with superuser on `172.31.0.157` runs this once:

```sql
CREATE ROLE smartcomply_ci_user
  WITH LOGIN
       CREATEDB
       PASSWORD 'smartcomply_ci_only_isolated_dbs';

-- Optional but recommended hardening:
ALTER ROLE smartcomply_ci_user CONNECTION LIMIT 10;
```

That's it. The role has `CREATEDB` so it can `CREATE DATABASE
smartcomply_ci_<pipeline_iid>` and `DROP DATABASE` afterwards, but
**no `GRANT` on the live `smartcomply` database**, so it cannot read
or modify UAT data even if its password leaks.

## Why the password is in plaintext in `.gitlab-ci.yml`

The role's blast radius is intentionally limited to its own
ephemeral databases. Reasoning:

- An attacker with the password can connect to `172.31.0.157:5432`
  (assuming they can also reach it on the network, which is VPN-gated
  for both UAT runners and developer laptops).
- Once connected, they can `CREATE DATABASE foo;` and `DROP DATABASE
  foo;` for databases this role owns. They can `\l` to list, but
  cannot `\c smartcomply` or read any of its tables.
- They cannot touch the live `smartcomply` schema, users, audits,
  inspections, photos, or any UAT data.

So the password is treated as a discoverable-but-low-value
configuration value, like the database name. Putting it in
`.gitlab-ci.yml` instead of a GitLab masked CI variable means:

- One less secret to rotate.
- Local dev runs `./dev/scripts/test-ci.sh` against the same UAT
  Postgres without needing access to GitLab's CI variables, by
  exporting `CI_DB_HOST=172.31.0.157 CI_DB_USERNAME=smartcomply_ci_user
  CI_DB_PASSWORD=smartcomply_ci_only_isolated_dbs` (or just running
  against `localhost` with their own user, which is the script's
  default).
- The "what's the password?" question has a public answer instead of
  a "ping me in slack" answer.

The high-value credential — `dilipv`, which owns the live
`smartcomply` database — stays in `application-uat.properties` and
nowhere else. CI never uses it.

## Local dev

For local development, `./dev/scripts/test-ci.sh` defaults to:
- `CI_DB_HOST=localhost`
- `CI_DB_USERNAME=$USER`
- `CI_DB_PASSWORD=` (empty — relies on local Postgres trust auth)
- `CI_DB_NAME=smartcomply_ci`

The CI environment variables in `.gitlab-ci.yml` override these to
point at the UAT Postgres + the dedicated CI role. Same script, both
environments.

## Refreshing the baseline dump

`dev/ci-data/baseline.sql.gz` is the schema + minimum data the test
suites need (excludes photos, AI assessments, the rolling api_history
log). Regenerate when schema migrations land or seed data changes:

```bash
./dev/scripts/refresh-ci-baseline.sh        # dumps local smartcomply
git add dev/ci-data/baseline.sql.gz
git commit -m "chore(ci): refresh baseline.sql.gz"
```
