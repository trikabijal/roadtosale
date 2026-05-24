# Re-tenant Scripts

Scripts to repurpose the AuditPro backend for a new company. Two flows are
supported; **Flow A is recommended** for any new tenant.

| File | Purpose |
|------|---------|
| `recreate-db.ps1` / `recreate-db.sh` | **(Recommended)** Backup, drop, and recreate the `smartcomply` database, then apply the full schema + Flyway baseline + master data automatically. No manual app-start needed. |
| `flyway-baseline.sql` | Applied automatically by recreate-db scripts. Creates the `ai_assessments` table (added by V1.19/V1.20) and populates `flyway_schema_history` so the app's Flyway has nothing to run on first boot. |
| `retenant.sql` | (In-place alternative) Truncate audit/operational tables in an existing DB while keeping `roles`, `permissions`, `role_permissions`, and `departments`. |
| `seed-users.sql` | Seed 9 starter users (one per role) with a shared BCrypt-hashed temporary password. Run after either flow. |

---

## What is kept vs wiped

**Kept (master / config):**
- `roles`, `permissions`, `role_permissions` — RBAC seeded by Flyway V1.8 / V1.9 / V1.13–V1.18
- `departments` — your master list
- `lov_data`, `app_versions`
- `flyway_schema_history`

**Wiped (audit / operational / per-tenant):**
- All `chks_*` template tables (`checksheets`, `chks_headers`, `chks_header_data`, `chks_questions`, `chks_question_results`, options, matrices, files, general fields)
- All `user_checksheet_*` filled-audit tables (answers, files, matrix answers, approvals/validations + history, trace values)
- `usr_chksheet_ans_judgements` + files
- `ai_assessments`
- `npd_master`, `surprise_checksheets`, `surprise_checksheet_fields`
- `users`, `user_role_departments`, `refresh_token`, `api_history`

---

## Flow A — Drop & recreate database (RECOMMENDED)

Cleanest. No FK gymnastics. Sequences reset naturally. Same outcome every time.

### Prereqs
- PostgreSQL client tools (`psql`, `pg_dump`) on PATH
- Superuser credentials (e.g. `postgres`, or `rds_superuser` on AWS RDS)
- Spring Boot app **stopped**

### Run (Windows)
```powershell
./scripts/recreate-db.ps1 -PgHost localhost -SuperUser postgres -DbName smartcomply
```

### Run (Linux / macOS / WSL)
```bash
chmod +x scripts/recreate-db.sh
PGHOST=localhost SUPERUSER=postgres ./scripts/recreate-db.sh
```

The script will:
1. Prompt you to type the DB name to confirm.
2. `pg_dump` the existing DB to `./backups/smartcomply-<timestamp>.sql` (skip with `-NoBackup` / `NO_BACKUP=1`).
3. Terminate live connections, `DROP DATABASE`, `CREATE DATABASE`.
4. Apply `docs/original-schema.sql` — all tables and indexes in their final state.
5. Apply `scripts/flyway-baseline.sql` — creates the `ai_assessments` table (added by V1.19/V1.20 but absent from `original-schema.sql`) and inserts all 21 migration rows into `flyway_schema_history` so Flyway does nothing on first boot.
6. Apply `docs/seed-data.sql` — roles (9), permissions (79), role_permissions (278).

> **Why flyway-baseline.sql?** `V1.0__create_table.sql` is an empty placeholder file — the original schema
> was created by Hibernate `ddl-auto`, not Flyway. On a fresh DB, letting Flyway run from V1.1 would
> fail because V1.11 tries to rename a column that `original-schema.sql` already renamed, and V1.2
> references `checksheets` before any tables exist. The baseline step bypasses all of that.

### After it finishes
1. Seed users (see "Seed users" section below).
2. Start the app (Flyway will detect all migrations already done and skip them):
   ```powershell
   ./mvnw package -DskipTests
   java -jar target/smartcomply-0.0.1-SNAPSHOT.war
   ```
   > **Windows note:** `./mvnw spring-boot:run` fails with *"filename too long"* (CreateProcess error=206)
   > because the classpath exceeds the Windows command-line limit. Always use `package` + `java -jar`.
3. Log in as `superadmin` with the temporary password and begin configuration.

---

## Flow B — In-place truncate (alternative)

Use only if you cannot drop the database (e.g., shared instance, locked-down
RDS without `CREATE DATABASE` privilege).

### Prereqs
- Spring Boot app **stopped**
- Superuser connection (script uses `session_replication_role='replica'` to
  bypass FK trigger checks — that requires elevated privileges)
- A fresh `pg_dump` backup taken manually

### Run
```bash
psql -h <host> -U <super> -d smartcomply -f scripts/retenant.sql
```

The script wraps everything in one transaction with `RESTART IDENTITY` so
sequences reset to 1, then NULLs `created_by/updated_by/deleted_by` on the
retained master tables (so they don't dangle to the now-deleted user rows).

---

## Seed users (run after either flow)

```bash
psql -h <host> -U <app_user> -d smartcomply \
     -v email_domain=acme.com \
     -v temp_pwd='ChangeMe!2026' \
     -f scripts/seed-users.sql
```

| Variable | Default | Description |
|----------|---------|-------------|
| `email_domain` | `example.com` | Domain for all 9 user emails |
| `temp_pwd` | `Welcome@123` | Shared temporary password (BCrypt-hashed via `pgcrypto`) |

The 9 users (IDs 1..9) created:

| Username | Role |
|----------|------|
| `superadmin` | SUPER_ADMIN |
| `depthead` | DEPT_ADMIN |
| `sectionhead` | SUBDEPT_ADMIN |
| `operator` | OPERATOR |
| `preparer` | CHK_SHT_PREPARE |
| `validator` | CHK_SHT_VALIDATOR |
| `approver` | CHK_SHT_APPROVER |
| `datavalidator` | CHK_SHT_DATA_VALIDATOR |
| `dataapprover` | CHK_SHT_DATA_APPROVER |

`users_id_seq` is set to start at 100 so future app-created users do not
collide with the seeded IDs.

> The seed users have **no department assigned** (`user_role_departments.department_id IS NULL`).
> Assign each one to the right department via the User Edit screen after login.

---

## Essentials & gotchas

1. **Stop the app before either flow.** Live sessions write to
   `refresh_token` / `api_history` mid-truncate and break the FK detach step.
2. **Take a `pg_dump` backup.** Both flows are irreversible.
3. **`pgcrypto` extension** is required for `seed-users.sql`. The script
   does `CREATE EXTENSION IF NOT EXISTS pgcrypto`. On managed Postgres you
   need the privilege (RDS: `rds_superuser`).
4. **`session_replication_role`** in `retenant.sql` requires superuser. If
   denied, ask for an alternative (per-table `DISABLE TRIGGER ALL`).
5. **Files on S3 / disk are not touched** by these scripts. If you want to
   purge uploaded files, snapshot the path columns first:
   ```sql
   COPY (
     SELECT path FROM chks_header_data_files     WHERE path IS NOT NULL UNION ALL
     SELECT path FROM chks_question_files        WHERE path IS NOT NULL UNION ALL
     SELECT path FROM usr_chksheet_ans_judgement_files WHERE path IS NOT NULL UNION ALL
     SELECT unnest(file_paths) FROM surprise_checksheet_fields WHERE file_paths IS NOT NULL
   ) TO '/tmp/files-to-delete.txt';
   ```
   Then delete from your storage backend (S3 `aws s3 rm` / local `rm`).
6. **Force password reset on first login** for all 9 seed users — `temp_pwd`
   is a known-shared secret.
7. **`spring.jpa.hibernate.ddl-auto`** must be `none` or `validate`. Never
   `create` / `create-drop` — it would wipe the schema on every restart.
8. **For repeated re-tenanting**, use Flow A every time. It guarantees the
   same end-state regardless of what's in the source DB.

---

## Verification

After seeding, this query should return 9 rows mapping each user to the
correct role code:

```sql
SELECT u.id, u.username, u.email, r.role_code
FROM   users u
JOIN   user_role_departments urd ON urd.user_id = u.id
JOIN   roles r                   ON r.id        = urd.role_id
ORDER  BY u.id;
```

And every wiped table should be empty (or 9 for `users` /
`user_role_departments`):

```sql
SELECT 'checksheets', count(*) FROM checksheets
UNION ALL SELECT 'user_checksheets',        count(*) FROM user_checksheets
UNION ALL SELECT 'user_checksheet_answers', count(*) FROM user_checksheet_answers
UNION ALL SELECT 'ai_assessments',          count(*) FROM ai_assessments
UNION ALL SELECT 'refresh_token',           count(*) FROM refresh_token
UNION ALL SELECT 'users (=9)',              count(*) FROM users
UNION ALL SELECT 'roles (KEEP)',            count(*) FROM roles
UNION ALL SELECT 'permissions (KEEP)',      count(*) FROM permissions
UNION ALL SELECT 'role_permissions (KEEP)', count(*) FROM role_permissions
UNION ALL SELECT 'departments (KEEP)',      count(*) FROM departments;
```
