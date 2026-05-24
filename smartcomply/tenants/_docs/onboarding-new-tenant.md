# Onboarding a new tenant — backend seed data

This is the runbook for creating a new tenant's data (users + departments
+ dealerships + geography) so it auto-seeds on every fresh deploy.

If you're looking for the architecture / theory, that's in
`docs/tenant-overlay.md`. This doc is the "I need to onboard tenant Ford
on Monday — here's exactly what I do" guide.

> Frontend / mobile branding (logos, theme, labels) is a separate
> concern — see `smartcomply-angular/docs/tenant-overlay.md` and
> `auditpro-mobile-app/docs/tenant-overlay.md`.

---

## What "onboarding" means

For each new tenant we add to AuditPro, three things need to happen
backend-side:

| Layer | What | Where it lives | When it runs |
|---|---|---|---|
| Tenant runtime config | BI dashboard insights | `tenants/<id>/config/insights.yaml` | Read at JVM startup |
| Tenant seed data | Users, departments, dealerships, geography | `src/main/resources/db/tenants/<id>/V100.xxx__*.sql` | Auto-applied by Flyway on first boot |
| Tenant demo data (optional) | Showcase audits + AI assessments | `dev/seed-<id>-demo.py` (manual) | Run by hand against UAT, never prod |

This doc focuses on the middle row — the Flyway data migrations — because
that's what makes a fresh server deploy zero-touch for the tenant.

---

## Step 1 — Get the data

You need a customer-supplied source for each of:

- **User list** (operators, validators, approvers, dept heads). Usually an
  Excel sheet from the customer's IT contact. Columns you need: username,
  email, first name, last name, mobile, role (which AuditPro role they map
  to). The customer provides one **default password** they want set —
  treat as throwaway, force change on first login (not yet implemented but
  on the roadmap).
- **Dealership / location list**. Excel with: dealer name, address, city,
  state, region, type (sales / service / 3S / etc.), pin code, optional
  phone / email / website.
- **Department structure** — usually inherited from the master departments
  Flyway already seeds in V1.22 (Channel Development, Sales, Service).
  Most tenants don't need a custom department; if they do, you'll be
  inserting a row into `departments` with a fresh id.

Get the spreadsheets. Park them somewhere shared (Google Drive, the
`auditpro/dev/customer-handoff/<tenant>/` folder, or wherever the team
keeps customer assets — they don't go in this repo).

---

## Step 2 — Lay out the tenant folder

```bash
TENANT=ford   # whatever the tenant id is
mkdir -p src/main/resources/db/tenants/$TENANT
mkdir -p tenants/$TENANT/config
```

Two folders because Flyway data migrations and runtime tenant config live
in different trees:

| Folder | Purpose |
|---|---|
| `src/main/resources/db/tenants/<id>/` | Flyway data migrations (auto-run at boot) |
| `tenants/<id>/config/` | Runtime tenant config (insights.yaml, read at startup) |

Both ship inside the docker image (see `Dockerfile` — `COPY ./tenants` and
the WAR includes `src/main/resources/db/tenants` automatically). So a
single image works for any tenant — `docker run -e TENANT_ID=<id>` picks
which one is active.

---

## Step 3 — Write `V100.001__seed_<tenant>_users.sql`

Use the existing Kia migration as a template:

```bash
cp src/main/resources/db/tenants/kia/V100.001__seed_kia_users.sql \
   src/main/resources/db/tenants/$TENANT/V100.001__seed_${TENANT}_users.sql
```

Then edit. The file has three INSERT blocks; here's what each does and
what to change:

### 3a. Department row (optional)

```sql
INSERT INTO departments (name, department_id, created_at, created_by)
SELECT 'Sales', 2, CURRENT_TIMESTAMP, 1
WHERE NOT EXISTS (...);
```

If the tenant uses one of the master departments (id 1 / 2 / 3 from
V1.22), keep this as a no-op. If they need their own department, give it
a fresh id starting at 100 (master departments use 1–99, so 100+ is yours
to allocate).

### 3b. Users

```sql
INSERT INTO users (id, username, email, first_name, last_name, mobile,
                   password, status, fail_login_count, created_at, created_by)
SELECT v.id, v.username, ... FROM (VALUES
  (10, 'KIA_SALES_DEPT_HEAD', ...),
  (11, '...', ...)
) AS v(...)
WHERE NOT EXISTS (
  SELECT 1 FROM users u WHERE u.username = v.username OR LOWER(u.email) = LOWER(v.email)
);
```

Three things to change:

1. **Pick a fresh id range.** Don't collide with other tenants. Convention:
   - Kia: ids 10–99
   - Tenant 2: ids 100–199
   - Tenant 3: ids 200–299
   - Allocate a 100-id block per tenant when you start. Document the
     range in a comment at the top of the file.
2. **Hash the password.** All users share the customer's chosen default.
   Generate a bcrypt hash:
   ```bash
   python3 -c "import bcrypt; print(bcrypt.hashpw(b'TheirPassword', bcrypt.gensalt()).decode())"
   ```
   The `$2a$10$...` string goes in the password column. The Kia file uses
   the same hash everywhere because everyone gets the same default — fine
   pattern; copy it.
3. **Customer's actual usernames + emails.** Replace the Kia ones.

### 3c. Role assignments

```sql
INSERT INTO user_role_departments (user_id, role_id, department_id, created_at, created_by)
SELECT v.user_id, v.role_id, v.department_id, CURRENT_TIMESTAMP, 1
FROM (VALUES
  (10, 2, 2),  -- KIA_SALES_DEPT_HEAD → DEPT_ADMIN role on Sales dept
  ...
) AS v(user_id, role_id, department_id)
ON CONFLICT (user_id, role_id, department_id) DO NOTHING;
```

For each user, decide which role they get and which department:

| AuditPro role | role_id | Typical mapping |
|---|---|---|
| `SUPER_ADMIN` | 1 | Internal Trika ops only |
| `DEPT_ADMIN` | 2 | Customer's departmental head |
| `SUBDEPT_ADMIN` | 3 | Customer's regional / functional head |
| `OPERATOR` | 9 | Field auditors |
| `DEALER_PRINCIPAL` | (V1.27+, see Kia overlay for id) | Dealership principal — receives intervention plans, acknowledges them. |

The customer maps their org chart to these. `department_id` is the
department they own (use the master ids — 1 / 2 / 3 — for most tenants).

### Intervention permissions (V1.27+)

If the tenant uses the Improvement Campaign / Improvement Plan feature
(most do — it's a major sales surface), make sure to grant:

| Permission | Granted to |
|---|---|
| `INTERVENTION_MANAGE` | Tenant admins / regional heads who author campaigns. |
| `INTERVENTION_ASSIGNMENT_VIEW` | Anyone who needs to read plan lists / details / BI summaries. Usually granted broadly to viewer roles. |
| `INTERVENTION_ASSIGNMENT_MANAGE` | Admins who can close-as-non-compliant or override plan state. |
| `INTERVENTION_ASSIGNMENT_ACKNOWLEDGE` | The DEALER_PRINCIPAL role only. |
| `INTERVENTION_CONDUCT` | Operators who run re-inspection waves (typically same set as `CHECKSHEET_FILL_ANSWER`). |

Also: every dealership that should receive plans needs a
`audit_assignments.dealer_principal_user_id` set to the dealer
principal user. The acknowledge / myPlans endpoints scope server-side
on that FK regardless of permission. If the tenant doesn't yet have a
DEALER_PRINCIPAL user per dealership, that's the gating step before
plans become actionable.

See [`system-overview.md`](./system-overview.md) §10 for the full
permissions matrix.

---

## Step 4 — Write `V100.002__seed_<tenant>_dealerships.sql`

```bash
cp src/main/resources/db/tenants/kia/V100.002__seed_kia_dealerships.sql \
   src/main/resources/db/tenants/$TENANT/V100.002__seed_${TENANT}_dealerships.sql
```

This file has six INSERT blocks. They MUST run in this order because of
foreign keys:

1. **`countries`** — usually one row (`India`). Use a fresh id if your
   tenant operates in a different country.
2. **`regions`** — North/South/East/West/Central/Northeast for India.
   Customer-defined region structure.
3. **`states`** — every state the tenant operates in. Each row has a
   `region_id` FK — group by region.
4. **`cities`** — every city. Each has a `state_id` FK.
5. **`auditee_types`** — Sales / Service / 3S etc. Customer-specific
   business categorisation. Use ids 1+ and don't collide with Kia's
   types if you share a UAT instance.
6. **`auditees`** — the dealerships themselves. Each has `created_by`
   FK to a user (use id 1 — the master `superadmin` — or one of your
   tenant users if you prefer).
7. **`auditee_locations`** — the physical sites. A dealer can have
   multiple locations; this table is what gets audited. Each has
   `auditee_id`, `city_id`, `auditee_type_id` FKs.

### Generating the SQL from a spreadsheet

There's no automated tool. The Kia migrations were built by hand from
the customer's Excel. Easiest path: open the spreadsheet, write a small
Python or Excel macro that emits the `(id, name, ...)` value tuples, and
paste them into the migration. Keep the format identical to the Kia
migration so future maintainers find it familiar.

### ID ranges for dealerships

Allocate non-overlapping ranges per tenant — same as users:

- Kia auditees: id 1–9999
- Tenant 2: id 10000–19999
- Tenant 3: id 20000–29999

Locations: same ranges, separate space (auditee_locations has its own id
sequence).

If you ever **share a UAT instance** between two tenants for testing
(not a real production setup, but it happens), the non-overlapping ranges
keep them from stomping on each other. In a real production deploy each
tenant has its own DB, so this is belt-and-braces.

---

## Step 5 — Write `tenants/<id>/config/insights.yaml`

The runtime tenant config — what the National BI dashboard's "AI
insights" callout says for this tenant. Start with empty:

```yaml
# tenants/ford/config/insights.yaml
correlations: []
```

Empty is fine. Adds nothing to the dashboard. When the customer's audit
data exposes a real cross-checkpoint pattern (like Kia's paver+signage
co-occurrence), come back and add an entry. See `docs/tenant-overlay.md`
section 2 for the schema.

---

## Step 6 — Test locally

You'll need a clean local DB to test the auto-seed properly. Two
options:

```bash
# Option A — fresh local DB on a new schema
psql -U bijalsanghavi -d postgres -c "CREATE DATABASE smartcomply_${TENANT};"

# Option B — wipe + re-baseline existing local DB (faster, but
# destructive)
psql -d smartcomply -f scripts/recreate-db.sh   # wipes everything
```

Then boot with the new tenant id:

```bash
TENANT_ID=ford ./bin/restart.sh
```

Watch the log for:

```
o.f.core.internal.command.DbMigrate : Migrating schema "public" to version "100.001 - seed ford users"
o.f.core.internal.command.DbMigrate : Migrating schema "public" to version "100.002 - seed ford dealerships"
o.f.core.internal.command.DbMigrate : Successfully applied N migrations
```

Sanity-check the data landed:

```sql
SELECT username FROM users WHERE id BETWEEN 100 AND 199;
SELECT name FROM auditees WHERE id BETWEEN 10000 AND 19999 LIMIT 5;
```

And verify Kia data did NOT land (since we're tenant=ford):

```sql
SELECT username FROM users WHERE id BETWEEN 10 AND 99;
-- Should return zero rows on a fresh DB.
```

---

## Step 7 — Deploy

The CI / Docker pipeline doesn't change. Push the migration files,
build a new image, deploy with `TENANT_ID=<tenant>`:

```bash
# In .gitlab-ci.yml deploy step (or a CI variable):
docker run -d \
  -e TENANT_ID=ford \
  -e SPRING_PROFILES_ACTIVE=uat \
  ... \
  gitlab.tiez.net:5050/tiez/smartcomply:latest
```

On first boot of a fresh tenant DB, Flyway applies V1.0 → V1.26
(universal schema) plus V100.001 + V100.002 (Ford data). Done.

If you're deploying onto an existing DB that already has the data
(e.g. someone manually inserted Ford rows previously), the migrations
no-op via `ON CONFLICT DO NOTHING` and Flyway records them as applied.
Safe.

---

## Conventions and gotchas

### Mandatory: idempotency

Every INSERT must use `ON CONFLICT (key) DO NOTHING` (or an equivalent
`WHERE NOT EXISTS`). Reason: existing UAT/prod DBs may already have the
data; the migration must be safe to apply against an already-seeded DB.
Look at the Kia migrations for the patterns — copy them.

### Versioning

| Pattern | Use for |
|---|---|
| `V100.001`, `V100.002`, …, `V100.999` | First version of each tenant's seed (one file per concern: users, dealers, etc.) |
| `V101.001`, `V101.002`, … | Follow-up additions to a tenant (e.g. "add 50 new dealers acquired in Q3") |

The same version number across different tenant folders is fine — they
never collide because Flyway only loads one tenant's location per JVM
boot. So Kia's `V100.001` and Ford's `V100.001` are independent.

Universal schema migrations stay in `db/migration/` with the existing
`V1.x` convention.

### NOT supported in Flyway SQL

These are psql-client-only meta-commands; Flyway runs SQL through JDBC
and ignores or errors on them:

- `\set`, `\echo`, `\prompt`, `\copy`, `\i`
- `:variable` substitution

Use:
- Hardcoded values (the password hash is the same for all users — bake
  it in)
- Or Flyway placeholders via `${name}` if you really need substitution
  (configure with `spring.flyway.placeholders.<name>=...` — see Spring
  Boot docs)

### Foreign key ordering

`auditee_locations` references `auditees`, `cities`, `auditee_types`.
`cities` references `states`. `states` references `regions`. `regions`
references `countries`. Bottom-up: countries → regions → states →
cities → auditee_types → auditees → auditee_locations. Don't reorder.

### Password hashing

```bash
python3 -c "import bcrypt; print(bcrypt.hashpw(b'YourPassword', bcrypt.gensalt(10)).decode())"
```

Cost factor 10 — same as the rest of the codebase. The customer-supplied
default goes in plaintext into bcrypt; the resulting `$2a$10$...` string
goes into the SQL. The plaintext should be communicated to the customer
through whatever secure channel you already use; **don't commit it**.

### Adding more dealers later

Three months in, the customer adds 50 dealerships. You write a follow-up
migration:

```bash
# tenants/ford/V101.001__ford_q3_dealer_acquisition.sql
INSERT INTO auditees (id, name, ...) VALUES
  (10628, 'New Ford Dealer 1', ...),
  ...
ON CONFLICT (id) DO NOTHING;

INSERT INTO auditee_locations (id, auditee_id, ...) VALUES
  ...
ON CONFLICT (id) DO NOTHING;
```

Don't edit `V100.002` — that one's already applied. Add `V101.x` for
each batch of changes. Over a year you might accumulate 5–10 V101.x
files; that's fine, it's the audit trail.

### What about updates / deletes?

Flyway data migrations are forward-only. If the customer renames a
dealer, a migration with `UPDATE auditees SET name=… WHERE id=…` is the
right pattern. For a delete, soft-delete:
`UPDATE auditees SET deleted_at = NOW() WHERE id=…`. Hard deletes
through Flyway are dangerous — operational ops do those, not migrations.

---

## What about the demo audit data?

The `tenants/kia/bin/seed-kia-demo.py` script — the 200-audit showcase with photos
and Gemini AI assessments — is **not** part of tenant onboarding. It's
sales material:

- It runs against the API, not the DB directly
- It makes ~1500 real Gemini Flash calls (~$0.50 per run)
- It generates fictional audit data, not the customer's real audits
- It's only ever run on dev / UAT, never prod

For a new tenant's sales-pitch UAT, you'd write `dev/seed-ford-demo.py`
(parallel to the Kia one) using their actual checksheet template and
their real dealerships (already seeded by V100.002). Or, more often, you
demo with their DEV environment populated by their own auditors doing a
small pilot.

This is intentional: never let a script that costs money + creates
fake data anywhere near a production deploy.

---

## Summary checklist

When onboarding tenant `<id>`:

- [ ] Get user list (Excel) from customer
- [ ] Get dealership list (Excel) from customer
- [ ] Pick non-overlapping id ranges (users + auditees + locations)
- [ ] `mkdir -p src/main/resources/db/tenants/<id>`
- [ ] Write `V100.001__seed_<id>_users.sql` (departments + users + role assignments)
- [ ] Write `V100.002__seed_<id>_dealerships.sql` (countries → regions → states → cities → auditee_types → auditees → auditee_locations)
- [ ] `mkdir -p tenants/<id>/config`
- [ ] Write `tenants/<id>/config/insights.yaml` (start with `correlations: []`)
- [ ] Test locally: fresh DB, `TENANT_ID=<id> ./bin/restart.sh`, verify Flyway log + sample SELECTs
- [ ] Verify other tenants' data didn't leak (e.g. Kia users absent on a Ford DB)
- [ ] Add a CI variable `TENANT_ID=<id>` to the deploy job (or override per-environment)
- [ ] Coordinate frontend overlay with the BI / mobile team — see those repos' `docs/tenant-overlay.md`
- [ ] Hand the customer their default password through your secure channel
