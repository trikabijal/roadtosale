   #!/usr/bin/env bash
# ============================================================================
# AuditPro - Recommended re-tenant flow (Linux / macOS / WSL).
# Drops smartcomply, recreates it, prompts user to start app for Flyway,
# then to seed users.
# ============================================================================
set -euo pipefail

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
SUPERUSER="${SUPERUSER:-postgres}"
DBNAME="${DBNAME:-smartcomply}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
NO_BACKUP="${NO_BACKUP:-0}"

usage() {
    cat <<EOF
Usage: $0 [options]
  Env vars:
    PGHOST       (default: localhost)
    PGPORT       (default: 5432)
    SUPERUSER    (default: postgres)
    DBNAME       (default: smartcomply)
    BACKUP_DIR   (default: ./backups)
    NO_BACKUP=1  skip pg_dump (dev only)

  Example:
    PGHOST=db.internal SUPERUSER=postgres ./scripts/recreate-db.sh
EOF
}
[[ "${1:-}" == "-h" || "${1:-}" == "--help" ]] && { usage; exit 0; }

require_cmd() { command -v "$1" >/dev/null || { echo "Missing required command: $1" >&2; exit 1; }; }
require_cmd psql
[[ "$NO_BACKUP" != "1" ]] && require_cmd pg_dump

echo "==> Target: ${SUPERUSER}@${PGHOST}:${PGPORT}  database=${DBNAME}"
read -r -p "This will DROP and RECREATE '${DBNAME}'. Type the database name to confirm: " confirm
[[ "$confirm" != "$DBNAME" ]] && { echo "Aborted."; exit 1; }

# 1. Backup
if [[ "$NO_BACKUP" != "1" ]]; then
    mkdir -p "$BACKUP_DIR"
    stamp="$(date +%Y%m%d-%H%M%S)"
    backup="${BACKUP_DIR}/${DBNAME}-${stamp}.sql"
    echo "==> Backing up to $backup"
    pg_dump -h "$PGHOST" -p "$PGPORT" -U "$SUPERUSER" -d "$DBNAME" -F p -f "$backup"
    echo "    Backup OK"
fi

# 2. Terminate live connections
echo "==> Terminating live connections to $DBNAME"
psql -h "$PGHOST" -p "$PGPORT" -U "$SUPERUSER" -d postgres -v ON_ERROR_STOP=1 -c "
SELECT pg_terminate_backend(pid)
FROM   pg_stat_activity
WHERE  datname = '${DBNAME}' AND pid <> pg_backend_pid();
"

# 3. Drop + create
echo "==> Dropping $DBNAME"
psql -h "$PGHOST" -p "$PGPORT" -U "$SUPERUSER" -d postgres -v ON_ERROR_STOP=1 \
     -c "DROP DATABASE IF EXISTS \"${DBNAME}\";"

echo "==> Creating $DBNAME"
psql -h "$PGHOST" -p "$PGPORT" -U "$SUPERUSER" -d postgres -v ON_ERROR_STOP=1 \
     -c "CREATE DATABASE \"${DBNAME}\";"

# Helper
run_sql_file() {
    local label="$1" file="$2"
    echo "==> $label"
    psql -h "$PGHOST" -p "$PGPORT" -U "$SUPERUSER" -d "$DBNAME" -v ON_ERROR_STOP=1 -f "$file"
}

# 4. Apply base schema (all tables except ai_assessments)
run_sql_file "Applying base schema (docs/original-schema.sql)" "docs/original-schema.sql"

# 5. Create ai_assessments table + populate flyway_schema_history
#    Flyway's V1.0 is an empty placeholder; the original schema was created by
#    Hibernate ddl-auto. This step creates the missing ai_assessments table
#    (added by V1.19 + V1.20) and marks all migrations as done so Flyway
#    has nothing to run on first app boot.
run_sql_file "Applying Flyway baseline (scripts/flyway-baseline.sql)" "scripts/flyway-baseline.sql"

# 6. Seed roles, permissions, role_permissions
run_sql_file "Seeding master data (docs/seed-data.sql)" "docs/seed-data.sql"

cat <<EOF

Schema and master data applied successfully.

Next: seed the 9 fresh users and start the app.

  psql -h ${PGHOST} -p ${PGPORT} -U ${SUPERUSER} -d ${DBNAME} \\
       -v email_domain=acme.com -v temp_pwd='ChangeMe!2026' \\
       -f scripts/seed-users.sql

  Then start the app (Flyway will do nothing; schema is already complete):
    ./mvnw package -DskipTests
    java -jar target/smartcomply-0.0.1-SNAPSHOT.war

  Log in as superadmin and force a password change for all seed users.
EOF
